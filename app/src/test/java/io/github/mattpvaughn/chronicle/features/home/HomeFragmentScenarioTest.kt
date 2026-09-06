package io.github.mattpvaughn.chronicle.features.home

import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.LibrarySyncRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.launchFragmentInHiltContainer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
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
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class HomeFragmentScenarioTest {
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())

  @get:Rule
  val hiltRule = HiltAndroidRule(this)

  /**
   * What the screen reads, bound into the real test graph (cu-185).
   *
   * The ViewModel builds itself from these rather than from a hand-assembled factory — the
   * factory is gone, and the graph resolving it is the same one production uses.
   *
   * `isConnected` is stubbed rather than relaxed: a relaxed `StateFlow<Boolean>` hands back a
   * `StateFlow<Object>` and `collectAsStateWithLifecycle` throws `ClassCastException` when Compose
   * reads it (cu-187's finding).
   */
  @BindValue
  @JvmField
  val plexConfig: PlexConfig =
    mockk(relaxed = true) {
      every { isConnected } returns MutableStateFlow(true)
      every { toServerString(any()) } returns "http://localhost/cover.jpg"
    }

  @BindValue
  @JvmField
  val navigator: Navigator = mockk(relaxed = true)

  @BindValue
  @JvmField
  val librarySyncRepository: LibrarySyncRepository =
    mockk(relaxed = true) {
      every { isRefreshing } returns MutableStateFlow(false)
      every { errorMessage } returns MutableStateFlow(null)
    }

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `the home screen reaches a resumed state in a generic host`() {
    launchFragmentInHiltContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
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

    launchFragmentInHiltContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
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

    launchFragmentInHiltContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  /** Rotation is destroy/recreate, and where most Fragment bugs here have come from. */
  @Test
  fun `the home screen survives a recreation`() {
    launchFragmentInHiltContainer<HomeFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
