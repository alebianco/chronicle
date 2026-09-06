package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/**
 * The bookmarks of the book being played (cu-22, migrated in cu-203).
 *
 * Replaces `BookmarkListAdapter` and the two `isVisible` decisions its host made. The empty state
 * is a branch rather than a second view, so "list showing" and "empty message showing" cannot both
 * be true — which is what those two writes had to be kept in step by hand.
 */
@Composable
fun BookmarkList(
  bookmarks: List<Bookmark>,
  onJump: (Bookmark) -> Unit,
  onEdit: (Bookmark) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxWidth()) {
    val padding = dimensionResource(R.dimen.screen_horizontal_padding)

    if (bookmarks.isEmpty()) {
      Text(
        text = stringResource(R.string.bookmark_none),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(padding),
      )
      return@Surface
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
      // Keyed by id: a note edited in place must rebind rather than be replaced, or the row
      // animates as a removal plus an insertion.
      items(bookmarks, key = { it.id }) { bookmark ->
        BookmarkRow(bookmark, onJump = { onJump(bookmark) }, onEdit = { onEdit(bookmark) })
      }
    }
  }
}

@Composable
private fun BookmarkRow(
  bookmark: Bookmark,
  onJump: () -> Unit,
  onEdit: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onJump)
        .padding(
          horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
          vertical = 12.dp,
        ),
  ) {
    // The accent bookmark glyph, which the XML row carried and a straight port would have lost —
    // the class of omission cu-198 shipped with the player's bookmark button.
    Icon(
      painter = painterResource(R.drawable.ic_bookmark),
      contentDescription = stringResource(R.string.bookmarks_title),
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
      // `formatPrecisePosition`, not `DateUtils` — a position inside a book is shown the same way
      // the player shows one (cu-19).
      Text(
        text = formatPrecisePosition(bookmark.position.millis),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      // Only when there is one: an empty note would otherwise take a line of its own.
      if (bookmark.hasNote) {
        Text(
          text = bookmark.note,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    IconButton(onClick = onEdit) {
      Icon(
        painter = painterResource(R.drawable.ic_edit),
        contentDescription = stringResource(R.string.bookmark_edit_note),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}
