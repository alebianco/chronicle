package io.github.mattpvaughn.chronicle.features.download

/**
 * Maps a `String` book id to a stable `Int`, for the two Android APIs that insist on one.
 *
 * **This used to exist for the download engine**, whose grouping API was `int` throughout while
 * book ids are `String` so a non-numeric backend can be represented (decision-11). That engine is
 * gone, and with it the `EXTRA_BOOK_ID` round-trip it forced: a hash cannot be reversed, so the
 * real id had to travel beside it in an extras map and be read back in every listener.
 * `DownloadRequest` and `DownloadEvent` carry the id in a field.
 *
 * What remains needs an `Int` for reasons Android imposes rather than a library:
 * **notification ids** and **PendingIntent request codes**.
 *
 * `String.hashCode` because a code must survive a restart — a notification posted before a
 * relaunch has to keep matching its own action — and that hash is specified by the language rather
 * than by the JVM. Numeric ids map to themselves, which keeps existing notifications stable across
 * this change.
 *
 * Two ids hashing alike would share a notification. Negligible at household scale, and the fix —
 * persisting an id↔code table — is a lot of machinery for a cosmetic collision.
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
