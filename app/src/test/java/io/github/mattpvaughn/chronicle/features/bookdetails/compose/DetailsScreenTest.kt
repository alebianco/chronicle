package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The book-details header, asserted on what it renders (cu-200).
 *
 * Replaces `BookDetailsMetadataLayoutTest`, which inflated the old XML and measured four TextViews
 * for overlap — cu-145 found the narrator and series rows overlapping the author by 17px on the
 * tablet. **A `Column` cannot overlap its children**, so that invariant is now structural rather
 * than tested: what is worth asserting instead is that each row appears only when there is
 * something to say, which is the *other* half of cu-145.
 */
@RunWith(RobolectricTestRunner::class)
class DetailsScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun setScreen(
    state: DetailsUiState,
    actions: DetailsActions = DetailsActions(),
  ) {
    compose.setContent {
      ChronicleTheme {
        DetailsScreen(state = state, actions = actions, coverUrl = { "http://localhost/$it" })
      }
    }
  }

  private fun book(
    narrator: String? = null,
    series: String? = null,
  ) = BookHeader(title = "The Hobbit", author = "J R R Tolkien", narrator = narrator, series = series)

  @Test
  fun `the title and author always render`() {
    setScreen(DetailsUiState(book = book()))

    compose.onNodeWithText("The Hobbit").assertIsDisplayed()
    compose.onNodeWithText("J R R Tolkien").assertIsDisplayed()
  }

  /**
   * cu-145's rule: a row appears only when there is something to say.
   *
   * An empty "Narrated by" line claims the book has no narrator, which is a wrong statement rather
   * than a missing one — and most books are missing one or both, since cu-24 learns them only for
   * books the user has opened.
   */
  @Test
  fun `an unknown narrator renders no narrator row`() {
    setScreen(DetailsUiState(book = book(narrator = null)))

    assertEquals(
      "a book with no known narrator must not claim one",
      0,
      compose.onAllNodesWithText("Narrated by", substring = true).fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `a known narrator and series both render`() {
    setScreen(DetailsUiState(book = book(narrator = "Andy Serkis", series = "Middle-earth, Book 1")))

    compose.onNodeWithText("Narrated by Andy Serkis").assertIsDisplayed()
    compose.onNodeWithText("Middle-earth, Book 1").assertIsDisplayed()
  }

  /** The series line navigates into the browse facet (cu-24); losing that is a silent loss. */
  @Test
  fun `tapping the series line reports it`() {
    var tapped = false
    setScreen(
      state = DetailsUiState(book = book(series = "Middle-earth, Book 1")),
      actions = DetailsActions(onSeriesClick = { tapped = true }),
    )

    compose.onNodeWithText("Middle-earth, Book 1").performClick()

    assertTrue("the series line must navigate to the browse facet", tapped)
  }

  /**
   * The download control's spoken label follows its state (cu-149).
   *
   * `CacheLabelPairingTest` checked this by parsing two `when` blocks out of the ViewModel's
   * *source text* and comparing their branch labels — because the icon and the label were separate
   * flows that could drift. One sealed `DownloadState` now drives both, so they cannot; this
   * asserts each state actually announces its own action, which is the part a type cannot check.
   */
  @Test
  fun `a downloaded book offers to remove the download`() {
    setScreen(DetailsUiState(book = book(), download = DownloadState.Cached))

    compose.onNodeWithContentDescription("Remove download").assertIsDisplayed()
  }

  @Test
  fun `an undownloaded book offers to download`() {
    setScreen(DetailsUiState(book = book(), download = DownloadState.NotCached))

    compose.onNodeWithContentDescription("Download").assertIsDisplayed()
  }

  @Test
  fun `a downloading book offers to cancel`() {
    setScreen(DetailsUiState(book = book(), download = DownloadState.Caching))

    compose.onNodeWithContentDescription("Cancel download").assertIsDisplayed()
  }

  /** Buffering replaces the play button in place rather than hiding it. */
  @Test
  fun `a buffering book shows no play button`() {
    setScreen(DetailsUiState(book = book(), playback = PlaybackState(isAudioLoading = true)))

    assertEquals(
      "the play control must be replaced while buffering",
      0,
      compose.onAllNodesWithContentDescription("Pause/Play button").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `a playing book offers pause`() {
    setScreen(DetailsUiState(book = book(), playback = PlaybackState(isPlaying = true)))

    compose.onNodeWithContentDescription("Pause/Play button").assertIsDisplayed()
  }

  // ---- the chapter list, folded into the same LazyColumn (cu-201) ----

  private fun chapter(
    id: String,
    title: String,
    disc: Int = 1,
    index: Long = 0L,
  ) = io.github.mattpvaughn.chronicle.data.model.Chapter(
    id = id,
    title = title,
    discNumber = disc,
    index = index,
    trackId = "t1",
  )

  @Test
  fun `the chapter list renders below the header`() {
    compose.setContent {
      ChronicleTheme {
        DetailsScreen(
          state = DetailsUiState(book = book()),
          actions = DetailsActions(),
          coverUrl = { it },
          chapterRows =
            io.github.mattpvaughn.chronicle.data.model.chapterRows(
              listOf(chapter("1", "An Unexpected Party")),
              activeChapter = null,
            ),
        )
      }
    }

    compose.onNodeWithText("The Hobbit").assertIsDisplayed()
    compose.onNodeWithText("An Unexpected Party").assertIsDisplayed()
  }

  @Test
  fun `tapping a chapter reports which one`() {
    var jumped: String? = null
    compose.setContent {
      ChronicleTheme {
        DetailsScreen(
          state = DetailsUiState(book = book()),
          actions = DetailsActions(),
          coverUrl = { it },
          chapterRows =
            io.github.mattpvaughn.chronicle.data.model.chapterRows(
              listOf(chapter("1", "An Unexpected Party")),
              activeChapter = null,
            ),
          onChapterClick = { jumped = it.title },
        )
      }
    }

    compose.onNodeWithText("An Unexpected Party").performClick()

    assertEquals("An Unexpected Party", jumped)
  }

  /** A multi-disc book gets its headers; a single-disc one must not (cu-201's shared rule). */
  @Test
  fun `a multi-disc book shows disc headers`() {
    compose.setContent {
      ChronicleTheme {
        DetailsScreen(
          state = DetailsUiState(book = book()),
          actions = DetailsActions(),
          coverUrl = { it },
          chapterRows =
            io.github.mattpvaughn.chronicle.data.model.chapterRows(
              listOf(chapter("1", "One"), chapter("2", "Two", disc = 2, index = 1L)),
              activeChapter = null,
            ),
        )
      }
    }

    compose.onNodeWithText("Disc 1").assertIsDisplayed()
  }
}
