package io.github.mattpvaughn.chronicle.features.home

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The second screen under `FragmentScenario`, proving the pattern generalises (cu-178).
 *
 * `CollectionsFragmentScenarioTest` was the proof of concept; this one exists to show the recipe
 * transfers without new plumbing. It is the same three steps:
 *
 *  1. build the screen's **real** `ViewModelProvider.Factory` over fakes,
 *  2. install a mocked `ActivityComponent` whose `inject` populates the Fragment's `lateinit`s,
 *  3. `launchFragmentInContainer` with the app theme.
 *
 * A real factory rather than a mocked one, for the reason the first suite records: `ViewModelProvider`
 * picks among several `create` overloads and stubbing the wrong one fails at run time with "no
 * answer found".
 */
@RunWith(RobolectricTestRunner::class)
class HomeFragmentScenarioTest {
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())

  private fun realFactory(): HomeViewModel.Factory {
    val bookRepository =
      mockk<IBookRepository>(relaxed = true) {
        every { getAllBooks() } returns booksFlow
        every { getRecentlyListened() } returns booksFlow
        every { getRecentlyAdded() } returns booksFlow
        every { getCachedAudiobooks() } returns booksFlow
      }
    val syncRepository =
      mockk<LibrarySyncRepository>(relaxed = true) {
        every { isRefreshing } returns MutableStateFlow(false)
        every { errorMessage } returns MutableStateFlow(null)
      }
    return HomeViewModel.Factory(
      plexConfig = mockk<PlexConfig>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) },
      bookRepository = bookRepository,
      librarySyncRepository = syncRepository,
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true),
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
    )
  }

  @Before
  fun installGraph() {
    val factory = realFactory()
    val fragmentSlot = slot<HomeFragment>()
    testActivityComponent =
      mockk<ActivityComponent>(relaxed = true) {
        every { inject(capture(fragmentSlot)) } answers {
          fragmentSlot.captured.apply {
            viewModelFactory = factory
            prefsRepo = mockk(relaxed = true)
            navigator = mockk(relaxed = true)
            plexConfig =
              mockk(relaxed = true) {
                // `isConnected` must be stubbed, not relaxed: a relaxed `StateFlow<Boolean>` hands
                // back a `StateFlow<Object>`, and `collectAsStateWithLifecycle` throws
                // ClassCastException the moment Compose reads it (cu-187's finding).
                every { isConnected } returns MutableStateFlow(true)
                every { toServerString(any()) } returns "http://localhost/cover.jpg"
              }
          }
          Unit
        }
      }
  }

  @After
  fun clearGraph() {
    testActivityComponent = null
  }

  @Test
  fun `the home screen reaches a resumed state in a generic host`() {
    launchFragmentInContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /**
   * The empty library is the state a new user sees first, and the one most likely to be wrong —
   * an adapter that assumes at least one book, or an "empty" message rendered over real content.
   */
  @Test
  fun `the home screen renders with an empty library`() {
    booksFlow.value = emptyList()

    launchFragmentInContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the home screen renders with books present`() {
    booksFlow.value =
      listOf(
        Audiobook(id = "1001", source = TEST_SOURCE, title = "Mistborn"),
        Audiobook(id = "1002", source = TEST_SOURCE, title = "Elantris"),
      )

    launchFragmentInContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  /** Rotation is destroy/recreate, and where most Fragment bugs here have come from. */
  @Test
  fun `the home screen survives a recreation`() {
    launchFragmentInContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
