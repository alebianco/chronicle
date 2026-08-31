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
