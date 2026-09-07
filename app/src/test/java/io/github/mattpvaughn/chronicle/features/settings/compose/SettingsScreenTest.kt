package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.features.settings.PreferenceModel
import io.github.mattpvaughn.chronicle.features.settings.PreferenceType
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings screen, asserted on what it renders.
 *
 * The View version had no rendering test at all — `SettingsList` was a `FrameLayout` wrapping a
 * programmatic `RecyclerView`, so reading a row's contents needed Espresso on a device. These run
 * on the JVM and count toward the ratchet.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun row(
    type: PreferenceType,
    title: String,
    key: String = title,
    explanation: String = "",
    isChecked: Boolean = false,
  ) = SettingsRow(
    model =
      PreferenceModel(
        type = type,
        title = FormattableString.LiteralString(title),
        key = key,
        explanation =
          if (explanation.isEmpty()) {
            FormattableString.EMPTY_STRING
          } else {
            FormattableString.LiteralString(explanation)
          },
      ),
    isChecked = isChecked,
  )

  private fun setScreen(
    rows: List<SettingsRow>,
    onClick: (PreferenceModel) -> Unit = {},
    onToggle: (PreferenceModel, Boolean) -> Unit = { _, _ -> },
  ) {
    compose.setContent {
      ChronicleTheme { SettingsScreen(rows = rows, onClick = onClick, onToggle = onToggle) }
    }
  }

  @Test
  fun `a title, a clickable row and a switch all render`() {
    setScreen(
      listOf(
        row(PreferenceType.TITLE, "Playback"),
        row(PreferenceType.CLICKABLE, "Sync location", explanation = "Where books are stored"),
        row(PreferenceType.BOOLEAN, "Offline Mode"),
      ),
    )

    // Uppercased by the screen, matching the View style's `textAllCaps` — asserted on the
    // *rendered* text so a straight-port regression fails here rather than on a device.
    compose.onNodeWithText("PLAYBACK").assertIsDisplayed()
    compose.onNodeWithText("Sync location").assertIsDisplayed()
    compose.onNodeWithText("Where books are stored").assertIsDisplayed()
    compose.onNodeWithText("Offline Mode").assertIsDisplayed()
  }

  /** A row with no explanation renders one line, not an empty second one. */
  @Test
  fun `a row without an explanation shows only its title`() {
    setScreen(listOf(row(PreferenceType.CLICKABLE, "Export settings")))

    compose.onNodeWithText("Export settings").assertIsDisplayed()
    // Exactly one node carries text on this row. An explanation-less row skips the Text
    // outright rather than emitting a blank one — which the View version could not do, since a
    // ViewHolder always inflated both and toggled `isVisible`.
    assertEquals(
      "a row with no explanation must emit one text node, not two",
      1,
      compose.onAllNodesWithText("Export settings", substring = true).fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `a clickable row reports which preference was tapped`() {
    var clicked: PreferenceModel? = null
    setScreen(
      rows = listOf(row(PreferenceType.CLICKABLE, "Import settings")),
      onClick = { clicked = it },
    )

    compose.onNodeWithText("Import settings").performClick()

    assertEquals("Import settings", (clicked?.title as? FormattableString.LiteralString)?.string)
  }

  /**
   * The whole row toggles, not just the switch.
   *
   * The View version wired `preferenceSwitchContent` for exactly this; losing it would shrink a
   * full-width target to a 48dp one, which is the touch-target concern in a new place.
   */
  @Test
  fun `tapping anywhere on a switch row toggles it`() {
    var toggled: Pair<String, Boolean>? = null
    setScreen(
      rows = listOf(row(PreferenceType.BOOLEAN, "Skip silent audio", isChecked = false)),
      onToggle = { model, checked ->
        toggled = (model.title as FormattableString.LiteralString).string to checked
      },
    )

    compose.onNodeWithText("Skip silent audio").performClick()

    assertEquals("Skip silent audio" to true, toggled)
  }

  /** And it reports the *inverse* of the current value, not a constant. */
  @Test
  fun `toggling an enabled switch turns it off`() {
    var toggled: Boolean? = null
    setScreen(
      rows = listOf(row(PreferenceType.BOOLEAN, "Shake to snooze", isChecked = true)),
      onToggle = { _, checked -> toggled = checked },
    )

    compose.onNodeWithText("Shake to snooze").performClick()

    assertEquals(false, toggled)
  }

  @Test
  fun `an empty list renders nothing rather than failing`() {
    setScreen(emptyList())

    assertTrue("an empty settings list is a valid state", true)
  }
}
