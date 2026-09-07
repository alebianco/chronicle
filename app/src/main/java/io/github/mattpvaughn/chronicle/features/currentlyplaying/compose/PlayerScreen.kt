package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.ChapterRow
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.chapterList
import io.github.mattpvaughn.chronicle.features.currentlyplaying.PlayerText
import io.github.mattpvaughn.chronicle.views.compose.CoverImage

/**
 * What the player screen can do, as one object.
 *
 * A single parameter rather than nine lambdas, so adding a control does not re-thread every call
 * site and a preview cannot silently omit one.
 */
data class PlayerActions(
  val onPlayPause: () -> Unit = {},
  val onSkipForwards: () -> Unit = {},
  val onSkipBackwards: () -> Unit = {},
  val onSkipToNext: () -> Unit = {},
  val onSkipToPrevious: () -> Unit = {},
  val onSeek: (Float) -> Unit = {},
  val onSlideStart: () -> Unit = {},
  val onSlideFinished: () -> Unit = {},
  val onChangeSpeed: () -> Unit = {},
  val onSleepTimer: () -> Unit = {},
  val onAddBookmark: () -> Unit = {},
  val onShowBookmarks: () -> Unit = {},
)

/**
 * The player's body.
 *
 * Stateless: it takes a [PlayerUiState] and emits events, so the whole screen is reachable from
 * `createComposeRule()` with no Robolectric, no `FragmentScenario` and no mocked Dagger component
 * — which is what the Fragment version never was.
 *
 * **Every visibility guard is gone, not ported.** The Fragment established "am I on screen?" with
 * `!seekbar.isShown || root.height == 0`, because a collapsed sheet is zero height with every
 * child still `VISIBLE`. That inference caused two landscape bugs (the landscape probe anchored on a
 * view `values-land` hides). Here the Fragment simply does not compose this at all when the sheet
 * is collapsed, which it now knows from `bottomSheetState` rather than from geometry.
 */
@Composable
fun PlayerScreen(
  state: PlayerUiState,
  actions: PlayerActions,
  coverUrl: (String) -> String,
  showArtwork: Boolean,
  modifier: Modifier = Modifier,
  chapterRows: List<ChapterRow> = emptyList(),
  onChapterClick: (Chapter) -> Unit = {},
) {
  // One `LazyColumn` for the transport body *and* the chapters, so they scroll together — the
  // View version had a separate RecyclerView coordinating with the collapsing toolbar by hand.
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    item(key = "player-body") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Landscape drops the cover rather than shrinking it — the same call `values-land`'s
        // `currently_playing_artwork_visibility` made, expressed where it can be read.
        if (showArtwork) {
          PlayerArtwork(state.artwork, coverUrl)
        }
        PlayerReadout(state.text)
        PlayerSlider(state.slider, actions)
        TransportRow(state.transport, actions)
        UtilityRow(state.utility, actions)
      }
    }
    chapterList(chapterRows, onChapterClick)
  }
}

@Composable
private fun PlayerArtwork(
  artwork: ArtworkState,
  coverUrl: (String) -> String,
) {
  CoverImage(
    thumb = artwork.thumb.orEmpty(),
    serverConnected = artwork.serverConnected,
    coverUrl = coverUrl,
    modifier =
      Modifier
        .fillMaxWidth(0.7f)
        .aspectRatio(1f)
        .clip(RoundedCornerShape(8.dp))
        .padding(vertical = 8.dp),
  )
}

/**
 * The two-level readout.
 *
 * Both lines come from [PlayerText], the pure formatters extracted from the screen — so the wording rule
 * (§3.1 rule 3: `6h 12m left in book`, never `47:12:33/52:04:11`) is stated once and shared with
 * the View screen while both exist.
 */
@Composable
private fun PlayerReadout(text: TextState) {
  // `stringResource`, not a `LocalContext.current.getString` bridge: lint's
  // `LocalContextResourceRead` flags the latter, and rightly — a Context read is invisible to
  // Compose's resource-change tracking, so the readout would not recompose on a locale change.
  val strings = playerStrings()

  Text(
    text = text.chapterTitle,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onBackground,
    textAlign = TextAlign.Center,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
  )
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = PlayerText.chapterPosition(text.progress, strings),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = PlayerText.chapterRemaining(text.progress, strings),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = PlayerText.bookProgress(text.progress, strings),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = text.progressPercentage,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * The seek bar.
 *
 * The drag position is held in `remember` and only reported on release, so playback's own ticks
 * cannot fight the thumb mid-gesture. `state.isSliding` is what makes that expressible at all —
 * it was a plain `var` until the Compose migration, and Compose has no "write time" at which to
 * consult a field.
 */
@Composable
private fun PlayerSlider(
  slider: SliderState,
  actions: PlayerActions,
) {
  var dragValue by remember { mutableFloatStateOf(slider.value) }
  var isDragging by remember { mutableStateOf(false) }

  Slider(
    value = if (isDragging) dragValue else slider.value,
    valueRange = 0f..slider.valueTo,
    onValueChange = {
      if (!isDragging) {
        isDragging = true
        actions.onSlideStart()
      }
      dragValue = it
    },
    onValueChangeFinished = {
      isDragging = false
      // Fraction, not millis: `seekTo` takes a percentage of the current chapter.
      actions.onSeek(if (slider.valueTo > 0f) dragValue / slider.valueTo else 0f)
      actions.onSlideFinished()
    },
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun TransportRow(
  transport: TransportState,
  actions: PlayerActions,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = actions.onSkipToPrevious) {
      Icon(
        painterResource(R.drawable.ic_skip_previous_white),
        stringResource(R.string.skip_to_previous),
        tint = MaterialTheme.colorScheme.onBackground,
      )
    }
    IconButton(onClick = actions.onSkipBackwards) {
      Icon(
        painterResource(transport.jumpBackwardsIcon),
        stringResource(R.string.skip_backwards),
        tint = MaterialTheme.colorScheme.onBackground,
      )
    }

    // The spinner replaces the button in place rather than hiding it — the Fragment used
    // `INVISIBLE` rather than `GONE` for exactly this, so the row does not reflow while buffering.
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
      if (transport.isAudioLoading) {
        CircularProgressIndicator(Modifier.size(32.dp))
      } else {
        IconButton(onClick = actions.onPlayPause) {
          // `Image`, not `Icon`: these are **two-colour** drawables — an accent circle with a
          // white glyph — and `Icon` flattens both to a single `tint`, rendering the play button
          // as a bare filled circle with no triangle. Found on a device during the details
          // screen's Compose migration; every Compose test passed, since the semantics tree was
          // right and only the pixels wrong.
          Image(
            painterResource(
              if (transport.isPlaying) {
                R.drawable.ic_pause_button_large_colored
              } else {
                R.drawable.ic_play_button_large_colored
              },
            ),
            contentDescription = stringResource(R.string.pause_play_button),
            modifier = Modifier.size(48.dp),
          )
        }
      }
    }

    IconButton(onClick = actions.onSkipForwards) {
      Icon(
        painterResource(transport.jumpForwardsIcon),
        stringResource(R.string.skip_forwards),
        tint = MaterialTheme.colorScheme.onBackground,
      )
    }
    IconButton(onClick = actions.onSkipToNext) {
      Icon(
        painterResource(R.drawable.ic_skip_next_white),
        stringResource(R.string.skip_to_next),
        tint = MaterialTheme.colorScheme.onBackground,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UtilityRow(
  utility: UtilityState,
  actions: PlayerActions,
) {
  Row(
    Modifier.fillMaxWidth().padding(bottom = 8.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(onClick = actions.onChangeSpeed) {
      Text(utility.speedLabel, color = MaterialTheme.colorScheme.onBackground)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = actions.onSleepTimer) {
        Icon(
          painterResource(R.drawable.ic_sleep_timer),
          stringResource(R.string.sleep_timer),
          // Tinted rather than hidden when inactive, so the control keeps its place.
          tint =
            if (utility.isSleepTimerActive) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
      if (utility.isSleepTimerActive) {
        Text(
          utility.sleepTimerRemaining,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onBackground,
        )
      }
    }

    // Tap marks this moment, long-press lists them. A tap is the frequent action and gets the
    // plain press; browsing is rarer, so it takes the long one — §3.1 rule 2, one tray icon per
    // job.
    // A `Box` with `combinedClickable` rather than an `IconButton`: `IconButton` takes only
    // `onClick`, and the long-press is not optional here — it is the only route to the bookmark
    // list. The 48dp size keeps the touch target, which `IconButton` would have supplied.
    Box(
      modifier =
        Modifier
          .size(48.dp)
          .combinedClickable(
            onClick = actions.onAddBookmark,
            onLongClick = actions.onShowBookmarks,
            onClickLabel = stringResource(R.string.bookmark_add),
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(R.drawable.ic_bookmark_add),
        stringResource(R.string.bookmark_add),
        tint = MaterialTheme.colorScheme.onBackground,
      )
    }
  }
}

/**
 * The three format strings [PlayerText] needs, read the Compose way and passed as a resolver.
 *
 * `PlayerText`'s `StringResolver` is `(Int, Array<out Any>) -> String` — a shape that suits the
 * Fragment, which has a `Context`. Here the strings are resolved up front by `stringResource` (so
 * Compose tracks them) and the resolver only formats.
 */
@Composable
private fun playerStrings(): (Int, Array<out Any>) -> String {
  val leftInBook = stringResource(R.string.player_left_in_book)
  val chapterOf = stringResource(R.string.player_chapter_of)
  val leftInChapter = stringResource(R.string.player_left_in_chapter)
  return { resId, args ->
    val template =
      when (resId) {
        R.string.player_left_in_book -> leftInBook
        R.string.player_chapter_of -> chapterOf
        R.string.player_left_in_chapter -> leftInChapter
        else -> ""
      }
    String.format(template, *args)
  }
}
