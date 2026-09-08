package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Circuit `Ui` half: that each control posts the **right event**.
 *
 * The presenter test covers what each event does; this covers that the UI raises the one it means
 * to. Together they close the gap a `*Destination` left open — its lambdas were wired by position,
 * so a control connected to the wrong handler compiled and looked correct.
 *
 * Robolectric because this renders real Compose UI, unlike the presenter, which needs no Android.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesIndexTesterUiTest {
  @get:Rule
  val compose = createComposeRule()

  private fun setUi(onEvent: (SeriesIndexTesterEvent) -> Unit) {
    compose.setContent {
      ChronicleTheme {
        SeriesIndexTesterUi(
          state = SeriesIndexTesterCircuitState(ui = SeriesIndexTesterUiState(), eventSink = onEvent),
        )
      }
    }
  }

  @Test
  fun `the back arrow raises NavigateUp`() {
    val events = mutableListOf<SeriesIndexTesterEvent>()
    setUi { events.add(it) }

    compose.onNodeWithContentDescription("Back").performClick()

    assertEquals(listOf(SeriesIndexTesterEvent.NavigateUp), events)
  }

  @Test
  fun `typing raises TitleSortChanged, not SampleChosen`() {
    val events = mutableListOf<SeriesIndexTesterEvent>()
    setUi { events.add(it) }

    compose.onNodeWithText("Sort title to test").performTextInput("D")

    assertEquals(
      "the input must raise TitleSortChanged; SampleChosen is the tap on a library title",
      listOf(SeriesIndexTesterEvent.TitleSortChanged("D")),
      events,
    )
  }
}
