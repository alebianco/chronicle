package io.github.mattpvaughn.chronicle.util

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The cu-110 gate: which emissions reach the UI, and which are dropped.
 *
 * `ProgressUpdater` writes to the `Audiobook` table once a second during playback, and Room
 * invalidates per **table** — so every `LiveData` query on it re-emits at tick rate. On Home that
 * was three shelf queries per second, each rebuilding its list and deserializing
 * `Audiobook.chapters` for every book returned: 88% janky frames, main thread pinned at ~24% of a
 * core, a GC every ~4s freeing ~165,000 objects.
 *
 * Both halves need pinning, because the obvious fix breaks the second one. Keying on ids alone
 * suppresses the churn *and* silently swallows real progress changes — which is exactly what
 * `LibraryViewModel` did, leaving its progress bars frozen. So these tests assert the drop and the
 * pass-through with equal weight.
 */
class DistinctByTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  private fun book(
    id: String,
    progress: Long = 0L,
    isCached: Boolean = false,
    title: String = "Dune",
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    progress = progress,
    isCached = isCached,
  )

  /** Collects everything the downstream observer actually sees. */
  private fun observed(
    source: MutableLiveData<List<Audiobook>>,
    vararg emissions: List<Audiobook>,
  ): List<List<Audiobook>> {
    val seen = mutableListOf<List<Audiobook>>()
    source.distinctBy { it.booksKey() }.observeForever { seen.add(it) }
    emissions.forEach { source.value = it }
    return seen
  }

  /**
   * The measured mechanism. Room re-emits an identical list once a second; the UI must see it
   * once.
   */
  @Test
  fun `an unchanged list emits once, not once per tick`() {
    val source = MutableLiveData<List<Audiobook>>()
    val books = listOf(book("1001", progress = 90_000L), book("1002"))

    val seen = observed(source, books, books, books, books, books)

    assertEquals("re-emitting the same list must not reach the UI five times", 1, seen.size)
  }

  /**
   * A *distinct but equal* list is what Room actually hands over — a fresh query result, new
   * objects, same values. Keying on the values rather than on identity is the whole point.
   */
  @Test
  fun `an equal list built from new objects still emits once`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen =
      observed(
        source,
        listOf(book("1001", progress = 90_000L)),
        listOf(book("1001", progress = 90_000L)),
      )

    assertEquals(1, seen.size)
  }

  /**
   * The half that a naive fix breaks. Every list row draws a progress bar, so a genuine progress
   * change *must* propagate — `LibraryViewModel`'s id-only comparison is why its bars stopped
   * moving.
   */
  @Test
  fun `a progress change reaches the UI`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen =
      observed(
        source,
        listOf(book("1001", progress = 90_000L)),
        listOf(book("1001", progress = 120_000L)),
      )

    assertEquals("a real progress change must not be swallowed", 2, seen.size)
    assertEquals(120_000L, seen.last().single().progress)
  }

  /** Download state is rendered too, so it belongs in the key. */
  @Test
  fun `a cached-state change reaches the UI`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen =
      observed(
        source,
        listOf(book("1001", isCached = false)),
        listOf(book("1001", isCached = true)),
      )

    assertEquals(2, seen.size)
  }

  /** A book appearing or disappearing changes the shelf. */
  @Test
  fun `a added or removed book reaches the UI`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen =
      observed(
        source,
        listOf(book("1001")),
        listOf(book("1001"), book("1002")),
        listOf(book("1002")),
      )

    assertEquals(3, seen.size)
  }

  /** Order is part of the key: a re-sorted shelf is a different shelf. */
  @Test
  fun `a reordered list reaches the UI`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen =
      observed(
        source,
        listOf(book("1001"), book("1002")),
        listOf(book("1002"), book("1001")),
      )

    assertEquals(2, seen.size)
  }

  /**
   * The first emission must always arrive, including when its key is `null`-ish or the list is
   * empty — otherwise an empty shelf never renders its empty state. This is what the `NO_KEY_YET`
   * sentinel is for; `null` as the initial "no key" value would swallow it.
   */
  @Test
  fun `the first emission always arrives, even when empty`() {
    val source = MutableLiveData<List<Audiobook>>()

    val seen = observed(source, emptyList())

    assertEquals(1, seen.size)
    assertEquals(emptyList<Audiobook>(), seen.single())
  }

  /**
   * The key must be cheap: it must not touch `chapters`, the serialized column whose
   * deserialization was the measured cost. Asserted by showing that two books differing *only* in
   * chapters compare equal — if the key ever grows to include them, this fails and says why.
   */
  @Test
  fun `the key ignores the serialized chapters column`() {
    val withChapters = book("1001").copy(chapters = List(108) { chapter(it) })
    val withoutChapters = book("1001")

    assertEquals(
      "including chapters in the key reintroduces the per-tick deserialization cost",
      listOf(withoutChapters).booksKey(),
      listOf(withChapters).booksKey(),
    )
  }

  private fun chapter(index: Int) =
    Chapter(
      id = index.toString(),
      bookId = "1001",
      trackId = "2001",
      title = "Chapter $index",
      index = index.toLong(),
      discNumber = 1,
      bookStartTimeOffset = index * 1000L,
      bookEndTimeOffset = (index + 1) * 1000L,
    )
}
