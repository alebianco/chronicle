package io.github.mattpvaughn.chronicle.features.download

import kotlinx.coroutines.flow.Flow

/**
 * The download engine, behind a seam.
 *
 * Introduced by decision-24 so replacing the engine is not a 15-file rewrite, and so a future
 * engine swap touches one implementation instead of every caller. It mirrors what the ingestion
 * seam did for sources.
 *
 * **Deliberately framework-free.** No `android.*`, no `java.io.File`, no engine types — a request
 * names a destination *path*, not a `File`, and progress arrives as a data class rather than an
 * engine's status enum. That is what puts the decision logic on the portable side of the seam, per
 * decision-24: the interface could be implemented on another platform without change, even though
 * this app's implementation is Android-bound and will stay that way.
 *
 * ### The contract the on-disk layout imposes
 *
 * Downloads are plain files at `<cachedMediaDir>/<trackId>.<ext>`, and **partials are named
 * exactly like finished files** — no `.part` or `.tmp` suffix. That is not laziness: the previous
 * engine downloaded in place, `MoveSyncLocationWorker` selects files with
 * `MediaItemTrack.cachedFilePattern`, and giving partials a distinguishing suffix starts *orphaning*
 * them, because the prune only ever scans the active `cachedMediaDir`. `SyncLocationMoveTest`
 * pins this.
 *
 * An implementation must therefore:
 *
 * - write directly to [DownloadRequest.destinationPath], never to a temporary name;
 * - **resume** an existing partial with an HTTP `Range` request rather than restarting it, because
 *   restarting a 293 MB book on a flaky connection never finishes;
 * - leave an already-complete file alone. The household has real downloaded audio and four prior
 *   tasks have failure modes ending in *deleted audio*; this is the highest-risk area in the app.
 */
interface Downloader {
  /**
   * Starts downloading [requests], or resumes them if partials already exist.
   *
   * Returns immediately; progress arrives on [events].
   */
  suspend fun enqueue(requests: List<DownloadRequest>)

  /** Everything the engine has to say about in-flight and finished work. */
  val events: Flow<DownloadEvent>

  /** Stops and forgets every download for [bookId], leaving any bytes already on disk. */
  suspend fun cancelBook(bookId: String)

  /** Stops and forgets every download, everywhere. */
  suspend fun cancelAll()

  /**
   * Stops downloads for [bookId] **and deletes their files**.
   *
   * Separate from [cancelBook] because the difference is destructive: cancelling a download the
   * user changed their mind about must not remove audio they already have.
   */
  suspend fun deleteBook(bookId: String)

  /** Restarts anything that failed, and resumes anything paused. Called on regaining network. */
  suspend fun retryFailedAndResume()

  /** The track ids the engine currently knows about, whatever their state. */
  suspend fun knownTrackIds(): List<String>
}

/**
 * One track to fetch.
 *
 * @param trackId the track's id, which is also the basename of [destinationPath]. Carried
 *   explicitly rather than parsed back out of the path, because the previous engine's group API was
 *   `Int`-only and forced the book id through a **hash** — a hash that could not be reversed, so
 *   the real id had to travel alongside it in an extras map. There is no such constraint here, and
 *   this is what removes the need for that workaround.
 * @param bookId the owning book's real id. Used for grouping, cancellation and the notification.
 * @param bookTitle for the notification only. Never used as an identifier.
 * @param url fully resolved, including the `download=1` query. Auth arrives from the client's
 *   headers plugin rather than being baked in here, so a token is never held in a request object
 *   that might be logged.
 * @param destinationPath absolute path, `<cachedMediaDir>/<trackId>.<ext>`. See the interface KDoc:
 *   partials use this exact name.
 */
data class DownloadRequest(
  val trackId: String,
  val bookId: String,
  val bookTitle: String,
  val url: String,
  val destinationPath: String,
)

/** Something that happened to a download. */
sealed interface DownloadEvent {
  val trackId: String
  val bookId: String

  /**
   * Bytes arrived.
   *
   * [totalBytes] is null when the server did not say how large the file is, which is a real case
   * for a `Content-Range`-less response and must render as indeterminate rather than as 0%.
   */
  data class Progress(
    override val trackId: String,
    override val bookId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
  ) : DownloadEvent

  /** The file is fully on disk at its final path. */
  data class Completed(
    override val trackId: String,
    override val bookId: String,
  ) : DownloadEvent

  /**
   * The download stopped and will not continue on its own.
   *
   * [cause] is a diagnosis for a log, never user-facing text — the same split the playback-error
   * path uses. Any bytes already written stay on disk so a later attempt can resume them.
   */
  data class Failed(
    override val trackId: String,
    override val bookId: String,
    val cause: String,
  ) : DownloadEvent

  /** The user cancelled it. Not an error, and deliberately not worth a notification. */
  data class Cancelled(
    override val trackId: String,
    override val bookId: String,
  ) : DownloadEvent
}
