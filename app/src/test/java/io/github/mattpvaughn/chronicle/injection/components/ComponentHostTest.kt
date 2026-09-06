package io.github.mattpvaughn.chronicle.injection.components

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.launchFragmentInContainer
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the resolution order of both injection seams (cu-178).
 *
 * The two are **deliberately opposite**, and that is the whole point of this suite:
 *
 * - [injectFromHost] checks the **host first**, because `FragmentScenario` hosts fragments in
 *   `EmptyFragmentActivity`, which is not an [ActivityComponentHost] — so under test the host
 *   branch cannot resolve and the override is genuinely the only path.
 * - [injectFromAppGraph] checks the **override first**, because Robolectric reads the real manifest
 *   and instantiates the real `ChronicleApplication`, which *is* an [AppComponentHost] — so a
 *   host-first ordering makes the override unreachable under test.
 *
 * That second case is not hypothetical. Written host-first, a `ChooseServerFragment` scenario suite
 * passed with its mock's `inject` doing nothing at all: it was silently building the real Dagger
 * graph. A test that cannot fail proves nothing, so the ordering is pinned here rather than left to
 * a comment.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentHostTest {
  class BlankFragment : Fragment()

  @After
  fun clearGraphs() {
    testActivityComponent = null
    testAppComponent = null
  }

  @Test
  fun `the activity seam reports failure when neither host nor override can supply a graph`() {
    testActivityComponent = null
    launchFragmentInContainer<BlankFragment>().use { scenario ->
      scenario.onFragment { fragment ->
        var injected = false
        val resolved = fragment.injectFromHost { injected = true }
        assertFalse("EmptyFragmentActivity is not an ActivityComponentHost", resolved)
        assertFalse("nothing may be injected when no graph resolved", injected)
      }
    }
  }

  @Test
  fun `the activity seam falls back to the test override`() {
    val component = mockk<ActivityComponent>(relaxed = true)
    testActivityComponent = component
    launchFragmentInContainer<BlankFragment>().use { scenario ->
      scenario.onFragment { fragment ->
        var seen: ActivityComponent? = null
        assertTrue(fragment.injectFromHost { seen = it })
        assertSame(component, seen)
      }
    }
  }

  /**
   * The regression that made this suite necessary: Robolectric supplies a real `ChronicleApplication`,
   * so an override checked *after* the host would never be reached and every app-graph scenario test
   * would silently assert against the production graph.
   */
  @Test
  fun `the app seam prefers the test override over the real Application`() {
    assertTrue(
      "Robolectric must be running the real Application for this test to mean anything",
      RuntimeEnvironment.getApplication() is AppComponentHost,
    )

    val component = mockk<AppComponent>(relaxed = true)
    testAppComponent = component
    launchFragmentInContainer<BlankFragment>().use { scenario ->
      scenario.onFragment { fragment ->
        var seen: AppComponent? = null
        assertTrue(fragment.injectFromAppGraph { seen = it })
        assertSame("the override must win, not the real Application's graph", component, seen)
      }
    }
  }

  @Test
  fun `the app seam uses the real Application when no override is installed`() {
    testAppComponent = null
    val application = RuntimeEnvironment.getApplication() as AppComponentHost
    launchFragmentInContainer<BlankFragment>().use { scenario ->
      scenario.onFragment { fragment ->
        var seen: AppComponent? = null
        assertTrue(fragment.injectFromAppGraph { seen = it })
        assertSame(application.appComponentForInjection, seen)
      }
    }
  }

  @Test
  fun `the seams inject exactly once`() {
    testAppComponent = mockk(relaxed = true)
    testActivityComponent = mockk(relaxed = true)
    launchFragmentInContainer<BlankFragment>().use { scenario ->
      scenario.onFragment { fragment ->
        var appCalls = 0
        var activityCalls = 0
        fragment.injectFromAppGraph { appCalls++ }
        fragment.injectFromHost { activityCalls++ }
        assertEquals(1, appCalls)
        assertEquals(1, activityCalls)
      }
    }
  }

  @Test
  fun `the real Application is an AppComponentHost`() {
    val application: Application = RuntimeEnvironment.getApplication()
    assertTrue(
      "ChronicleApplication must implement AppComponentHost or the login screens cannot inject",
      application is AppComponentHost,
    )
  }
}
