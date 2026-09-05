package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A collection's contents, and the diff that decides when a collection tile repaints.
 *
 * `features/collections` was the lowest-covered package in the codebase (8.7%), and both classes
 * here sat at 0%.
 *
 * The load-bearing behaviour is `mapNotNull`: a Plex collection stores **child ids**, and a book
 * can leave the library while the collection still names it — a server-side delete, or a library
 * switch. Resolving each id independently and dropping the misses means a collection with one
 * departed book still opens, showing the rest. Anything stricter turns a stale reference into an
 * empty screen or a crash.
 */
class CollectionDetailsTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun book(
    id: String,
    title: String,
  ) = Audiobook(id = id, source = TEST_SOURCE, title = title)

  private fun collection(
    id: String = "c1",
    title: String = "Cosmere",
    childCount: Long = 2L,
    thumb: String = "/thumb/c1",
  ) = Collection(
    id = id,
    source = TEST_SOURCE,
    title = title,
    childCount = childCount,
    thumb = thumb,
  )

  private val bookRepo = mockk<BookRepository>(relaxed = true)
  private val collectionRepo = mockk<CollectionsRepository>(relaxed = true)

  private fun viewModel(collectionId: String = "c1"): CollectionDetailsViewModel {
    every { collectionRepo.getCollection(collectionId) } returns MutableStateFlow(collection())
    val prefs =
      mockk<SharedPreferences>(relaxed = true) {
        every { getString(any(), any()) } returns "COVER_GRID"
      }
    return CollectionDetailsViewModel(
      collectionId = collectionId,
      bookRepo = bookRepo,
      collectionRepo = collectionRepo,
      prefsRepo = mockk<PrefsRepo>(relaxed = true) { every { libraryBookViewStyle } returns "COVER_GRID" },
      sharedPreferences = prefs,
    )
  }

  @Test
  fun `a collection resolves its child ids to books in order`() =
    runTest {
      coEvery { collectionRepo.getChildIds("c1") } returns listOf("1001", "1002")
      coEvery { bookRepo.getAudiobookAsync("1001") } returns book("1001", "Mistborn")
      coEvery { bookRepo.getAudiobookAsync("1002") } returns book("1002", "Elantris")

      val vm = viewModel()
      advanceUntilIdle()

      assertEquals(listOf("Mistborn", "Elantris"), vm.booksInCollection.value.map { it.title })
    }

  /**
   * The case that matters. A collection names a book the library no longer holds — deleted on the
   * server, or left behind by a library switch — and the screen must still open with the rest.
   */
  @Test
  fun `a book missing from the library is skipped rather than emptying the collection`() =
    runTest {
      coEvery { collectionRepo.getChildIds("c1") } returns listOf("1001", "gone", "1002")
      coEvery { bookRepo.getAudiobookAsync("1001") } returns book("1001", "Mistborn")
      coEvery { bookRepo.getAudiobookAsync("gone") } returns null
      coEvery { bookRepo.getAudiobookAsync("1002") } returns book("1002", "Elantris")

      val vm = viewModel()
      advanceUntilIdle()

      assertEquals(listOf("Mistborn", "Elantris"), vm.booksInCollection.value.map { it.title })
    }

  @Test
  fun `a collection whose books have all gone resolves to empty rather than throwing`() =
    runTest {
      coEvery { collectionRepo.getChildIds("c1") } returns listOf("gone", "also-gone")
      coEvery { bookRepo.getAudiobookAsync(any()) } returns null

      val vm = viewModel()
      advanceUntilIdle()

      assertTrue(vm.booksInCollection.value.isEmpty())
    }

  @Test
  fun `an empty collection resolves to empty`() =
    runTest {
      coEvery { collectionRepo.getChildIds("c1") } returns emptyList()

      val vm = viewModel()
      advanceUntilIdle()

      assertTrue(vm.booksInCollection.value.isEmpty())
    }

  @Test
  fun `books are empty until the lookup completes`() =
    runTest {
      coEvery { collectionRepo.getChildIds("c1") } returns listOf("1001")
      coEvery { bookRepo.getAudiobookAsync("1001") } returns book("1001", "Mistborn")

      val vm = viewModel()

      assertTrue("the seed must be empty, not a placeholder", vm.booksInCollection.value.isEmpty())
    }

  // ---- diff callback ----

  @Test
  fun `two collections are the same item when their ids match`() {
    val diff = CollectionsDiffCallback()

    assertTrue(diff.areItemsTheSame(collection(id = "c1"), collection(id = "c1", title = "renamed")))
    assertFalse(diff.areItemsTheSame(collection(id = "c1"), collection(id = "c2")))
  }

  /**
   * The three fields a collection tile actually draws. Each is pinned separately, because a field
   * dropped from this comparison makes the tile silently stop repainting — nothing fails, it just
   * stops updating.
   */
  @Test
  fun `a tile repaints when any drawn field changes`() {
    val diff = CollectionsDiffCallback()
    val base = collection()

    assertTrue("identical collections must not repaint", diff.areContentsTheSame(base, base.copy()))
    assertFalse("a renamed collection must repaint", diff.areContentsTheSame(base, base.copy(title = "Stormlight")))
    assertFalse("a new cover must repaint", diff.areContentsTheSame(base, base.copy(thumb = "/thumb/new")))
    assertFalse(
      "a changed book count must repaint",
      diff.areContentsTheSame(base, base.copy(childCount = 5L)),
    )
  }

  /**
   * A collection's *sort type* is not drawn on the tile, so changing it must not force a repaint —
   * the counterpart to the rule above, and what stops a refresh from redrawing every tile.
   */
  @Test
  fun `a field the tile does not draw does not force a repaint`() {
    val diff = CollectionsDiffCallback()
    val base = collection()

    assertTrue(
      diff.areContentsTheSame(base, base.copy(sortType = Collection.SortType.CUSTOM)),
    )
  }
}
