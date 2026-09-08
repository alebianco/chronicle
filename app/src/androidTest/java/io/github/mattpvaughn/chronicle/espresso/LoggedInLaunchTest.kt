package io.github.mattpvaughn.chronicle.espresso

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
   * Read through **Compose semantics** since the Compose migration — there is no `R.id.bottom_nav` any more, and
   * `dumpsys` reports one full-screen `AndroidComposeView` rather than a view tree. The Home tab's
   * content description is the stable handle, and it does not depend on fixture data.
   */
  @Test
  fun launchesIntoTheAppWhenAlreadySignedIn() {
    awaitHomeShelf()

    composeRule.onNode(homeTab).assertIsDisplayed()
  }

  /**
   * The activity survives a configuration change — the cheapest guard against a state-loss crash.
   *
   * Worth more since the Compose migration than it was before: the whole UI is one composition
   * now, and the nav back stack is what has to be restored rather than a `FragmentManager`'s.
   */
  @Test
  fun survivesRecreation() {
    // Wait for the first composition before recreating: recreating an activity that has not yet
    // navigated to Home restores a back stack that does not contain it, and the assertion then
    // fails for a reason that has nothing to do with recreation.
    awaitHomeShelf()

    composeRule.activityRule.scenario.recreate()

    awaitHomeShelf()
    composeRule.onNode(homeTab).assertIsDisplayed()
  }

  /**
   * Waits until the bottom nav exists, which is the app shell having replaced onboarding.
   *
   * **Compose's automatic idling cannot cover this.** It synchronises on recomposition and pending
   * animations, but the thing being waited for is a `StateFlow` emission: `MainActivity` collects
   * `isLoggedIn`, and `MockPlexMode.enable` seeds prefs then calls `determineLoginState()`, which
   * publishes asynchronously. Measured on a real device, `LOGGED_IN_FULLY` arrived **2.4 seconds
   * after the first test started** — so the suite was asserting against onboarding and reporting
   * "the component with ContentDescription 'Home' is not displayed", which reads like a broken
   * fixture rather than a race.
   *
   * `waitUntil` polls the semantics tree, so it costs nothing when the state is already correct and
   * only spends time when the app genuinely has not settled.
   */
  private fun awaitHomeShelf() {
    composeRule.waitUntil(timeoutMillis = LOGIN_SETTLE_TIMEOUT_MS) {
      composeRule.onAllNodes(homeTab).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * The Home tab, matched by its content description.
   *
   * This read `hasContentDescription("Home") or hasText("Home")`, and the `or` was a **workaround
   * for a real accessibility defect** rather than tolerance for two spellings. With
   * `alwaysShowLabel = false` the selected tab was the only one rendering a `Text` label, and
   * `NavigationBarItem` merged that text over the icon's `contentDescription` — so Home, the launch
   * destination and therefore always selected, was the one tab with no description at all. A
   * description-only matcher found `Library` and `Settings` but never `Home`, which cost **six days
   * of red CI**, diagnosed first as a missing login fixture and then as a race, before a
   * `uiautomator` dump showed the node was simply absent.
   *
   * The description now sits on the item rather than the icon, so every tab exposes one whether
   * selected or not (`BottomBarSemanticsTest`, plus a `uiautomator` dump on the tablet showing all
   * of Home, Library and Settings under `content-desc` on two different tabs). The `or hasText` arm
   * is therefore **removed rather than kept**: leaving it would let the defect return silently,
   * since the label is present exactly when the description used to be missing.
   *
   * `hasClickAction()` narrows it to the tab itself: "Home" also appears as a heading inside the
   * shelf, and without this the matcher found two nodes and failed on the ambiguity rather than on
   * anything real.
   */
  private val homeTab =
    hasContentDescription("Home") and hasClickAction()

  private companion object {
    /**
     * Generous on purpose. The wait ends as soon as the shelf appears, so a high ceiling costs
     * nothing on a fast device and stops a slow emulator — a cold CI runner boots one — from
     * failing for being slow rather than wrong.
     */
    const val LOGIN_SETTLE_TIMEOUT_MS = 30_000L
  }
}
