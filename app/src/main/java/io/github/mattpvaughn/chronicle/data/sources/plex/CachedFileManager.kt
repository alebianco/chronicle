package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2core.DownloadBlock
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.model.isCompleteDownload
import io.github.mattpvaughn.chronicle.features.download.DownloadNotificationWorker
import io.github.mattpvaughn.chronicle.features.download.FetchGroupStartFinishListener
import io.github.mattpvaughn.chronicle.features.download.ResumePlan
import io.github.mattpvaughn.chronicle.features.download.bookIdOrNull
import io.github.mattpvaughn.chronicle.features.download.downloadGroupId
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import javax.inject.Inject

interface ICachedFileManager {
  enum class CacheStatus { CACHED, CACHING, NOT_CACHED }

  val activeBookDownloads: LiveData<Set<String>>

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
   * downloaded with no way back except re-requesting it by hand (cu-76).
   *
   * Safe to call repeatedly — Fetch2 ignores downloads that are already running or complete.
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
    private val fetch: Fetch,
    private val prefsRepo: PrefsRepo,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val plexConfig: PlexConfig,
    private val applicationContext: Context,
    private val dispatchers: DispatcherProvider,
    private val externalScope: CoroutineScope,
  ) : ICachedFileManager {
    private val externalFileDirs = Injector.get().externalDeviceDirs()

    private val downloadListener =
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context?,
          intent: Intent?,
        ) {
          when (intent?.action) {
            DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS ->
              Injector.get().fetch()
                .cancelAll()
            DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD -> {
              val bookId = intent.getStringExtra(DownloadNotificationWorker.KEY_BOOK_ID)
              if (!bookId.isNullOrEmpty()) {
                Timber.i("Cancelling book: $bookId")
                Injector.get().fetch().cancelGroup(downloadGroupId(bookId))
              }
            }
          }
        }
      }

    override fun resumeInterruptedDownloads() {
      // resumeAll covers PAUSED downloads; FAILED ones need an explicit retry, and a
      // download abandoned by the old single-retry limit is FAILED rather than paused.
      // Which ids qualify is ResumePlan's call, so it can be tested without this class.
      fetch.resumeAll()
      fetch.getDownloads { all ->
        val toRetry = ResumePlan.idsToRetry(all)
        if (toRetry.isEmpty()) {
          return@getDownloads
        }
        Timber.i("Retrying ${toRetry.size} interrupted download(s)")
        fetch.retry(toRetry)
      }
    }

    override fun cancelGroup(id: String) {
      fetch.cancelGroup(downloadGroupId(id))
    }

    override fun cancelCaching() {
      fetch.cancelAll()
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
      // Add downloads to Fetch
      externalScope.launch {
        fetch.enqueue(makeRequests(bookId, bookTitle)) {
          val errors =
            it.mapNotNull { (_, error) ->
              if (error == Error.NONE) null else error
            }
          if (BuildConfig.DEBUG && errors.isNotEmpty()) {
            Toast.makeText(
              applicationContext,
              "Error enqueuing download: $errors",
              LENGTH_SHORT,
            ).show()
          }
          if (errors.isEmpty()) {
            DownloadNotificationWorker.start()
          }
        }
      }
    }

    /**
     * Creates [Request]s for all missing files associated with [bookId]
     *
     * @return the number of files to be downloaded
     */
    private suspend fun makeRequests(
      bookId: String,
      bookTitle: String,
    ): List<Request> {
      // Gets all tracks for album id
      val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

      val cachedFilesDir = prefsRepo.cachedMediaDir
      Timber.i("Caching tracks to: ${cachedFilesDir.path}")
      Timber.i("Tracks to cache: $tracks")

      val requests =
        tracks.mapNotNull { track ->
          // File exists but is not marked as cached in the database- more likely than not
          // this means that it has failed to fully download
          val destFile = File(cachedFilesDir, track.getCachedFileName())
          val trackCached = track.cached
          val destFileExists = destFile.exists()

          // No need to make a new request, the file is already downloaded
          if (trackCached && destFileExists) {
            return@mapNotNull null
          }

          // File exists but is not marked as cached in the database- probably means a download
          // has failed. Delete it and try again
          if (!trackCached && destFileExists) {
            val deleted = destFile.delete()
            if (!deleted) {
              Timber.e("Failed to delete previously cached file. Download will fail!")
            } else {
              Timber.e("Succeeding in deleting cached file")
            }
          }

          return@mapNotNull makeTrackDownloadRequest(
            track,
            bookId,
            bookTitle,
            "file://${destFile.absolutePath}",
          )
        }
      Timber.i("Made download requests: ${requests.map { it.file }}")
      return requests
    }

    /** Create a [Request] for a track download with the proper metadata */
    private fun makeTrackDownloadRequest(
      track: MediaItemTrack,
      bookId: String,
      bookTitle: String,
      dest: String,
    ) = plexConfig.makeDownloadRequest(track.media, bookId, bookTitle, dest)

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
      fetch.deleteGroup(downloadGroupId(bookId))
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

        // Posts a copy *after* mutating. Both of these used to post the mutable set itself
        // before the change landed, so an observer saw the previous contents — and because
        // LiveData compares by reference, posting the same instance twice can be coalesced
        // away entirely, leaving the download indicator stale.
        override fun add(elem: String): Boolean {
          val changed = internalSet.add(elem)
          _activeBookDownloads.postValue(internalSet.toSet())
          return changed
        }

        override fun remove(elem: String): Boolean {
          val changed = internalSet.remove(elem)
          _activeBookDownloads.postValue(internalSet.toSet())
          return changed
        }

        override fun toString() = internalSet.toString()

        override operator fun contains(elem: String) = internalSet.contains(elem)
      }

    private val _activeBookDownloads = MutableLiveData<Set<String>>()
    override val activeBookDownloads: LiveData<Set<String>>
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

      // singleton so we can observe downloads always
      fetch.addListener(
        object : FetchGroupStartFinishListener() {
          override fun onStarted(
            groupId: Int,
            fetchGroup: FetchGroup,
          ) {
            // The listener only receives Fetch2's Int group, which downloadGroupId hashed from
            // the book id and cannot invert — so read the id back off any download in the group.
            val bookId = fetchGroup.downloads.firstNotNullOfOrNull { it.bookIdOrNull() }
            if (bookId == null) {
              Timber.i("Download group $groupId started with no book id in its extras")
              return
            }
            if (bookId !in activeDownloads) {
              Timber.i("Starting downloading book with id: $bookId")
            }
            activeDownloads.add(bookId)
          }

          override fun onStarted(
            download: Download,
            downloadBlocks: List<DownloadBlock>,
            totalBlocks: Int,
          ) {
            Timber.i("Starting download!")
            DownloadNotificationWorker.start()
            super.onResumed(download)
          }

          override fun onFinished(
            groupId: Int,
            fetchGroup: FetchGroup,
          ) {
            // Handle the various downloaded statuses
            Timber.i(
              "Group change for book with id $groupId: ${fetchGroup.downloads.size} tracks downloaded",
            )
            val downloads = fetchGroup.downloads
            Timber.i(downloads.joinToString { it.status.toString() })
            downloads.firstNotNullOfOrNull { it.bookIdOrNull() }
              ?.let { activeDownloads.remove(it) }
            val downloadSuccess =
              downloads.all { it.error == Error.NONE } && downloads.isNotEmpty()
            // Fetch2 reports an Int groupId, which is a hash of the book id and cannot be
            // reversed — so the id is read back from the extras it was enqueued with (cu-71).
            // A download from an older version has none; skipping is right, because guessing
            // would mark the wrong book as downloaded.
            val bookId = downloads.firstNotNullOfOrNull { it.bookIdOrNull() }
            if (downloadSuccess && bookId != null) {
              externalScope.launch {
                withContext(dispatchers.io) {
                  Timber.i("Book download success for $bookId (group $groupId)")
                  bookRepository.updateCachedStatus(bookId, true)
                }
              }
            } else if (downloadSuccess) {
              Timber.w("Download group $groupId finished with no book id in its extras")
            }
          }
        },
      )
    }

    /**
     * Update [trackRepository] and [bookRepository] to reflect downloaded files
     *
     * Deletes files for [Audiobook]s no longer in the database and updates [Audiobook.isCached]
     * for downloaded files which no longer exist on the file system
     */
    override suspend fun refreshTrackDownloadedStatus() {
      val idToFileMap = HashMap<String, File>()
      val filesOnDisk =
        prefsRepo.cachedMediaDir.listFiles(
          FileFilter {
            MediaItemTrack.cachedFilePattern.matches(it.name)
          },
        )?.toList() ?: emptyList()

      // A file's presence is not proof it finished downloading. This scan used to mark any
      // matching file as cached, so a Wi-Fi drop mid-download left a partial file that the
      // next launch promoted to "available offline" — and the book played truncated. The
      // expected size has always been in the database; it was simply never read (cu-76).
      val trackIdsFoundOnDisk =
        filesOnDisk.mapNotNull { file ->
          val id = MediaItemTrack.getTrackIdFromFileName(file.name)
          val expectedSize = trackRepository.getTrackAsync(id)?.size ?: 0L
          if (!isCompleteDownload(file, expectedSize)) {
            Timber.i(
              "Ignoring incomplete download for track $id: ${file.length()} of $expectedSize bytes",
            )
            return@mapNotNull null
          }
          idToFileMap[id] = file
          id
        }

      val reportedCachedKeys = trackRepository.getCachedTracks().map { it.id }

      val alteredTracks = mutableListOf<String>()

      // Exists in DB but not in cache- remove from DB!
      reportedCachedKeys.filter {
        !trackIdsFoundOnDisk.contains(it)
      }.forEach {
        Timber.i("Removed track: $it")
        alteredTracks.add(it)
        trackRepository.updateCachedStatus(it, false)
      }

      // Exists in cache but not in DB- add to DB!
      trackIdsFoundOnDisk.filter {
        !reportedCachedKeys.contains(it)
      }.forEach {
        val rowsUpdated = trackRepository.updateCachedStatus(it, true)
        if (rowsUpdated == 0) {
          // TODO: this will be relevant when multiple sources is implemented, but for now
          //       we just have to trust as we allow users to retain downloads across libraries
//                // File has been orphaned- no longer exists in DB, remove it from file system!
//                idToFileMap[it]?.delete()
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
        val isBookCached = bookTrackCacheCount == bookTrackCount && bookTrackCount > 0
        val book = bookRepository.getAudiobookAsync(bookId)
        if (book != null) {
          bookRepository.update(
            book.copy(
              isCached = isBookCached,
              chapters = book.chapters.map { it.copy(downloaded = isBookCached) },
            ),
          )
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
