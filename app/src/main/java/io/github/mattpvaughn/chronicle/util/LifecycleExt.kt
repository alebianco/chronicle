package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The identity of a book list *as the UI draws it*: id, cached state and progress.
 *
 * The key for `distinctUntilChangedBy` on every shelf and list query (cu-110). Room invalidates per
 * **table**, so every query on `Audiobook` re-emits whenever `ProgressUpdater` writes — once a
 * second during playback — and each emission rebuilds a list and deserializes `Audiobook.chapters`
 * for every book in it. Measured on Home: 88% janky frames and a GC every ~4s.
 *
 * The key must be **cheap to compute and complete enough to notice a real change**. Comparing whole
 * [io.github.mattpvaughn.chronicle.data.model.Audiobook]s defeats the purpose (`equals` walks the
 * serialized chapters string); comparing ids alone is worse than useless — `LibraryViewModel` did
 * that and its progress bars silently stopped updating, because a genuine progress change produced
 * an unchanged key. Progress is included for that reason; `title`/`author`/`thumb` are omitted
 * because a metadata edit arrives through a refresh that changes the id set or `updatedAt` rarely,
 * and comparing them per tick is not worth catching that. `BooksKeyDedupTest` pins both halves.
 */
fun List<io.github.mattpvaughn.chronicle.data.model.Audiobook>.booksKey(): List<Triple<String, Boolean, Long>> =
  map { Triple(it.id, it.isCached, it.progress) }

/** A wrapper for a value exposed as a one-shot event rather than as state. */
open class Event<out T>(private val content: T) {
  var hasBeenHandled = false
    private set // Allow external read but not write

  /** Returns the content and prevents its use again. */
  fun getContentIfNotHandled(): T? {
    return if (hasBeenHandled) {
      null
    } else {
      hasBeenHandled = true
      content
    }
  }

  /** Returns the content, even if it's already been handled. */
  fun peekContent(): T = content
}

/**
 * Publishes [value] as a fresh [Event].
 *
 * Assigns rather than posts, which is the point: a `StateFlow` write lands immediately, so a reader
 * in the same main-loop pass sees it (cu-52). The receiver is nullable because "no event yet" is a
 * real state — seeding one of these with a blank `Event` would make a fresh screen hold an event
 * that never happened.
 */
fun <T> MutableStateFlow<Event<T>?>.setEvent(value: T) {
  this.value = Event(value)
}
