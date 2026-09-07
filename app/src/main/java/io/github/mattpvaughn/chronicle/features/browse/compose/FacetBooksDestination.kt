package io.github.mattpvaughn.chronicle.features.browse.compose

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
import io.github.mattpvaughn.chronicle.features.browse.FacetBooksViewModel
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The books under one facet value, as a navigation destination.
 *
 * The facet name is the toolbar title, so it is read from the ViewModel rather than the route: the
 * ViewModel already resolves both arguments out of `SavedStateHandle`, and reading the
 * route here as well would be the same fact in two places.
 */
@Composable
fun FacetBooksDestination(
  title: String,
  plexConfig: PlexConfig,
  onNavigateUp: () -> Unit,
  onBookClick: (Audiobook) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: FacetBooksViewModel = hiltViewModel(),
) {
  val books by viewModel.books.collectAsStateWithLifecycle(initialValue = null)
  val style by viewModel.viewStyle.collectAsStateWithLifecycle(initialValue = null)
  val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

  ChronicleScaffold(title = title, onNavigateUp = onNavigateUp, modifier = modifier) {
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
