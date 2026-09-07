package io.github.mattpvaughn.chronicle.features.download

/**
 * Maps a backend-neutral book id to the `Int` group id Fetch2 requires.
 *
 * Fetch2's grouping API is `int` throughout while book ids are `String`, so a non-numeric backend
 * can be represented (decision-11).
 *
 * `String.hashCode` because `cancelGroup` may run in a later process than the one that enqueued the
 * download: the mapping must survive a restart, and that hash is specified by the language rather
 * than the JVM. Numeric ids map to themselves so existing Plex downloads keep their group.
 *
 * Two ids hashing alike would share a group, so cancelling one cancels the other. Negligible at
 * household scale, and the fix — persisting an id↔group table — is a lot of machinery for a
 * subsystem that may be replaced.
 */
fun downloadGroupId(bookId: String): Int {
  val numeric = bookId.toIntOrNull()
  if (numeric != null && numeric >= 0) {
    return numeric
  }
  // Int.MIN_VALUE has no positive counterpart, and abs() returns it unchanged and negative.
  val hashed = bookId.hashCode()
  return if (hashed == Int.MIN_VALUE) 0 else kotlin.math.abs(hashed)
}

/**
 * A stable, unique PendingIntent request code for [bookId] under [prefix].
 *
 * Android matches a PendingIntent by request code plus intent, so the code must be identical across
 * process restarts — or a notification posted before a relaunch stops matching its own action — and
 * distinct per book. Two actions computed it as `prefix + bookId`, which a `String` id cannot do.
 *
 * Reuses [downloadGroupId] so collision behaviour is defined in one place.
 */
fun requestCodeFor(
  prefix: Int,
  bookId: String,
): Int = prefix + downloadGroupId(bookId)

/**
 * Key under which a download request carries its book id in Fetch2's `Extras`.
 *
 * [downloadGroupId] is one-way, but Fetch2's listeners hand back only the `Int` group and the app
 * needs the real id to update the database — so it travels with the request.
 */
const val EXTRA_BOOK_ID = "chronicle.bookId"

/** The book id a download was enqueued for, or null if the request predates [EXTRA_BOOK_ID]. */
fun com.tonyodev.fetch2.Download.bookIdOrNull(): String? = extras.getString(EXTRA_BOOK_ID, "").ifEmpty { null }

/**
 * Groups downloads by book, dropping any enqueued before [EXTRA_BOOK_ID] was added.
 *
 * Not `groupBy { it.group }`: that group is a hash, so it cannot be turned back into a book id.
 * Dropped rather than guessed, because a wrong guess marks the wrong book downloaded, while a
 * dropped one is picked up by the next `CachedFileManager.refreshCachedFileStatus`.
 */
fun List<com.tonyodev.fetch2.Download>.groupByBookId(): Map<String, List<com.tonyodev.fetch2.Download>> =
  mapNotNull { download -> download.bookIdOrNull()?.let { it to download } }
    .groupBy({ it.first }, { it.second })
