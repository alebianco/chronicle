package io.github.mattpvaughn.chronicle.espresso

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.debug.MockPlexMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app launches into a usable library against the fixture server.
 *
 * Replaces `OnboardingActivityTest`, which had not compiled since `c5cfd46`. That test typed a
 * username and password into `OnboardingActivity` — but Plex login is OAuth (a PIN approved in a
 * browser), there is no password field to type into, and onboarding became Fragments hosted by the
 * single `MainActivity` in `9e89270`. Its premise was gone, not merely its view ids, so this is a
 * rewrite rather than a repair.
 *
 * `ChronicleTestRunner` enables mock-Plex mode before the application starts, so this needs no
 * credentials and no live server.
 *
 * Deliberately narrow. Driving navigation between tabs was attempted and abandoned: a
 * `BottomNavigationItemView` sits partly under the system bars so Espresso's stock `click()`
 * refuses it, and matching by content description hit the currently-playing sheet instead. Those
 * are Espresso-matcher problems, not app problems, and chasing them here would trade a suite that
 * runs for one that is subtly wrong. Navigation coverage belongs in its own task once the harness
 * has earned trust.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LoggedInLaunchTest {
  /**
   * The precondition every other case rests on. If the fixture session is not seeded, the app shows
   * the login screen and every assertion below fails for a misleading reason.
   */
  @Test
  fun mockPlexModeIsActive() {
    assertTrue(
      "the fixture server must be running, or these tests are exercising the login screen",
      MockPlexMode.isRunning,
    )
  }

  /**
   * A seeded session lands in the app proper, not onboarding.
   *
   * The nav bar is the discriminator: onboarding has none. Asserting on Home's content instead
   * would be asserting on the fixture data, which is a different test.
   */
  @Test
  fun launchesIntoTheAppWhenAlreadySignedIn() {
    ActivityScenario.launch(MainActivity::class.java).use {
      onView(withId(R.id.bottom_nav)).check(matches(isDisplayed()))
      onView(withId(R.id.nav_home)).check(matches(isDisplayed()))
    }
  }

  /** The activity survives a configuration change — the cheapest guard against a state-loss crash. */
  @Test
  fun survivesRecreation() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.recreate()

      onView(withId(R.id.bottom_nav)).check(matches(isDisplayed()))
    }
  }
}
