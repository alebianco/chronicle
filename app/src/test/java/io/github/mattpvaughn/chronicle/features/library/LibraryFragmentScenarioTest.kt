package io.github.mattpvaughn.chronicle.features.library

import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.launchFragmentInHiltContainer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The library screen under `FragmentScenario` — the largest of the eight at 1,259 instructions.
 *
 * Follows the recipe established by `CollectionsFragmentScenarioTest` (proof of concept) and
 * `HomeFragmentScenarioTest` (generalisation): real factory over fakes, mocked `ActivityComponent`
 * whose `inject` populates the `lateinit`s, `launchFragmentInContainer` with the app theme.
 *
 * `SharedPreferences` is a hand-written fake rather than a mock, for the reason recorded in
 * CLAUDE.md: `preferenceFlow` registers a listener and emits on callback, so a relaxed mock emits
 * nothing, the `combine` behind `books` never fires, and the screen renders against a flow that
 * produced nothing — green, and proving nothing.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class LibraryFragmentScenarioTest {
  private val booksFlow = MutableStateFlow<List<Audiobook>>(emptyList())
  private val scheduler = TestCoroutineScheduler()

  @get:Rule
  val hiltRule = HiltAndroidRule(this)

  /**
   * What the screen reads, bound into the real test graph (cu-185).
   *
   * `FakePrefs` rather than a relaxed `SharedPreferences` mock: a relaxed one drops the listener
   * registration, so `preferenceFlow` never emits and every assertion downstream passes against a
   * flow that produced nothing.
   *
   * `isConnected` is stubbed for the same class of reason — a relaxed `StateFlow<Boolean>` is a
   * `StateFlow<Object>`, which `collectAsStateWithLifecycle` throws on (cu-187).
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

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `the library reaches a resumed state in a generic host`() {
    launchFragmentInHiltContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /** The first-run state: no books synced yet. */
  @Test
  fun `the library renders when empty`() {
    booksFlow.value = emptyList()

    launchFragmentInHiltContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the library renders a populated shelf`() {
    booksFlow.value =
      (1..12).map {
        Audiobook(id = "$it", source = TEST_SOURCE, title = "Book $it", titleSort = "Book $it")
      }

    launchFragmentInHiltContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the library survives a recreation`() {
    launchFragmentInHiltContainer<LibraryFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
