package io.github.mattpvaughn.chronicle.features.download

/**
 * Maps a backend-neutral book id to the `Int` group id Fetch2 requires.
 *
 * Fetch2's grouping API is `int` throughout — `Request.groupId`, `cancelGroup(int)`,
 * `getDownloadsInGroup(int)` — while book ids are `String` so a non-numeric backend can be
 * represented (Audiobookshelf UUIDs, local file paths; decision-11). This is the bridge.
 *
 * Two properties matter:
 *
 * - **Deterministic.** `cancelGroup` may be called in a later process than the one that enqueued
 *   the download, so the mapping cannot involve any per-session state. `String.hashCode` is
 *   specified by the Java language, not JVM-dependent, so it is stable across restarts and
 *   devices — unlike `Object.hashCode`, which is not.
 * - **Numeric ids map to themselves.** Plex ids are numeric, so existing downloads keep the group
 *   id they were enqueued with and are not orphaned by the retype.
 *
 * Collisions are possible in principle: two ids hashing to the same value would share a download
 * group, so cancelling one would cancel the other. With a household-sized library the odds are
 * negligible, and the alternative — persisting an id↔group table — is a lot of machinery for a
 * subsystem cu-76 may replace. Worth revisiting if a real collision is ever observed.
 */
fun downloadGroupId(bookId: String): Int {
  val numeric = bookId.toIntOrNull()
  if (numeric != null && numeric >= 0) {
    return numeric
  }
  // absoluteValue rather than a mask: Int.MIN_VALUE has no positive counterpart, so take it to 0
  // instead of letting abs() return it unchanged and negative.
  val hashed = bookId.hashCode()
  return if (hashed == Int.MIN_VALUE) 0 else kotlin.math.abs(hashed)
}

/**
 * A stable, unique PendingIntent request code for [bookId] under [prefix].
 *
 * Two notification actions used to compute this as `prefix + bookId`, which a `String` id cannot
 * do. Android matches a PendingIntent by request code plus intent, so the code must be identical
 * across process restarts — otherwise a notification posted before a relaunch stops matching its
 * own action — and distinct per book, or two books share an action.
 *
 * Reuses [downloadGroupId] rather than adding a second String→Int mapping, so there is one place
 * where collision behaviour is defined. A numeric id therefore still yields `prefix + id`,
 * preserving the codes of notifications posted before the retype.
 */
fun requestCodeFor(
  prefix: Int,
  bookId: String,
): Int = prefix + downloadGroupId(bookId)

/**
 * Key under which a download request carries its book id in Fetch2's `Extras`.
 *
 * [downloadGroupId] is one-way — a hash cannot be reversed — but Fetch2's listeners hand back an
 * `Int` groupId and the app needs the real book id to update the database. So the id travels with
 * the request and comes back verbatim (cu-71).
 */
const val EXTRA_BOOK_ID = "chronicle.bookId"

/**
 * The book id a download was enqueued for, or null if the request predates [EXTRA_BOOK_ID].
 *
 * Returns null rather than guessing for downloads enqueued by an older version: those have no
 * extras, and inventing an id would update the wrong book's cached status.
 */
fun com.tonyodev.fetch2.Download.bookIdOrNull(): String? = extras.getString(EXTRA_BOOK_ID, "").ifEmpty { null }

/**
 * Groups downloads by the book they belong to, dropping any that predate [EXTRA_BOOK_ID].
 *
 * Replaces `groupBy { it.group }`. Fetch2's group is [downloadGroupId]'s output — a hash, so it
 * cannot be turned back into a book id, and two ids could in principle share one. Reading the id
 * the request carried avoids both problems.
 *
 * Downloads enqueued by a version before cu-71 have no extras. They are dropped rather than
 * attributed to a guessed id: the consequence of a wrong guess is marking the wrong book
 * downloaded, and a dropped one is picked up by the next cache scan
 * (`CachedFileManager.refreshCachedFileStatus`) instead.
 */
fun List<com.tonyodev.fetch2.Download>.groupByBookId(): Map<String, List<com.tonyodev.fetch2.Download>> =
  mapNotNull { download -> download.bookIdOrNull()?.let { it to download } }
    .groupBy({ it.first }, { it.second })
