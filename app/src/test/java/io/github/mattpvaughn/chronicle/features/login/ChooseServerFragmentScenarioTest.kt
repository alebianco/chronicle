package io.github.mattpvaughn.chronicle.features.login

import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginRepo
import io.github.mattpvaughn.chronicle.testing.launchFragmentInHiltContainer
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first login screen under `FragmentScenario` — and the reason the *app*-graph seam exists.
 *
 * The four login screens do not use [io.github.mattpvaughn.chronicle.injection.components.ActivityComponent]:
 * they run before a library is chosen, so they inject from `AppComponent` and used to reach it by
 * casting twice, through `Activity` *and* `ChronicleApplication`. cu-178 removed the
 * `ActivityComponent` cast; this suite exists because that left the login flow — the part of the
 * app a household sees when something has already gone wrong — still unreachable on the JVM.
 *
 * The recipe is otherwise the one the three `ActivityComponent` suites established: real factory
 * over fakes, a mocked component whose `inject` populates the `lateinit`s, `launchFragmentInContainer`
 * with the app theme.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
class ChooseServerFragmentScenarioTest {
  @get:Rule
  val hiltRule = HiltAndroidRule(this)

  /** What the ViewModel builds from, bound into the real test graph (cu-185). */

  @BindValue
  @JvmField
  val plexLoginRepo: PlexLoginRepo = mockk(relaxed = true)

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `the server chooser reaches a resumed state in a generic host`() {
    launchFragmentInHiltContainer<ChooseServerFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull("the view must be created", it.view) }
    }
  }

  /**
   * The state a user is actually in on this screen: the server list has not arrived yet. It is the
   * default `LoadingStatus.LOADING`, so it is what renders first on every real login.
   */
  @Test
  fun `the server chooser renders while still loading`() {
    launchFragmentInHiltContainer<ChooseServerFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { assertNotNull(it.view) }
    }
  }

  @Test
  fun `the server chooser survives a recreation`() {
    launchFragmentInHiltContainer<ChooseServerFragment>(themeResId = R.style.AppTheme).use { scenario ->
      scenario.recreate()
      scenario.onFragment { assertNotNull(it.view) }
    }
  }
}
