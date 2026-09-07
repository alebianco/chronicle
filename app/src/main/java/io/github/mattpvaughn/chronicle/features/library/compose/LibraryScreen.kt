package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import androidx.compose.foundation.lazy.grid.items as gridItems

/**
 * Everything the library screen renders, as one value.
 *
 * The Fragment gated three views on `books.isEmpty()` and `isOffline` through two cached locals —
 * the pattern `CollectorCachesItsValueTest` exists to guard, because discarding a collector's
 * emission left the local on its seed and rendered "No books found" over a full library. A sealed
 * [LibraryContent] makes that unrepresentable: "no emission yet" is its own branch.
 */
internal data class LibraryUiState(
  val content: LibraryContent = LibraryContent.Loading,
  val style: ViewStyleKind = ViewStyleKind.CoverGrid,
  val isRefreshing: Boolean = false,
  val serverConnected: Boolean = true,
)

internal sealed interface LibraryContent {
  /** Nothing read yet — the `stateIn` seed, never `Loaded(emptyList())`. */
  data object Loading : LibraryContent

  data class Loaded(val books: List<Audiobook>) : LibraryContent

  /** Empty and online: a genuinely empty library. */
  data object Empty : LibraryContent

  /**
   * Empty *because* offline. A distinct state, not a flavour of [Empty] — telling a user their
   * library is empty when it is merely unreachable is the trust bug this project treats as R0.
   */
  data object OfflineEmpty : LibraryContent
}

@Composable
internal fun LibraryScreen(
  state: LibraryUiState,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
  onDisableOfflineMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // `Surface`, not a bare `Box`: MaterialTheme defines colorScheme.background but paints nothing,
  // so the window colour shows through and the text renders near-invisible.
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (val content = state.content) {
      LibraryContent.Loading -> Unit
      LibraryContent.Empty -> CenteredMessage(stringResource(R.string.no_books_found))
      LibraryContent.OfflineEmpty ->
        CenteredMessage(
          text = stringResource(R.string.no_downloaded_books_found),
          action = stringResource(R.string.disable_offline_mode) to onDisableOfflineMode,
        )
      is LibraryContent.Loaded -> BookList(content.books, state, coverUrl, onBookClick)
    }
  }
}

@Composable
private fun BookList(
  books: List<Audiobook>,
  state: LibraryUiState,
  coverUrl: (String) -> String,
  onBookClick: (Audiobook) -> Unit,
) {
  if (state.style == ViewStyleKind.CoverGrid) {
    LazyVerticalGrid(
      // `Adaptive`, not `Fixed(3)`: the Fragment used `GridLayoutManager(ctx, 3)`, which divides
      // the *available* width — 640px cells on the 1920px tablet, one cover filling the screen.
      // There is no `layout-land`, so this is also what makes landscape right.
      columns = GridCells.Adaptive(minSize = 180.dp),
      contentPadding = PaddingValues(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      // `key` is the identity the diff runs on, replacing the Fragment's hand-rolled
      // `isDifferentListById` plus `submitList(null) { submitList(real) }` scroll-to-top dance.
      gridItems(books, key = { it.id }) { book ->
        BookCard(
          book = book,
          style = state.style,
          serverConnected = state.serverConnected,
          coverUrl = coverUrl,
          onClick = { onBookClick(book) },
        )
      }
    }
  } else {
    LazyColumn(Modifier.fillMaxSize()) {
      items(books, key = { it.id }) { book ->
        BookCard(
          book = book,
          style = state.style,
          serverConnected = state.serverConnected,
          coverUrl = coverUrl,
          onClick = { onBookClick(book) },
        )
      }
    }
  }
}

@Composable
private fun CenteredMessage(
  text: String,
  action: Pair<String, () -> Unit>? = null,
) {
  Column(
    Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = text },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (action != null) {
      Button(onClick = action.second, modifier = Modifier.padding(top = 16.dp)) {
        Text(action.first)
      }
    }
  }
}
