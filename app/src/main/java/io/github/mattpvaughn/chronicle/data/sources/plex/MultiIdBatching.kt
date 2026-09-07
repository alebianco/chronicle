package io.github.mattpvaughn.chronicle.data.sources.plex

/**
 * How many characters of comma-joined ids one multi-id request may carry.
 *
 * **Deliberately far below what the server tolerates.** A probe against a real Plex with a 22 KB URL
 * and got a 200, so Plex itself is not the constraint — but the relay is one of the three
 * connection tiers, and a reverse proxy commonly caps the request line at 8 KB. 2 KB of
 * ids leaves ample room for the host, path and query while still making a household library
 * (196 books, ~1.4 KB) a single request.
 */
const val MAX_ID_STRING_LENGTH = 2048

/**
 * Splits [ids] into batches whose comma-joined length stays within [MAX_ID_STRING_LENGTH].
 *
 * Bounded by **length, not count**, because ids are free-form strings — a backend with
 * long ids would blow a count-based limit while a numeric-id server stayed far inside it.
 *
 * Order is preserved so a failed batch names the books it covered. An id longer than the cap on its
 * own is still emitted as a single batch: dropping it would silently lose a book, and letting the
 * server refuse an over-long URL is the more honest failure.
 */
fun batchIdsByUrlLength(
  ids: List<String>,
  maxLength: Int = MAX_ID_STRING_LENGTH,
): List<List<String>> {
  if (ids.isEmpty()) return emptyList()

  val batches = mutableListOf<List<String>>()
  var current = mutableListOf<String>()
  var currentLength = 0

  for (id in ids) {
    // The separator only costs a character when something precedes it.
    val added = if (current.isEmpty()) id.length else id.length + 1
    if (current.isNotEmpty() && currentLength + added > maxLength) {
      batches += current
      current = mutableListOf()
      currentLength = 0
    }
    current += id
    currentLength += if (current.size == 1) id.length else id.length + 1
  }
  if (current.isNotEmpty()) batches += current
  return batches
}
