package io.github.mattpvaughn.chronicle.features.browse.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.browse.FacetBooksViewModel
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The books under one facet value — a narrator, a genre, a series.
 *
 * ### Where the title comes from
 *
 * Straight off the screen key. Under Navigation Compose the facet value was the one argument read
 * back **out of the route** rather than from the ViewModel, because the toolbar needed it and the
 * ViewModel kept it private — so the nav graph decoded the path segment itself with `decodeArg`.
 * That decode existed only because the value had been percent-encoded to survive being a URL path
 * segment in the first place. The screen key carries the string as a string, so both halves of that
 * round trip are gone.
 */
internal data class FacetBooksCircuitState(
  val title: String,
  val books: List<Audiobook>?,
  val style: ViewStyleKind,
  val isConnected: Boolean,
  val eventSink: (FacetBooksEvent) -> Unit,
) : CircuitUiState

internal sealed interface FacetBooksEvent : CircuitUiEvent {
  data class BookOpened(val book: Audiobook) : FacetBooksEvent

  data object NavigateUp : FacetBooksEvent
}

internal class FacetBooksPresenter(
  private val viewModel: @Composable () -> FacetBooksViewModel,
  private val plexConfig: PlexConfig,
  private val navigator: Navigator,
  private val title: String,
) : Presenter<FacetBooksCircuitState> {
  @Composable
  override fun present(): FacetBooksCircuitState {
    val viewModel = viewModel()
    val books by viewModel.books.collectAsState(initial = null)
    val style by viewModel.viewStyle.collectAsState(initial = null)
    val isConnected by plexConfig.isConnected.collectAsState()

    return FacetBooksCircuitState(
      title = title,
      books = books,
      style = style?.let { ViewStyleKind.of(it) } ?: ViewStyleKind.CoverGrid,
      isConnected = isConnected,
    ) { event ->
      when (event) {
        is FacetBooksEvent.BookOpened -> navigator.goTo(BookDetailsScreenKey(event.book.id))
        FacetBooksEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

@Composable
internal fun FacetBooksUi(
  state: FacetBooksCircuitState,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  ChronicleScaffold(
    title = state.title,
    onNavigateUp = { state.eventSink(FacetBooksEvent.NavigateUp) },
    modifier = modifier,
  ) {
    BookGrid(
      books = state.books,
      emptyMessage = stringResource(R.string.no_books_found),
      serverConnected = state.isConnected,
      coverUrl = plexConfig::toServerString,
      onBookClick = { state.eventSink(FacetBooksEvent.BookOpened(it)) },
      style = state.style,
      modifier = Modifier.fillMaxSize(),
    )
  }
}
