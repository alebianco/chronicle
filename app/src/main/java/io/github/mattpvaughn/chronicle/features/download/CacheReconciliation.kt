package io.github.mattpvaughn.chronicle.features.download

/**
 * Decides what a cached-file scan means, given what is on disk and what the database believes.
 *
 * Extracted from `CachedFileManager.refreshTrackDownloadedStatus`, which is the method behind the
 * owner's *"book reports no cache even if I'm sure I have downloaded it"*. The decision itself is
 * plain set arithmetic, but it was welded to `Fetch`, a `BroadcastReceiver`, a `Context` and an
 * `Injector.get()` field initialiser — so none of it could be tested without a device, and none of
 * it was. The I/O stays in the manager; the judgement lives here.
 *
 * Two rules this encodes, both of them bugs that reached the owner:
 *
 * - A scan that could not read its directory must produce **no** changes at all, rather than
 *   reporting everything absent (cu-85). That is the caller's job — it must not call this at all
 *   for an unavailable scan — and [reconcileCachedTracks] is written so an empty disk list is a
 *   genuine "nothing on disk", not a stand-in for "could not look".
 * - A file's presence is not proof it finished downloading (cu-76); the caller filters incomplete
 *   files out before calling, so anything reaching [onDisk] is a verified complete download.
 */
data class CacheReconciliation(
  /** Tracks the database calls cached that are no longer on disk. */
  val toMarkUncached: List<String>,
  /** Tracks present on disk that the database does not yet call cached. */
  val toMarkCached: List<String>,
) {
  /** Every track whose cached status changes, and therefore whose book needs re-evaluating. */
  val alteredTrackIds: List<String> get() = toMarkUncached + toMarkCached

  val hasChanges: Boolean get() = alteredTrackIds.isNotEmpty()
}

/**
 * @param onDisk ids of tracks with a verified-complete file present.
 * @param reportedCached ids the database currently believes are cached.
 */
fun reconcileCachedTracks(
  onDisk: Collection<String>,
  reportedCached: Collection<String>,
): CacheReconciliation {
  // Sets, not lists: the original used List.contains inside a filter over the other list, which is
  // quadratic — and a large library runs this on every launch.
  val onDiskSet = onDisk.toSet()
  val reportedSet = reportedCached.toSet()
  return CacheReconciliation(
    toMarkUncached = reportedSet.filterNot { it in onDiskSet },
    toMarkCached = onDiskSet.filterNot { it in reportedSet },
  )
}

/**
 * Which partial files are safe to delete (cu-81).
 *
 * cu-76 stopped promoting a short file to "available offline", and deliberately **left the bytes
 * on disk** because Fetch2 resumes over HTTP Range. Nothing ever removed the ones whose download
 * was abandoned for good, so a cancelled or exhausted download leaks its bytes into
 * `cachedMediaDir` invisibly — the UI correctly reports the book as not downloaded, so nothing
 * points at the space. It costs disk, not correctness, and it shows up as "the app is using 30GB
 * and I have four books" on a device whose cache is a small SD card.
 *
 * **The safety rule, and it is the whole design.** A file is a candidate only when *all three* are
 * true, and each rules out a different way of destroying something valuable:
 *
 * - Fetch2 has **no record** of it. A `PAUSED` or `FAILED` download is a resume candidate
 *   (cu-76's `ResumePlan`), and deleting its bytes turns a cheap range request into a full
 *   re-download.
 * - The database does **not** call it cached. A complete file belongs to a book the user
 *   downloaded on purpose.
 * - The file is **incomplete**, i.e. shorter than the track says it should be. A full-length file
 *   with no Fetch2 record is a finished download whose row is merely stale — the reconciliation
 *   above adopts it, and deleting it would destroy a good download to fix a bookkeeping error.
 *
 * Anything failing a check is kept. The asymmetry is deliberate: keeping a stale partial costs
 * disk, deleting a live one costs the user their download.
 */
fun partialsSafeToPrune(
  incompleteOnDisk: Collection<String>,
  knownToFetch: Collection<String>,
  reportedCached: Collection<String>,
): List<String> {
  val fetchKnows = knownToFetch.toSet()
  val cached = reportedCached.toSet()
  return incompleteOnDisk.filterNot { it in fetchKnows || it in cached }
}

/**
 * Deletes the files [partialsSafeToPrune] chose, and reports what was reclaimed (cu-81).
 *
 * Separated from the manager so the part that actually touches the filesystem can be exercised
 * against real temp files — the decision above is set arithmetic, but "did it delete the right
 * file, and only that one" is the half worth checking with a disk.
 *
 * A file that will not delete is logged by the caller and left; it is offered again on the next
 * scan, which is a better outcome than treating a transient failure as an error the user must act
 * on.
 */
fun prunePartialFiles(
  prunable: Collection<String>,
  idToFile: Map<String, java.io.File>,
): PruneOutcome {
  var deleted = 0
  var reclaimedBytes = 0L
  val failed = mutableListOf<String>()
  prunable.forEach { id ->
    val file = idToFile[id] ?: return@forEach
    val size = file.length()
    if (file.delete()) {
      deleted++
      reclaimedBytes += size
    } else {
      failed += id
    }
  }
  return PruneOutcome(deleted = deleted, reclaimedBytes = reclaimedBytes, failedIds = failed)
}

/** What a prune actually managed to remove. */
data class PruneOutcome(
  val deleted: Int,
  val reclaimedBytes: Long,
  val failedIds: List<String> = emptyList(),
)

/**
 * Whether a book counts as downloaded.
 *
 * A book is cached only when **every** one of its tracks is. The `> 0` guard is load-bearing: a
 * book whose tracks have not loaded yet reports 0 of 0, and without it `0 == 0` would mark an
 * empty book as fully downloaded.
 */
fun isBookFullyCached(
  cachedTrackCount: Int,
  totalTrackCount: Int,
): Boolean = totalTrackCount > 0 && cachedTrackCount == totalTrackCount
