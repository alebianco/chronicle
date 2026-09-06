package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme

/** What the collapsed mini player shows. Framework-free so it can be built in a test or preview. */
data class MiniPlayerState(
  val bookTitle: String,
  val chapterTitle: String,
  val artworkUrl: String,
  val isPlaying: Boolean,
  val isLoading: Boolean,
)

/** The height of the collapsed handle — `@dimen/bottom_sheet_handle_height`. */
val MiniPlayerHeight = 72.dp

/**
 * The collapsed currently-playing handle (cu-206).
 *
 * Replaces `currently_playing_handle` in `activity_main.xml`, which was a `ConstraintLayout` bound
 * field-by-field from `MainActivity`.
 *
 * ### The per-tick write problem is now structural
 *
 * `ProgressUpdater` republishes the playing book **once a second**, so this content re-emits at
 * tick rate with an identical title and artwork. The View version needed two hand-maintained
 * guards for that — `setTextIfChanged`, and a pair of `boundBookTitle`/`boundBookThumb` fields
 * compared before every bind (cu-117, where the measured cost was 285 jiffies/10s against 1 while
 * paused). Compose skips a recomposition whose inputs are `equals`, so passing a
 * [MiniPlayerState] `data class` gets the same result from the framework: an unchanged tick
 * recomposes nothing, and there is no mirror state to drift.
 */
@Composable
fun MiniPlayer(
  state: MiniPlayerState,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
  onPlayPauseClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .height(MiniPlayerHeight)
        .background(ChronicleColors.PrimaryDark)
        .clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AsyncImage(
      model = coverUrl(state.artworkUrl),
      // The ImageView carried the book title as its contentDescription; keep that, since the
      // title text beside it is truncated and this is what a screen reader announces.
      contentDescription = state.bookTitle,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(MiniPlayerHeight),
    )
    Column(
      modifier =
        Modifier
          .weight(1f)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = state.chapterTitle,
        style = MaterialTheme.typography.bodyMedium,
        color = ChronicleColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = state.bookTitle,
        style = MaterialTheme.typography.bodySmall,
        color = ChronicleColors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(
      modifier = Modifier.size(MiniPlayerHeight),
      contentAlignment = Alignment.Center,
    ) {
      if (state.isLoading) {
        // Spinner *instead of* the icon, same as the expanded player (cu-95). In the View version
        // the button went INVISIBLE rather than GONE so the row would not reflow; here both sit in
        // a fixed-size Box, so the layout is stable either way.
        val buffering = stringResource(R.string.buffering)
        CircularProgressIndicator(
          modifier =
            Modifier
              .size(24.dp)
              .semantics { contentDescription = buffering },
          color = ChronicleColors.Accent,
        )
      } else {
        Icon(
          painter =
            painterResource(
              // A button shows the action a tap performs, not the current state: while playing it
              // must offer pause. The drawables are *state*-named, which is how this got inverted
              // once during the cu-58 conversion.
              if (state.isPlaying) {
                R.drawable.ic_notification_icon_paused
              } else {
                R.drawable.ic_notification_icon_playing
              },
            ),
          contentDescription = stringResource(R.string.pause_play_button),
          tint = ChronicleColors.TextPrimary,
          modifier =
            Modifier
              .size(32.dp)
              .clickable(onClick = onPlayPauseClick),
        )
      }
    }
  }
}

@Preview
@Composable
private fun MiniPlayerPreview() {
  ChronicleTheme {
    MiniPlayer(
      state =
        MiniPlayerState(
          bookTitle = "The Wisdom of Crowds",
          chapterTitle = "Chapter 12: A Reckoning",
          artworkUrl = "",
          isPlaying = true,
          isLoading = false,
        ),
      coverUrl = { it },
      onClick = {},
      onPlayPauseClick = {},
    )
  }
}
