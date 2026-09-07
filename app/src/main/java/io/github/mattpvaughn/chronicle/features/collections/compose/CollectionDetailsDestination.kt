package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.collections.CollectionDetailsViewModel
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The books in one collection, as a navigation destination.
 *
 * The title is collected rather than passed in the route: it comes from the collection row, which
 * the ViewModel is already reading. The Fragment set it with a `collectWhileStarted` for the same
 * reason — and set its navigation click listener **twice**, which is one of the things a
 * declarative rewrite simply cannot express.
 */
@Composable
fun CollectionDetailsDestination(
  plexConfig: PlexConfig,
  onNavigateUp: () -> Unit,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CollectionDetailsViewModel = hiltViewModel(),
) {
  val books by viewModel.booksInCollection.collectAsStateWithLifecycle()
  val style by viewModel.viewStyle.collectAsStateWithLifecycle(initialValue = null)
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()
  val collection by viewModel.title.collectAsStateWithLifecycle(initialValue = null)

  ChronicleScaffold(
    title = collection?.title.orEmpty(),
    onNavigateUp = onNavigateUp,
    modifier = modifier,
  ) {
    BookGrid(
      books = books,
      emptyMessage = stringResource(R.string.no_books_found),
      serverConnected = isConnected,
      coverUrl = plexConfig::toServerString,
      onBookClick = onBookClick,
      style = style?.let { ViewStyleKind.of(it) } ?: ViewStyleKind.CoverGrid,
      modifier = Modifier.fillMaxSize(),
    )
  }
}
