package io.github.mattpvaughn.chronicle.features.library

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First tests for [LibraryViewModel], which sat at **0% instruction coverage** — 701 missed
 * instructions on the screen that is the app's front door.
 *
 * The `books` flow is the whole subject: it combines five sources and applies, in order, an
 * **offline filter**, a **hide-played filter**, and one of seven **sort orders**. Every one of
 * those is a user-visible decision, and the sort comparator's `else` branch throws — which is how
 * `LibrarySortKeyTest` came to exist after this suite found that four advertised sort keys had no
 * branch at all.
 *
 * `SharedPreferences` is a hand-written fake, not a mock. `preferenceFlow` registers a listener and
 * emits on callback, so a `relaxed` mock emits nothing, `combine` never fires, and every assertion
 * about the resulting list would pass against an empty flow — the trap now recorded in CLAUDE.md.
 */
class LibraryViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  /**
   * The real settings store, not a hand-written `SharedPreferences` fake.
   *
   * The fake existed because these ViewModels observed preferences directly; they read
   * `SettingsDataStore` now, so a fake would be asserting against a reimplementation of the thing
   * under test — and the ViewModel would read an empty store while the test wrote to the fake.
   */
  private val prefs = testSettingsDataStore("library-vm")
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())

  private fun book(
    id: String,
    title: String,
    author: String = "Author",
    isCached: Boolean = false,
    viewCount: Long = 0L,
    duration: Long = 1_000L,
    year: Int = 2000,
    addedAt: Long = 0L,
  ) = Audiobook(
    id = id,
    source = TEST_SOURCE,
    title = title,
    titleSort = title,
    author = author,
    isCached = isCached,
    viewCount = viewCount,
    duration = duration,
    year = year,
    addedAt = addedAt,
  )

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { libraryBookViewStyle } returns "COVER_GRID"
    }
  private val syncRepository = mockk<LibrarySyncRepository>(relaxed = true)

  private fun viewModel(): LibraryViewModel {
    every { syncRepository.isRefreshing } returns MutableStateFlow(false)
    every { syncRepository.errorMessage } returns MutableStateFlow(null)
    val bookRepository =
      mockk<IBookRepository>(relaxed = true) {
        every { getAllBooks() } returns booksFlow
      }
    return LibraryViewModel(
      bookRepository = bookRepository,
      trackRepository = mockk<ITrackRepository>(relaxed = true),
      prefsRepo = prefsRepo,
      settings = prefs,
      cachedFileManager = mockk<ICachedFileManager>(relaxed = true),
      librarySyncRepository = syncRepository,
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      appContext = mockk<Context>(relaxed = true),
      dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
    )
  }

  @Test
  fun `books sort by title naturally so book 2 precedes book 10`() =
    runTest {
      booksFlow.value =
        listOf(
          book("1", "Book 10"),
          book("2", "Book 2"),
          book("3", "Book 1"),
        )

      val titles = viewModel().books.first().map { it.title }

      assertEquals(listOf("Book 1", "Book 2", "Book 10"), titles)
    }

  @Test
  fun `flipping the sort direction reverses the order`() =
    runTest {
      prefs.set(booleanPreferencesKey(PrefsRepo.KEY_IS_LIBRARY_SORT_DESCENDING), false)
      booksFlow.value = listOf(book("1", "Alpha"), book("2", "Beta"))

      val titles = viewModel().books.first().map { it.title }

      assertEquals(listOf("Beta", "Alpha"), titles)
    }

  /**
   * Offline mode is the filter with real consequences: showing an uncached book offline lets the
   * user open a player that cannot resolve a file. `AudiobookDetailsViewModel` has its own guard
   * for the same reason (the `Eagerly` note in CLAUDE.md).
   */
  @Test
  fun `offline mode hides books that are not downloaded`() =
    runTest {
      prefs.set(booleanPreferencesKey(PrefsRepo.KEY_OFFLINE_MODE), true)
      booksFlow.value =
        listOf(
          book("1", "Downloaded", isCached = true),
          book("2", "Streaming only", isCached = false),
        )

      val titles = viewModel().books.first().map { it.title }

      assertEquals(listOf("Downloaded"), titles)
    }

  @Test
  fun `online mode shows every book regardless of download state`() =
    runTest {
      booksFlow.value =
        listOf(
          book("1", "Downloaded", isCached = true),
          book("2", "Streaming only", isCached = false),
        )

      assertEquals(2, viewModel().books.first().size)
    }

  /**
   * "Played" is `viewCount`, the explicit completion fact — never a derived position
   * (decision-16). A book at 99% with no view count must still be listed.
   */
  @Test
  fun `hiding played books filters on view count rather than progress`() =
    runTest {
      prefs.set(booleanPreferencesKey(PrefsRepo.KEY_HIDE_PLAYED_AUDIOBOOKS), true)
      booksFlow.value =
        listOf(
          book("1", "Finished", viewCount = 1L),
          book("2", "Nearly finished", viewCount = 0L),
        )

      val titles = viewModel().books.first().map { it.title }

      assertEquals(listOf("Nearly finished"), titles)
    }

  @Test
  fun `the two filters compose`() =
    runTest {
      prefs.set(booleanPreferencesKey(PrefsRepo.KEY_OFFLINE_MODE), true)
      prefs.set(booleanPreferencesKey(PrefsRepo.KEY_HIDE_PLAYED_AUDIOBOOKS), true)
      booksFlow.value =
        listOf(
          book("1", "Keep", isCached = true, viewCount = 0L),
          book("2", "Played and cached", isCached = true, viewCount = 1L),
          book("3", "Unplayed but streaming", isCached = false, viewCount = 0L),
        )

      assertEquals(listOf("Keep"), viewModel().books.first().map { it.title })
    }

  @Test
  fun `an empty library stays empty`() =
    runTest {
      booksFlow.value = emptyList()

      assertTrue(viewModel().books.first().isEmpty())
    }

  /**
   * Every key in `Audiobook.SORT_KEYS` must reach a comparator branch — the `else` throws
   * `NoWhenBranchMatchedException`. This exercises each one end to end; `LibrarySortKeyTest` is the
   * build guard that stops the list and the comparator drifting apart again.
   */
  @Test
  fun `every advertised sort key produces an order rather than throwing`() =
    runTest {
      booksFlow.value =
        listOf(
          book("1", "B", author = "Sanderson, Brandon", duration = 200L, year = 2001, addedAt = 5),
          book("2", "A", author = "Abercrombie, Joe", duration = 100L, year = 1999, addedAt = 9),
        )

      Audiobook.SORT_KEYS.forEach { key ->
        prefs.set(stringPreferencesKey(PrefsRepo.KEY_BOOK_SORT_BY), key)
        val sorted = viewModel().books.first()
        assertEquals("sort key '$key' must return every book", 2, sorted.size)
      }
    }

  @Test
  fun `the filter menu opens and closes`() =
    runTest {
      val vm = viewModel()
      assertFalse(vm.isFilterShown.value)

      vm.setFilterMenuVisible(true)
      assertTrue(vm.isFilterShown.value)

      vm.setFilterMenuVisible(false)
      assertFalse(vm.isFilterShown.value)
    }

  @Test
  fun `toggling sort direction writes the inverted value back to prefs`() =
    runTest {
      every { prefsRepo.isLibrarySortedDescending } returns true

      viewModel().toggleSortDirection()

      verify { prefsRepo.isLibrarySortedDescending = false }
    }

  @Test
  fun `toggling hide-played writes the inverted value back to prefs`() =
    runTest {
      every { prefsRepo.hidePlayedAudiobooks } returns false

      viewModel().toggleHidePlayedAudiobooks()

      verify { prefsRepo.hidePlayedAudiobooks = true }
    }

  @Test
  fun `disabling offline mode clears the preference`() =
    runTest {
      viewModel().disableOfflineMode()

      verify { prefsRepo.offlineMode = false }
    }

  @Test
  fun `refreshing delegates to the sync repository`() =
    runTest {
      viewModel().refreshData()

      verify { syncRepository.refreshLibrary() }
    }
}
