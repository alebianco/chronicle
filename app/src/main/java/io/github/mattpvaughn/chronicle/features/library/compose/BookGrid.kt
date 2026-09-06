package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook

/**
 * A plain grid of books with an empty message (cu-201).
 *
 * Shared by the browse-facet and collection-detail screens, which are both thin
 * `AudiobookAdapter` users: a grid, an empty message, and a tap. They have no offline branch and
 * no view-style preference of their own, so they need less state than the library — giving them
 * `LibraryUiState` would mean carrying fields they never set.
 *
 * [books] being `null` means "not read yet" and renders nothing, which is the same distinction
 * `LibraryContent.Loading` draws: an empty list and an unread one must not look alike, or the
 * empty message flashes before the first emission (cu-68).
 */
@Composable
internal fun BookGrid(
  books: List<Audiobook>?,
  emptyMessage: String,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
  style: ViewStyleKind = ViewStyleKind.CoverGrid,
) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when {
      books == null -> Unit
      books.isEmpty() ->
        Column(
          Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = emptyMessage },
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      else ->
        LazyVerticalGrid(
          // Adaptive, never Fixed: a fixed count divides the available width, which on a 1920px
          // tablet gives one cover per screen (cu-187).
          columns = GridCells.Adaptive(minSize = 180.dp),
          contentPadding = PaddingValues(8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(books, key = { it.id }) { book ->
            BookCard(
              book = book,
              style = style,
              serverConnected = serverConnected,
              coverUrl = coverUrl,
              onClick = { onBookClick(book) },
            )
          }
        }
    }
  }
}
