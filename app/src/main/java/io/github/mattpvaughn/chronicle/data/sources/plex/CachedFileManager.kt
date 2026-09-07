package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.*
import androidx.work.WorkManager
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.model.isCompleteDownload
import io.github.mattpvaughn.chronicle.features.download.CacheScanOutcome
import io.github.mattpvaughn.chronicle.features.download.DownloadEvent
import io.github.mattpvaughn.chronicle.features.download.DownloadIntentStore
import io.github.mattpvaughn.chronicle.features.download.DownloadNotificationWorker
import io.github.mattpvaughn.chronicle.features.download.DownloadRequest
import io.github.mattpvaughn.chronicle.features.download.Downloader
import io.github.mattpvaughn.chronicle.features.download.isBookFullyCached
import io.github.mattpvaughn.chronicle.features.download.partialsSafeToPrune
import io.github.mattpvaughn.chronicle.features.download.prunePartialFiles
import io.github.mattpvaughn.chronicle.features.download.reconcileCachedTracks
import io.github.mattpvaughn.chronicle.features.download.scanCachedMediaDir
import io.github.mattpvaughn.chronicle.injection.qualifiers.ApplicationScope
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import javax.inject.Inject

interface ICachedFileManager {
  enum class CacheStatus { CACHED, CACHING, NOT_CACHED }

  val activeBookDownloads: StateFlow<Set<String>>

  fun cancelCaching()

  fun cancelGroup(id: String)

  fun downloadTracks(
    bookId: String,
    bookTitle: String,
  )

  suspend fun uncacheAllInLibrary(): Int

  suspend fun deleteCachedBook(bookId: String)

  suspend fun hasUserCachedTracks(): Boolean

  suspend fun refreshTrackDownloadedStatus()

  /**
   * Resumes downloads that stopped without finishing.
   *
   * Nothing did this before: `setAutoRetryMaxAttempts(1)` gave a download one retry and then
   * abandoned it, so a Wi-Fi blip ended it permanently and the book stayed partially
   * downloaded with no way back except re-requesting it by hand.
   *
   * Safe to call repeatedly — the downloader ignores tracks already running or complete.
   */
  fun resumeInterruptedDownloads()
}

interface SimpleSet<T> {
  fun add(elem: T): Boolean

  fun remove(elem: T): Boolean

  operator fun contains(elem: T): Boolean

  val size: Int
}

class CachedFileManager
  @Inject
  constructor(
    private val downloader: Downloader,
    private val downloadIntents: DownloadIntentStore,
    private val prefsRepo: PrefsRepo,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val plexConfig: PlexConfig,
    private val applicationContext: Context,
    private val workManager: WorkManager,
    private val dispatchers: DispatcherProvider,
    @ApplicationScope
    private val externalScope: CoroutineScope,
    private val externalFileDirs: List<@JvmSuppressWildcards File>,
  ) : ICachedFileManager {
    private val downloadListener =
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context?,
          intent: Intent?,
        ) {
          when (intent?.action) {
            DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS ->
              externalScope.launch {
                downloader.cancelAll()
                downloadIntents.clear()
              }
            DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD -> {
              val bookId = intent.getStringExtra(DownloadNotificationWorker.KEY_BOOK_ID)
              if (!bookId.isNullOrEmpty()) {
                Timber.i("Cancelling book: $bookId")
                externalScope.launch { cancelBookDownloads(bookId) }
              }
            }
          }
        }
      }

    /**
     * Re-enqueues everything the user asked for that is not yet on disk.
     *
     * Fetch2 owned a durable queue, so this used to be `resumeAll()` plus a `retry()` of whatever
     * `ResumePlan` picked out of *its* records. There is no engine-side queue now: the intent
     * lives in [DownloadIntentStore] and the bytes live on disk, so resuming is simply enqueueing
     * the pending tracks again — [KtorDownloader] sends a `Range` request for anything already
     * partly there, which is the same cheap resume by a different route.
     *
     * A track already in flight is ignored by the downloader rather than started twice.
     */
    override fun resumeInterruptedDownloads() {
      val pending = downloadIntents.pending()
      if (pending.isEmpty()) return
      externalScope.launch {
        val byBook = trackRepository.getAllTracksAsync().filter { it.id in pending }
        Timber.i("Resuming ${pending.size} interrupted download(s)")
        byBook.groupBy { it.parentKey }.forEach { (bookId, _) ->
          val book = bookRepository.getAudiobookAsync(bookId)
          downloadTracks(bookId, book?.title ?: "")
        }
      }
    }

    /**
     * Deletes incomplete files that no longer belong to anything.
     *
     * [DownloadIntentStore] is the authority on what is resumable — see the comment in the body
     * for why it, and not the downloader, has to answer that. The safe direction is always to keep
     * bytes: keeping a stale partial costs disk, deleting a live one costs the user their
     * download.
     */
    private fun pruneAbandonedPartials(
      incompleteOnDisk: List<String>,
      reportedCached: List<String>,
      idToFileMap: Map<String, okio.Path>,
    ) {
      if (incompleteOnDisk.isEmpty()) {
        return
      }
      // `DownloadIntentStore` replaces Fetch2's queue as the answer to "could this still be
      // resumed?". That substitution is the whole reason the store exists: Fetch2's records
      // survived a restart, and an in-memory job map does not — so reading the engine here would
      // report *nothing* pending after a relaunch and make every resumable partial look
      // abandoned. That is the app deleting the user's audio, which is why the durable record
      // came first and this call reads it rather than the downloader.
      val stillWanted = downloadIntents.pending()
      val prunable = partialsSafeToPrune(incompleteOnDisk, stillWanted, reportedCached)
      if (prunable.isEmpty()) {
        return
      }
      val outcome = prunePartialFiles(prunable, idToFileMap)
      if (outcome.failedIds.isNotEmpty()) {
        // Not an error the user can act on: the files are offered again on the next scan.
        Timber.i("Could not delete ${outcome.failedIds.size} abandoned partial(s)")
      }
      Timber.i("Pruned ${outcome.deleted} abandoned partial(s), reclaiming ${outcome.reclaimedBytes} bytes")
    }

    override fun cancelGroup(id: String) {
      externalScope.launch { cancelBookDownloads(id) }
    }

    override fun cancelCaching() {
      externalScope.launch {
        downloader.cancelAll()
        downloadIntents.clear()
      }
    }

    /**
     * Stops a book's downloads and forgets the intent, leaving bytes on disk.
     *
     * The intent must go, or the next cache scan would keep the partials alive forever as
     * "resumable" — the user cancelled, so nobody is coming back for them and the prune should be
     * free to reclaim the space.
     */
    private suspend fun cancelBookDownloads(bookId: String) {
      val trackIds = trackRepository.getTracksForAudiobookAsync(bookId).map { it.id }
      downloader.cancelBook(bookId)
      downloadIntents.remove(trackIds)
    }

    override suspend fun hasUserCachedTracks(): Boolean {
      return withContext(dispatchers.io) {
        trackRepository.getCachedTracks().isNotEmpty()
      }
    }

    override fun downloadTracks(
      bookId: String,
      bookTitle: String,
    ) {
      externalScope.launch {
        val requests = makeRequests(bookId, bookTitle)
        if (requests.isEmpty()) {
          Timber.i("Nothing to download for $bookId; every track is already cached")
          return@launch
        }
        // Recorded *before* enqueueing, not after. The intent is what keeps a partial safe from
        // the prune, so a crash between these two lines must leave the bytes protected rather
        // than orphaned — the safe direction is always to keep bytes.
        downloadIntents.add(requests.map { it.trackId })
        downloader.enqueue(requests)
        DownloadNotificationWorker.start(applicationContext, workManager)
      }
    }

    /**
     * Creates a [DownloadRequest] for every file of [bookId] that is not already fully on disk.
     */
    private suspend fun makeRequests(
      bookId: String,
      bookTitle: String,
    ): List<DownloadRequest> {
      // Gets all tracks for album id
      val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

      val cachedFilesDir = prefsRepo.cachedMediaDir
      Timber.i("Caching tracks to: ${cachedFilesDir.path}")
      Timber.i("Tracks to cache: ${tracks.map { it.id }}")

      val requests =
        tracks.mapNotNull { track ->
          // File exists but is not marked as cached in the database- more likely than not
          // this means that it has failed to fully download
          val destFile = File(cachedFilesDir, track.getCachedFileName())

          // Defence in depth. Ids are validated where a server response becomes a model
          // (`asTrackList`), so nothing should reach here unsafe — but this is the line that
          // actually writes to the filesystem, and `File(parent, child)` does not normalize. A
          // path that escapes the cache directory is refused here rather than trusted to have
          // been caught upstream, because the cost of being wrong is a write next to the Room
          // databases and the credential file.
          if (!destFile.canonicalPath.startsWith(cachedFilesDir.canonicalPath + File.separator)) {
            Timber.e(
              "Refusing to download track ${track.id}: '${destFile.path}' escapes the cache dir",
            )
            return@mapNotNull null
          }

          val trackCached = track.cached
          val destFileExists = destFile.exists()

          // No need to make a new request, the file is already downloaded
          if (trackCached && destFileExists) {
            return@mapNotNull null
          }

          // A file that exists but is not marked cached is a **partial**, and it is now kept
          // rather than deleted. Fetch2's request could not express "continue this file", so the
          // old code deleted the partial and started over; `KtorDownloader` sends
          // `Range: bytes=<len>-` instead, which is the difference between re-fetching a 293 MB
          // book over a flaky connection and asking for the tail of it.
          if (!trackCached && destFileExists) {
            Timber.i("Resuming partial download for track ${track.id} at ${destFile.length()} bytes")
          }

          return@mapNotNull makeTrackDownloadRequest(
            track,
            bookId,
            bookTitle,
            destFile.absolutePath,
          )
        }
      Timber.i("Made download requests: ${requests.map { it.destinationPath }}")
      return requests
    }

    /** Create a [DownloadRequest] for a track download with the proper metadata */
    private fun makeTrackDownloadRequest(
      track: MediaItemTrack,
      bookId: String,
      bookTitle: String,
      dest: String,
    ) = DownloadRequest(
      trackId = track.id.toString(),
      bookId = bookId,
      bookTitle = bookTitle,
      url = plexConfig.makeDownloadUrl(track.media),
      destinationPath = dest,
    )

    override suspend fun uncacheAllInLibrary(): Int {
      Timber.i("Removing books from library")
      val cachedTrackNamesForLibrary =
        trackRepository.getCachedTracks()
          .map { it.getCachedFileName() }
      val allCachedTrackFiles =
        externalFileDirs.flatMap { dir ->
          dir.listFiles(
            FileFilter {
              MediaItemTrack.cachedFilePattern.matches(it.name)
            },
          )?.toList() ?: emptyList()
        }
      allCachedTrackFiles.forEach {
        Timber.i("Cached for library: $cachedTrackNamesForLibrary")
        if (cachedTrackNamesForLibrary.contains(it.name)) {
          Timber.i("Deleting file: ${it.name}")
          it.delete()
        } else {
          Timber.i("Not deleting file: ${it.name}")
        }
      }
      trackRepository.uncacheAll()
      bookRepository.uncacheAll()
      return allCachedTrackFiles.size
    }

    /**
     * Deletes cached tracks from the filesystem corresponding to [tracks]. Assume all tracks have
     * the correct [MediaItemTrack.parentKey] set
     *
     * Return [Result.success] on successful deletion of all files or [Result.failure] if the
     * deletion of any files fail
     */
    override suspend fun deleteCachedBook(bookId: String) {
      Timber.i("Deleting downloaded book: $bookId")
      downloader.deleteBook(bookId)
      downloadIntents.remove(
        trackRepository.getTracksForAudiobookAsync(bookId).map { it.id },
      )
      externalScope.launch {
        withContext(dispatchers.io) {
          val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
          tracks.forEach {
            val trackFile = File(prefsRepo.cachedMediaDir, it.getCachedFileName())
            trackFile.delete()
            // now count it as deleted
            trackRepository.updateCachedStatus(it.id, false)
          }
          bookRepository.updateCachedStatus(bookId, false)
        }
      }
    }

    /** Set of [Audiobook.id] representing all books being actively downloaded */
    private var activeDownloads =
      object : SimpleSet<String> {
        private val internalSet = mutableSetOf<String>()
        override val size: Int
          get() = internalSet.size

        // Publishes a copy *after* mutating. Both of these used to publish the mutable set
        // itself before the change landed, so a collector saw the previous contents. The copy is
        // still required: a `StateFlow` conflates by `equals`, so handing it the same mutable
        // instance twice looks like no change at all and leaves the download indicator stale.
        override fun add(elem: String): Boolean {
          val changed = internalSet.add(elem)
          _activeBookDownloads.value = internalSet.toSet()
          return changed
        }

        override fun remove(elem: String): Boolean {
          val changed = internalSet.remove(elem)
          _activeBookDownloads.value = internalSet.toSet()
          return changed
        }

        override fun toString() = internalSet.toString()

        override operator fun contains(elem: String) = internalSet.contains(elem)
      }

    private val _activeBookDownloads = MutableStateFlow<Set<String>>(emptySet())
    override val activeBookDownloads: StateFlow<Set<String>>
      get() = _activeBookDownloads

    init {
      applicationContext.registerReceiver(
        downloadListener,
        IntentFilter().apply {
          addAction(DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD)
          addAction(DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS)
        },
        Context.RECEIVER_NOT_EXPORTED,
      )

      // Singleton, so downloads are observed for the life of the process.
      //
      // Fetch2 reported per-*group* start/finish, with an Int group id hashed from the book id
      // that could not be inverted — so the real id had to be read back out of each download's
      // extras. `DownloadEvent` carries the book id directly, which is why none of that
      // bookkeeping survives the port.
      //
      // The completion rule is unchanged and is the part worth being careful about: a book counts
      // as cached only when **every** track it wanted is on disk. Reporting per track would mark
      // a book downloaded after its first file, which is the shape of three separate bugs where
      // downloads went missing while the app claimed success.
      externalScope.launch {
        downloader.events.collect { event ->
          when (event) {
            is DownloadEvent.Progress -> {
              if (event.bookId !in activeDownloads) {
                Timber.i("Starting downloading book with id: ${event.bookId}")
                activeDownloads.add(event.bookId)
                DownloadNotificationWorker.start(applicationContext, workManager)
              }
            }

            is DownloadEvent.Completed -> {
              downloadIntents.remove(listOf(event.trackId))
              withContext(dispatchers.io) {
                trackRepository.updateCachedStatus(event.trackId, true)
                onBookTracksSettled(event.bookId)
              }
            }

            is DownloadEvent.Failed -> {
              // The intent deliberately stays: a failed download is the resume candidate the
              // prune rule protects, and dropping it here would let the next cache scan delete
              // bytes a `Range` request could have continued.
              Timber.w("Download failed for ${event.trackId}: ${event.cause}")
              activeDownloads.remove(event.bookId)
            }

            is DownloadEvent.Cancelled -> {
              activeDownloads.remove(event.bookId)
            }
          }
        }
      }
    }

    /**
     * Marks [bookId] cached once every one of its tracks is.
     *
     * The **only** owner of this write. `DownloadNotificationWorker` used to perform it too, from
     * a scope tied to its own cancellation, so the fact had two owners and one usually lost the
     * race — which is how a downloaded book could report itself uncached until the next cache scan
     * repaired it. This site is the right owner: a `@Singleton` on an injected scope outliving any
     * single unit of work, and already the reconciliation authority for cache state.
     */
    private suspend fun onBookTracksSettled(bookId: String) {
      val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
      if (tracks.isEmpty()) return
      if (tracks.all { it.cached }) {
        Timber.i("Book download success for $bookId")
        bookRepository.updateCachedStatus(bookId, true)
        activeDownloads.remove(bookId)
      }
    }

    /**
     * Update [trackRepository] and [bookRepository] to reflect downloaded files
     *
     * Deletes files for [Audiobook]s no longer in the database and updates [Audiobook.isCached]
     * for downloaded files which no longer exist on the file system
     */
    override suspend fun refreshTrackDownloadedStatus() {
      val idToFileMap = HashMap<String, okio.Path>()

      // "Cannot read the directory" is not "the directory is empty". This used to be
      // `listFiles(...) ?: emptyList()`, so an unmounted SD card or a moved sync directory made
      // every track look absent and un-cached a whole library while the files were still there.
      // A scan that cannot see the directory must change nothing at all.
      val filesOnDisk =
        when (
          val outcome =
            scanCachedMediaDir(prefsRepo.cachedMediaDir.toOkioPath()) { path ->
              MediaItemTrack.cachedFilePattern.matches(path.name)
            }
        ) {
          is CacheScanOutcome.Unavailable -> {
            Timber.w("Skipping cached-file refresh: ${outcome.reason}")
            return
          }
          is CacheScanOutcome.Scanned -> outcome.files
        }

      // A file's presence is not proof it finished downloading. This scan used to mark any
      // matching file as cached, so a Wi-Fi drop mid-download left a partial file that the
      // next launch promoted to "available offline" — and the book played truncated. The
      // expected size has always been in the database; it was simply never read.
      // The incomplete ones are remembered rather than merely skipped: a partial whose
      // download was abandoned is invisible — the UI correctly says the book is not downloaded —
      // so nothing ever pointed at the space it occupies.
      val incompleteOnDisk = mutableListOf<String>()
      val trackIdsFoundOnDisk =
        filesOnDisk.mapNotNull { file ->
          val id = MediaItemTrack.getTrackIdFromFileName(file.name)
          val expectedSize = trackRepository.getTrackAsync(id)?.size ?: 0L
          if (!isCompleteDownload(file, expectedSize)) {
            val actual = okio.FileSystem.SYSTEM.metadataOrNull(file)?.size ?: 0L
            Timber.i(
              "Ignoring incomplete download for track $id: $actual of $expectedSize bytes",
            )
            incompleteOnDisk.add(id)
            idToFileMap[id] = file
            return@mapNotNull null
          }
          idToFileMap[id] = file
          id
        }

      val reportedCachedKeys = trackRepository.getCachedTracks().map { it.id }

      // The set arithmetic lives in `reconcileCachedTracks` so it can be tested without a
      // downloader, a Context or the Injector — see CacheReconciliation.
      val reconciliation =
        reconcileCachedTracks(onDisk = trackIdsFoundOnDisk, reportedCached = reportedCachedKeys)

      // Delete partials nobody is coming back for. After the reconciliation, so the
      // database's view is the settled one; `partialsSafeToPrune` decides, and it keeps anything
      // the intent store could still resume or the database still claims.
      pruneAbandonedPartials(incompleteOnDisk, reportedCachedKeys, idToFileMap)

      val alteredTracks = mutableListOf<String>()

      // Exists in DB but not in cache- remove from DB!
      reconciliation.toMarkUncached.forEach {
        Timber.i("Removed track: $it")
        alteredTracks.add(it)
        trackRepository.updateCachedStatus(it, false)
      }

      // Exists in cache but not in DB- add to DB!
      reconciliation.toMarkCached.forEach {
        val rowsUpdated = trackRepository.updateCachedStatus(it, true)
        if (rowsUpdated == 0) {
          // A complete file whose track has no row is left alone — deliberately, and this is a
          // known gap to resolve rather than remove. Downloads are retained across libraries, so
          // "no row here" does not mean "nobody wants this"; deleting it would take a good
          // download to fix a bookkeeping gap. Only *incomplete* files are ever deleted, below,
          // and only when nothing is coming back for them.
          Timber.i("Complete file for unknown track $it — keeping it")
        } else {
          alteredTracks.add(it)
        }
      }

      // Update cached status for the books containing any added/removed tracks
      alteredTracks.map {
        trackRepository.getBookIdForTrack(it)
      }.distinct().forEach { bookId: String ->
        Timber.i("Book: $bookId")
        if (bookId == NO_AUDIOBOOK_FOUND_ID) {
          return@forEach
        }
        val bookTrackCacheCount =
          trackRepository.getCachedTrackCountForBookAsync(bookId)
        val bookTrackCount = trackRepository.getTrackCountForBookAsync(bookId)
        val isBookCached = isBookFullyCached(bookTrackCacheCount, bookTrackCount)
        val book = bookRepository.getAudiobookAsync(bookId)
        if (book != null) {
          // The chapter-level `downloaded` stamp went with the legacy column; it was
          // never read. The book's own flag is the one the UI and cache reconciliation use.
          bookRepository.update(book.copy(isCached = isBookCached))
        }
      }
    }

    /**
     * Migrates cached files from being named after the [MediaItemTrack.id] to being named after
     * the persistent part in [MediaItemTrack.media]
     */
    private fun migrateCachedFiles() {
    }
  }
