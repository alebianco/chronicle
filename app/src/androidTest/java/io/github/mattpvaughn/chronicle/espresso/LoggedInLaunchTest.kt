package io.github.mattpvaughn.chronicle.espresso

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.debug.MockPlexMode
import org.junit.Assert.assertTrue
import org.junit.Rule
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
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

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
   *
   * Read through **Compose semantics** since cu-206 — there is no `R.id.bottom_nav` any more, and
   * `dumpsys` reports one full-screen `AndroidComposeView` rather than a view tree. The Home tab's
   * content description is the stable handle, and it does not depend on fixture data.
   */
  @Test
  fun launchesIntoTheAppWhenAlreadySignedIn() {
    composeRule.onNodeWithContentDescription("Home").assertIsDisplayed()
  }

  /**
   * The activity survives a configuration change — the cheapest guard against a state-loss crash.
   *
   * Worth more since cu-206 than it was before: the whole UI is one composition now, and the nav
   * back stack is what has to be restored rather than a `FragmentManager`'s.
   */
  @Test
  fun survivesRecreation() {
    composeRule.activityRule.scenario.recreate()

    composeRule.onNodeWithContentDescription("Home").assertIsDisplayed()
  }
}
