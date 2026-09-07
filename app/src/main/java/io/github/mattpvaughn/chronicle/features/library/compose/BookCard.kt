package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookProgressState
import io.github.mattpvaughn.chronicle.data.model.progressBarMax
import io.github.mattpvaughn.chronicle.data.model.progressState
import io.github.mattpvaughn.chronicle.views.compose.CoverImage

/**
 * One book, in whichever of the three view styles is chosen (cu-201).
 *
 * Written *alongside* `AudiobookAdapter` rather than replacing it: that adapter is shared by
 * Library, Home, FacetBooks and CollectionDetails, and the last two are still Views. Forking the
 * rendering is the cost of migrating screens one at a time — what must **not** fork is the
 * *decision*, which is why the progress indicator reads `Audiobook.progressState()` (cu-198's
 * extraction) rather than re-implementing cu-86's three-state rule.
 *
 * Three styles, not a two-way `isGrid` boolean: `VIEW_STYLE_DETAILS_LIST` exists and collapsing to
 * a boolean would silently drop it.
 */
@Composable
internal fun BookCard(
  book: Audiobook,
  style: ViewStyleKind,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  when (style) {
    ViewStyleKind.CoverGrid -> GridCard(book, serverConnected, coverUrl, onClick, modifier)
    ViewStyleKind.TextOnly -> TextRow(book, onClick, modifier)
    ViewStyleKind.Details -> DetailsRow(book, serverConnected, coverUrl, onClick, modifier)
  }
}

@Composable
private fun GridCard(
  book: Audiobook,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  Column(modifier.fillMaxWidth().clickable(onClickLabel = book.title, onClick = onClick).padding(4.dp)) {
    Box {
      Cover(book, serverConnected, coverUrl, Modifier.fillMaxWidth().aspectRatio(1f))
      ProgressOverlay(book, Modifier.align(Alignment.BottomCenter))
    }
    Title(book.title, Modifier.padding(top = 4.dp))
    Author(book.author)
  }
}

@Composable
private fun TextRow(
  book: Audiobook,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  Column(
    modifier
      .fillMaxWidth()
      .clickable(onClickLabel = book.title, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Title(book.title)
    Author(book.author)
  }
}

@Composable
private fun DetailsRow(
  book: Audiobook,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onClick: () -> Unit,
  modifier: Modifier,
) {
  Row(
    modifier
      .fillMaxWidth()
      .clickable(onClickLabel = book.title, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Box {
      Cover(book, serverConnected, coverUrl, Modifier.size(64.dp))
      ProgressOverlay(book, Modifier.align(Alignment.BottomCenter))
    }
    Column(Modifier.padding(start = 12.dp)) {
      Title(book.title)
      Author(book.author)
    }
  }
}

@Composable
private fun Cover(
  book: Audiobook,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  modifier: Modifier,
) {
  // `CoverImage`, not a bare `AsyncImage`: it carries the placeholder for the offline, no-artwork
  // and failed-decode cases, which this call site used to leave as a hole in the layout (cu-207).
  CoverImage(
    thumb = book.thumb,
    serverConnected = serverConnected,
    coverUrl = coverUrl,
    modifier = modifier.clip(RoundedCornerShape(4.dp)),
  )
}

/**
 * The progress bar and the unstarted marker.
 *
 * Reads `progressState()` — the shared decision extracted in cu-198 — so this and
 * cu-86's rule is stated once in `progressState()`, so no renderer can drift from it.
 */
@Composable
private fun ProgressOverlay(
  book: Audiobook,
  modifier: Modifier,
) {
  when (val state = book.progressState()) {
    is BookProgressState.Unstarted -> Unit
    is BookProgressState.Completed ->
      LinearProgressIndicator(
        progress = { 1f },
        modifier = modifier.fillMaxWidth().height(3.dp),
      )
    is BookProgressState.InProgress ->
      LinearProgressIndicator(
        progress = { state.progressMillis.toFloat() / book.progressBarMax() },
        modifier = modifier.fillMaxWidth().height(3.dp),
      )
  }
}

@Composable
private fun Title(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onBackground,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
private fun Author(author: String) {
  Text(
    text = author,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}
