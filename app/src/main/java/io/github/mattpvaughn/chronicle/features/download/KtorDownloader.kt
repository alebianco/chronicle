package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * [Downloader] over Ktor, replacing the abandoned Fetch2 (decision-24).
 *
 * ### Resume is the whole point
 *
 * A partial file is **appended to**, never restarted. The local length becomes a
 * `Range: bytes=<len>-` header, and the two acceptable answers are handled differently:
 *
 * - **206 Partial Content** — the server honoured the range. Append from `len`.
 * - **200 OK** — the server ignored the range and is sending the whole file from byte 0. The
 *   partial must then be *truncated*, because appending would splice the file's beginning onto its
 *   own middle and produce a corrupt track that is exactly the right length to look finished.
 *
 * That second case is the one worth having a test for; it is silent corruption otherwise.
 *
 * A `416 Range Not Satisfiable` means the local file is already at or past the server's length, so
 * the download is treated as complete rather than as an error.
 *
 * ### Writing in place
 *
 * Bytes go straight to `<cachedMediaDir>/<trackId>.<ext>`, with no `.part` suffix, because
 * `MoveSyncLocationWorker` selects with `MediaItemTrack.cachedFilePattern` and a suffixed partial
 * would be *orphaned* by a prune that only scans the active directory. See [Downloader]'s KDoc.
 *
 * The consequence is that a partial is indistinguishable from a finished file on disk alone. That
 * is pre-existing and is why cache reconciliation compares against the database rather than
 * trusting the filesystem.
 *
 * ### One job per track
 *
 * Each request gets a coroutine on [scope] tracked in [jobs] by track id, so cancelling a book
 * cancels exactly its tracks. Re-enqueueing a track that is already running is a no-op rather than
 * a second writer to the same file — two coroutines appending to one `RandomAccessFile` is how a
 * download corrupts itself.
 */
@Singleton
class KtorDownloader
  @Inject
  constructor(
    @Named(AppModule.OKHTTP_CLIENT_DOWNLOADER)
    private val client: HttpClient,
    private val dispatchers: DispatcherProvider,
    // The application-wide supervisor scope, already on `dispatchers.io`. A download must outlive
    // the screen that started it, and a `SupervisorJob` means one failed track does not cancel the
    // rest of the book.
    private val scope: CoroutineScope,
  ) : Downloader {
    private val _events =
      MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
      )

    override val events: Flow<DownloadEvent> = _events.asSharedFlow()

    /** Track id to its running job, and the request it is running. */
    private val jobs = mutableMapOf<String, TrackedDownload>()
    private val lock = Mutex()

    private data class TrackedDownload(
      val request: DownloadRequest,
      val job: Job,
    )

    override suspend fun enqueue(requests: List<DownloadRequest>) {
      requests.forEach { request ->
        lock.withLock {
          if (jobs.containsKey(request.trackId)) {
            // Already in flight. Starting a second writer for the same path would interleave two
            // append streams into one file.
            Timber.i("Download for ${request.trackId} already running; ignoring duplicate enqueue")
            return@withLock
          }
          val job =
            scope.launch {
              runCatching { download(request) }
                .onFailure { failure ->
                  if (failure is kotlinx.coroutines.CancellationException) throw failure
                  Timber.e(failure, "Download failed for ${request.trackId}")
                  _events.tryEmit(
                    DownloadEvent.Failed(
                      trackId = request.trackId,
                      bookId = request.bookId,
                      cause = failure.describe(),
                    ),
                  )
                }
              lock.withLock { jobs.remove(request.trackId) }
            }
          jobs[request.trackId] = TrackedDownload(request, job)
        }
      }
    }

    private suspend fun download(request: DownloadRequest) =
      withContext(dispatchers.io) {
        val file = File(request.destinationPath)
        file.parentFile?.mkdirs()
        val alreadyHave = if (file.exists()) file.length() else 0L

        client
          .prepareGet(request.url) {
            if (alreadyHave > 0L) {
              header(HttpHeaders.Range, "bytes=$alreadyHave-")
            }
          }.execute { response ->
            when {
              // The local file is already at or past the server's length. Nothing to fetch.
              response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                Timber.i("${request.trackId} is already complete (416 for range $alreadyHave-)")
                _events.tryEmit(DownloadEvent.Completed(request.trackId, request.bookId))
                return@execute
              }

              !response.status.isSuccessLike() -> {
                error("HTTP ${response.status.value} for ${request.trackId}")
              }
            }

            // 200 to a ranged request means the server ignored the range and is resending from
            // byte 0. Appending in that case splices the head of the file onto its own middle and
            // yields a corrupt track of plausible length — so start over instead.
            val resuming = alreadyHave > 0L && response.status == HttpStatusCode.PartialContent
            val startAt =
              if (resuming) {
                alreadyHave
              } else {
                if (alreadyHave > 0L) {
                  Timber.w("Server ignored Range for ${request.trackId}; restarting from 0")
                }
                0L
              }

            val expectedTotal =
              response.contentLength()?.let { declared -> if (resuming) startAt + declared else declared }

            writeChannel(
              channel = response.bodyAsChannel(),
              file = file,
              startAt = startAt,
              request = request,
              expectedTotal = expectedTotal,
            )
          }
      }

    /**
     * Streams [channel] into [file] starting at [startAt], emitting progress as it goes.
     *
     * `RandomAccessFile` with an explicit seek rather than `FileOutputStream(append = true)`: the
     * append flag cannot express "truncate to 0 and start over", which is exactly what the
     * range-ignored case above needs.
     */
    private suspend fun writeChannel(
      channel: ByteReadChannel,
      file: File,
      startAt: Long,
      request: DownloadRequest,
      expectedTotal: Long?,
    ) {
      var written = startAt
      var lastReported = startAt
      RandomAccessFile(file, "rw").use { out ->
        // Truncates when restarting, and is a no-op when resuming at the current length.
        out.setLength(startAt)
        out.seek(startAt)

        val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
        while (true) {
          val read = channel.readAvailable(buffer)
          if (read <= 0) break
          out.write(buffer, 0, read)
          written += read

          // Throttled by bytes rather than by time: a progress event per 8 KB read would be
          // thousands per second on a LAN, and the per-second cost of download bookkeeping is
          // already a recorded concern in this area.
          if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
            lastReported = written
            _events.tryEmit(
              DownloadEvent.Progress(
                trackId = request.trackId,
                bookId = request.bookId,
                bytesDownloaded = written,
                totalBytes = expectedTotal,
              ),
            )
          }
        }
      }

      Timber.i("Downloaded ${request.trackId}: $written bytes")
      _events.tryEmit(DownloadEvent.Completed(request.trackId, request.bookId))
    }

    override suspend fun cancelBook(bookId: String) {
      val cancelling =
        lock.withLock {
          jobs.filterValues { it.request.bookId == bookId }
        }
      cancelling.forEach { (trackId, tracked) ->
        tracked.job.cancelAndJoin()
        lock.withLock { jobs.remove(trackId) }
        _events.tryEmit(DownloadEvent.Cancelled(trackId, bookId))
      }
    }

    override suspend fun cancelAll() {
      val cancelling = lock.withLock { jobs.toMap() }
      cancelling.forEach { (trackId, tracked) ->
        tracked.job.cancelAndJoin()
        _events.tryEmit(DownloadEvent.Cancelled(trackId, tracked.request.bookId))
      }
      lock.withLock { jobs.clear() }
    }

    override suspend fun deleteBook(bookId: String) {
      // Cancel first: deleting a file a coroutine is still writing to would recreate it.
      val doomed = lock.withLock { jobs.filterValues { it.request.bookId == bookId } }
      cancelBook(bookId)
      withContext(dispatchers.io) {
        doomed.values.forEach { tracked ->
          val file = File(tracked.request.destinationPath)
          if (file.exists() && !file.delete()) {
            Timber.w("Could not delete ${file.absolutePath}")
          }
        }
      }
    }

    override suspend fun retryFailedAndResume() {
      // Nothing to resume from an in-memory map: a job that failed removed itself, and a job still
      // present is still running. Callers re-enqueue from the database, which is the durable
      // record — the previous engine kept its own SQLite queue, and this deliberately does not, so
      // there is exactly one source of truth for what should be on disk.
      Timber.i("retryFailedAndResume: ${jobs.size} download(s) still in flight; callers re-enqueue")
    }

    /**
     * Suspends until every in-flight download has finished, failed or been cancelled.
     *
     * Exists for tests, and says so plainly rather than pretending otherwise — but it is the
     * honest way to wait for this class. Downloads are real I/O, so a virtual-time scheduler has
     * nothing to advance: `runTest` + `advanceUntilIdle` drives none of it and reports zero
     * requests, which looks like a broken downloader. Joining the actual jobs is the only thing
     * that can mean "the work is done".
     *
     * Also usable by a caller that genuinely needs to block on a batch, which is why it is not
     * `@VisibleForTesting`.
     */
    suspend fun awaitIdle() {
      while (true) {
        val running = lock.withLock { jobs.values.map { it.job } }
        if (running.isEmpty()) return
        running.forEach { it.join() }
      }
    }

    override suspend fun knownTrackIds(): List<String> = lock.withLock { jobs.keys.toList() }

    private companion object {
      const val DEFAULT_BUFFER_BYTES = 64 * 1024
      const val PROGRESS_INTERVAL_BYTES = 512L * 1024L
    }
  }

/** 2xx, treating a 206 as the success it is. */
private fun HttpStatusCode.isSuccessLike(): Boolean = value in 200..299

/** A cause chain worth logging, not user-facing text. */
private fun Throwable.describe(): String =
  generateSequence(this) { it.cause }
    .take(4)
    .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
