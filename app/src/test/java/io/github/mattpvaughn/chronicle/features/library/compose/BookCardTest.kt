package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared book card.
 *
 * Written alongside `AudiobookAdapter`, which four screens still use — so what matters most here
 * is that the *three* view styles all survive. Collapsing them to a two-way `isGrid` boolean would
 * silently drop `VIEW_STYLE_DETAILS_LIST`, and nothing else would notice.
 */
@RunWith(RobolectricTestRunner::class)
class BookCardTest {
  @get:Rule
  val compose = createComposeRule()

  private fun book(
    title: String = "Dune",
    author: String = "Frank Herbert",
    progress: Long = 0L,
    viewCount: Long = 0L,
    duration: Long = 3_600_000L,
  ) = Audiobook(
    id = "1",
    source = TEST_SOURCE,
    title = title,
    author = author,
    progress = progress,
    viewCount = viewCount,
    duration = duration,
  )

  private fun setCard(
    book: Audiobook = book(),
    style: ViewStyleKind = ViewStyleKind.CoverGrid,
    onClick: () -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        BookCard(
          book = book,
          style = style,
          serverConnected = true,
          coverUrl = { "http://localhost/$it" },
          onClick = onClick,
        )
      }
    }
  }

  @Test
  fun `a cover grid card shows title and author`() {
    setCard(style = ViewStyleKind.CoverGrid)

    compose.onNodeWithText("Dune").assertIsDisplayed()
    compose.onNodeWithText("Frank Herbert").assertIsDisplayed()
  }

  /** The style that a two-way boolean would have dropped. */
  @Test
  fun `a details row shows title and author`() {
    setCard(style = ViewStyleKind.Details)

    compose.onNodeWithText("Dune").assertIsDisplayed()
    compose.onNodeWithText("Frank Herbert").assertIsDisplayed()
  }

  @Test
  fun `a text-only row shows title and author`() {
    setCard(style = ViewStyleKind.TextOnly)

    compose.onNodeWithText("Dune").assertIsDisplayed()
    compose.onNodeWithText("Frank Herbert").assertIsDisplayed()
  }

  // One test per style rather than a loop: `setContent` may be called only once per rule, so
  // iterating throws "has already set content" rather than testing the second style.
  @Test
  fun `a cover grid card reports a tap`() = assertTappable(ViewStyleKind.CoverGrid)

  @Test
  fun `a details row reports a tap`() = assertTappable(ViewStyleKind.Details)

  @Test
  fun `a text-only row reports a tap`() = assertTappable(ViewStyleKind.TextOnly)

  private fun assertTappable(style: ViewStyleKind) {
    var tapped = false
    setCard(style = style, onClick = { tapped = true })

    compose.onNodeWithText("Dune").performClick()

    assertTrue("$style must be tappable", tapped)
  }

  /**
   * Counts progress indicators by their semantics `ProgressBarRangeInfo`, which is what
   * `LinearProgressIndicator` publishes — there is no text or content description to match on.
   */
  private fun progressBarCount(): Int =
    compose.onAllNodes(
      androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
        androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
      ),
    ).fetchSemanticsNodes().size

  /**
   * The progress indicator follows `progressState()` — the shared decision — so this and
   * the three-state rule is stated once, so a renderer cannot invent a fourth reading of it.
   */
  @Test
  fun `an unstarted book renders no progress bar`() {
    setCard(book = book(progress = 0L, viewCount = 0L))

    assertEquals(
      "an unstarted book must show no bar",
      0,
      progressBarCount(),
    )
  }

  @Test
  fun `a part-finished book renders a progress bar`() {
    setCard(book = book(progress = 1_800_000L))

    assertTrue("a started book must show a bar", progressBarCount() > 0)
  }

  /** Marked-as-read at zero progress is *finished*, not unstarted. */
  @Test
  fun `a book marked as read renders a full bar`() {
    setCard(book = book(progress = 0L, viewCount = 1L))

    assertTrue(
      "a book marked as read must show a bar, not the unstarted state",
      progressBarCount() > 0,
    )
  }
}
