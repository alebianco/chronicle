package io.github.mattpvaughn.chronicle.util

/**
 * Whether a list about to be submitted differs from the one already shown, compared by id.
 *
 * A `ListAdapter` hands back only an immutable copy of its list, so a caller wanting to know
 * "is this actually different?" has to compare. Comparing by **id** rather than by `equals` is the
 * point: the playing book's `progress` changes once a second (cu-110), so a full comparison would
 * report a new list on every tick and force a scroll-to-top.
 *
 * Extracted in cu-169 from `LibraryFragment` and `CollectionsFragment`, which held **identical**
 * copies of it — each wrapped in `withContext(Dispatchers.IO)`. That was wrong twice over: there is
 * no IO here, only two in-memory lists, and the block read `adapter.currentList` — a UI object —
 * off the main thread. It is a cheap synchronous comparison and belongs on the caller's thread.
 *
 * A null [currentIds] means "no adapter yet", which counts as different: the list has never been
 * submitted.
 */
fun <T> isDifferentListById(
  incoming: List<T>,
  currentIds: List<String>?,
  idOf: (T) -> String,
): Boolean {
  if (currentIds == null) {
    return true
  }
  if (incoming.size != currentIds.size) {
    return true
  }
  return incoming.indices.any { idOf(incoming[it]) != currentIds[it] }
}
