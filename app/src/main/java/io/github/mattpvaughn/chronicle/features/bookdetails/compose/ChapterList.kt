package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.ChapterRow
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/**
 * The chapter list, shared by the player and the details screen.
 *
 * Replaces `ChapterListAdapter`, which both screens constructed. Emitted into a caller's
 * `LazyColumn` rather than owning its own, so the details screen can scroll its header and the
 * chapter list as one list — which is what the old `CollapsingToolbarLayout` +
 * `appbar_scrolling_view_behavior` pairing was arranging by hand.
 *
 * The grouping decision lives in `chapterRows`, not here: two renderers must not disagree about
 * where a disc header goes, and a pure function is testable without a view (the precedent).
 */
fun LazyListScope.chapterList(
  rows: List<ChapterRow>,
  onChapterClick: (Chapter) -> Unit,
) {
  rows.forEach { row ->
    when (row) {
      is ChapterRow.DiscHeader ->
        item(key = "disc-${row.discNumber}") {
          Text(
            text = stringResource(R.string.disc_number, row.discNumber.toString()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
          )
        }
      is ChapterRow.ChapterItem ->
        // The key carries the chapter id as well as the track/disc/index triple. A chapter that
        // spans a track boundary legitimately appears on **both** tracks, so the triple
        // alone is not unique and `LazyColumn` throws on a duplicate key.
        item(key = "chapter-${row.chapter.trackId}-${row.chapter.discNumber}-${row.chapter.index}-${row.chapter.id}") {
          ChapterRowItem(row, onChapterClick)
        }
    }
  }
}

@Composable
private fun ChapterRowItem(
  row: ChapterRow.ChapterItem,
  onChapterClick: (Chapter) -> Unit,
) {
  // The active chapter is tinted, not marked with an icon — the View version did the same, and an
  // icon would need its own accessible label for information the title already carries.
  val color =
    if (row.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground

  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = row.chapter.title) { onChapterClick(row.chapter) }
      .padding(horizontal = 24.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      // Plex's chapter index is already 1-based (verified against the fixture), so this is the
      // number as given — adding one displayed the first chapter as "02".
      text = "%02d".format(row.chapter.index),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 16.dp),
    )
    Text(
      text = row.chapter.title,
      style = MaterialTheme.typography.bodyMedium,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      // A chapter's length is a duration inside a book, so it keeps its clock form — but through
      // the shared formatter, not `DateUtils`, which pads to `0:01:15` at the hour.
      text =
        formatPrecisePosition(
          row.chapter.bookEndTimeOffset.millis - row.chapter.bookStartTimeOffset.millis,
        ),
      style = MaterialTheme.typography.bodySmall,
      color = color,
      modifier = Modifier.padding(start = 16.dp),
    )
  }
}
