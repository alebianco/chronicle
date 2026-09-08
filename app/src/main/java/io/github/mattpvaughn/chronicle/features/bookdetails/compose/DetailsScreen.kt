package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.ChapterRow
import io.github.mattpvaughn.chronicle.features.bookdetails.DetailsProgressText
import io.github.mattpvaughn.chronicle.features.currentlyplaying.StringResolver
import io.github.mattpvaughn.chronicle.views.compose.CoverImage

/** What the details header can do. One object rather than seven lambdas. */
data class DetailsActions(
  val onPlayPause: () -> Unit = {},
  val onDownload: () -> Unit = {},
  val onToggleSummary: () -> Unit = {},
  val onSeriesClick: () -> Unit = {},
)

/**
 * The book-details header.
 *
 * Stateless, so the whole thing is reachable from `createComposeRule()` — the View version had no
 * rendering test at all beyond a Robolectric layout check that measured four TextViews for
 * overlap. In a `Column` that overlap is unrepresentable, which is why that test retires
 * rather than being ported.
 */
@Composable
fun DetailsScreen(
  state: DetailsUiState,
  actions: DetailsActions,
  coverUrl: (String) -> String,
  modifier: Modifier = Modifier,
  chapterRows: List<ChapterRow> = emptyList(),
  onChapterClick: (Chapter) -> Unit = {},
) {
  // One `LazyColumn` for the header *and* the chapters, so they scroll as a single list. The View
  // version arranged that with `CollapsingToolbarLayout` plus
  // `appbar_scrolling_view_behavior` on a separate RecyclerView — two scroll containers
  // coordinating by hand, which is the pairing the bug came out of.
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    item(key = "header") { DetailsHeader(state, actions, coverUrl) }
    chapterList(chapterRows, onChapterClick)
  }
}

@Composable
private fun DetailsHeader(
  state: DetailsUiState,
  actions: DetailsActions,
  coverUrl: (String) -> String,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
  ) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      CoverImage(
        thumb = state.book.thumb.orEmpty(),
        serverConnected = state.book.serverConnected,
        coverUrl = coverUrl,
        modifier = Modifier.size(160.dp).clip(RoundedCornerShape(4.dp)),
      )
      Column(Modifier.padding(start = 16.dp).weight(1f)) {
        BookText(state.book, actions.onSeriesClick)
        ProgressRow(state.progress)
      }
    }

    ControlRow(state, actions)
    Summary(state.summary, actions)
  }
}

@Composable
private fun BookText(
  book: BookHeader,
  onSeriesClick: () -> Unit,
) {
  Text(
    text = book.title,
    style = MaterialTheme.typography.titleLarge,
    color = MaterialTheme.colorScheme.onBackground,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
  )
  Text(
    text = book.author,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp),
  )
  // A `Column` cannot overlap its children, so the 17px overlap between these four rows is
  // unrepresentable rather than re-guarded — `BookDetailsMetadataLayoutTest` retires with it.
  book.narrator?.let {
    Text(
      text = stringResource(R.string.book_narrated_by, it),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
  book.series?.let {
    Text(
      text = it,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
      // Tappable: it navigates into the browse facet for the series. `onClickLabel`
      // rather than a bare `clickable`, because a screen reader must announce what the tap does —
      // the text alone reads as a label.
      modifier =
        Modifier
          .padding(top = 2.dp)
          .clickable(onClickLabel = seriesBrowseLabel(book)) { onSeriesClick() },
    )
  }
}

/** "Browse the <series> series" — spoken as an action, not a label. */
@Composable
private fun seriesBrowseLabel(book: BookHeader): String = stringResource(R.string.book_series_browse, book.series.orEmpty())

/**
 * The progress line.
 *
 * The wording is [DetailsProgressText]'s and the strings are `strings.xml`'s; this only resolves
 * them and lays the row out. It used to render a pre-formatted string built in the ViewModel —
 * the raw `h:mm:ss/h:mm:ss` pair §3.1 rule 3 bans — now replaced with three states:
 * the book's length before it is started, `6h 12m left` once it is, and `Finished` at the end.
 */
@Composable
private fun ProgressRow(progress: ProgressLine) {
  val strings = detailsProgressStrings()
  Row(
    Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text =
        DetailsProgressText.progress(
          state = progress.state,
          durationMillis = progress.durationMillis,
          strings = strings,
        ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = progress.percentage,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * The three format strings [DetailsProgressText] needs, read the Compose way and passed as a
 * resolver — the same shape `PlayerScreen.playerStrings()` uses, and for the same reason: resolving
 * them here lets Compose track them, so the row recomposes on a locale or configuration change
 * while the formatter itself stays a pure function with no `Context`.
 */
@Composable
private fun detailsProgressStrings(): StringResolver {
  val totalLength = stringResource(R.string.details_total_length)
  val left = stringResource(R.string.details_left)
  val finished = stringResource(R.string.details_finished)
  return { resId, args ->
    val template =
      when (resId) {
        R.string.details_total_length -> totalLength
        R.string.details_left -> left
        R.string.details_finished -> finished
        else -> ""
      }
    if (args.isEmpty()) template else String.format(template, *args)
  }
}

@Composable
private fun ControlRow(
  state: DetailsUiState,
  actions: DetailsActions,
) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Play, with the buffering spinner replacing it *in place* — the View version used INVISIBLE
    // rather than GONE so the row would not reflow while loading.
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
      if (state.playback.isAudioLoading) {
        CircularProgressIndicator(Modifier.size(28.dp))
      } else {
        IconButton(onClick = actions.onPlayPause) {
          // `Image`, not `Icon`: these drawables are **two-colour** — an accent circle with a
          // white glyph — and `Icon` flattens everything to a single `tint`, which renders the
          // play button as a bare filled circle with no triangle. Seen on a device; every test
          // passed, since the semantics tree was right and only the pixels were wrong.
          Image(
            painterResource(
              if (state.playback.isPlaying) {
                R.drawable.ic_pause_button_large_colored
              } else {
                R.drawable.ic_play_button_large_colored
              },
            ),
            contentDescription = stringResource(R.string.pause_play_button),
            modifier = Modifier.size(40.dp),
          )
        }
      }
    }

    DownloadControl(state.download, actions)
  }
}

/**
 * The download control.
 *
 * One `when` over a sealed [DownloadState] where the Fragment read four separate flows — icon,
 * spoken label, tint and status — each with its own `null` branch. `CacheLabelPairingTest` parsed
 * two of those `when` blocks out of the ViewModel's *source text* to check they covered the same
 * states; here the compiler guarantees it, which is strictly stronger.
 */
@Composable
private fun DownloadControl(
  download: DownloadState,
  actions: DetailsActions,
) {
  Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
    if (download is DownloadState.Caching) {
      // The spinner sits *over* the icon, which keeps its slot.
      CircularProgressIndicator(Modifier.size(28.dp))
    }
    IconButton(
      onClick = actions.onDownload,
      // Inert until the status resolves — the Fragment expressed this as `isEnabled = status
      // != null`, which is the same thing said in a way the type could not check.
      enabled = download !is DownloadState.Unknown,
    ) {
      Icon(
        painterResource(
          when (download) {
            is DownloadState.Cached -> R.drawable.ic_cloud_done_white
            else -> R.drawable.ic_cloud_download_white
          },
        ),
        stringResource(
          when (download) {
            is DownloadState.Cached -> R.string.download_remove
            is DownloadState.Caching -> R.string.download_cancel
            else -> R.string.download
          },
        ),
        // Was `ColorStateList.valueOf(tint)` on a **resource id** rather than a colour value, so
        // the icon was tinted with the integer value of `R.color.icon` (the details screen's
        // Compose migration found it; the Compose rewrite forces a real `Color` and the bug
        // cannot be expressed).
        tint =
          if (download is DownloadState.Cached) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onBackground
          },
      )
    }
  }
}

@Composable
private fun Summary(
  summary: SummaryState,
  actions: DetailsActions,
) {
  if (!summary.isShown) return
  Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
    Text(
      text = summary.text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = summary.linesShown,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = stringResource(if (summary.isExpanded) R.string.less else R.string.more),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 4.dp).clickable { actions.onToggleSummary() },
    )
  }
}
