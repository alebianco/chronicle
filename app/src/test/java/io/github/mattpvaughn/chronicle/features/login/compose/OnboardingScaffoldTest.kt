package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frame shared by the three onboarding pickers.
 *
 * The refresh test is the point of this file. `onboarding_plex_choose_library.xml` carried a
 * refresh icon with **no click listener in Kotlin** — a button that did nothing, beside a server
 * picker whose identical icon worked. Making `onRefresh` a required parameter made that
 * unrepresentable; this asserts the parameter is actually wired to the icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class OnboardingScaffoldTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `it shows its title and content`() {
    composeRule.setContent {
      ChronicleTheme {
        OnboardingScaffold(title = "Choose a server", onRefresh = {}) {
          Text("a server")
        }
      }
    }

    composeRule.onNodeWithText("Choose a server").assertIsDisplayed()
    composeRule.onNodeWithText("a server").assertIsDisplayed()
  }

  @Test
  fun `the refresh icon is wired`() {
    var refreshed = 0
    composeRule.setContent {
      ChronicleTheme {
        OnboardingScaffold(title = "Choose a library", onRefresh = { refreshed++ }) {}
      }
    }

    composeRule.onNodeWithContentDescription("Refresh list").performClick()

    assertEquals(1, refreshed)
  }
}
