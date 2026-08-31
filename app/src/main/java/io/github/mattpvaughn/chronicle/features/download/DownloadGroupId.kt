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
