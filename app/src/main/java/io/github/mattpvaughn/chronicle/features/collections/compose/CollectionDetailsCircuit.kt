package io.github.mattpvaughn.chronicle.features.collections.compose

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
import io.github.mattpvaughn.chronicle.features.collections.CollectionDetailsViewModel
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The books in one collection.
 *
 * The title is collected rather than carried on the screen key: it comes from the collection row,
 * which the ViewModel is already reading. The Fragment set it with a `collectWhileStarted` for the
 * same reason — and set its navigation click listener **twice**, which is one of the things a
 * declarative rewrite simply cannot express.
 */
internal data class CollectionDetailsCircuitState(
  val title: String,
  val books: List<Audiobook>,
  val style: ViewStyleKind,
  val isConnected: Boolean,
  val eventSink: (CollectionDetailsEvent) -> Unit,
) : CircuitUiState

internal sealed interface CollectionDetailsEvent : CircuitUiEvent {
  data class BookOpened(val book: Audiobook) : CollectionDetailsEvent

  data object NavigateUp : CollectionDetailsEvent
}

internal class CollectionDetailsPresenter(
  private val viewModel: @Composable () -> CollectionDetailsViewModel,
  private val plexConfig: PlexConfig,
  private val navigator: Navigator,
) : Presenter<CollectionDetailsCircuitState> {
  @Composable
  override fun present(): CollectionDetailsCircuitState {
    val viewModel = viewModel()
    val books by viewModel.booksInCollection.collectAsState()
    val style by viewModel.viewStyle.collectAsState(initial = null)
    val isConnected by plexConfig.isConnected.collectAsState()
    val collection by viewModel.title.collectAsState(initial = null)

    return CollectionDetailsCircuitState(
      title = collection?.title.orEmpty(),
      books = books,
      style = style?.let { ViewStyleKind.of(it) } ?: ViewStyleKind.CoverGrid,
      isConnected = isConnected,
    ) { event ->
      when (event) {
        is CollectionDetailsEvent.BookOpened -> navigator.goTo(BookDetailsScreenKey(event.book.id))
        CollectionDetailsEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

@Composable
internal fun CollectionDetailsUi(
  state: CollectionDetailsCircuitState,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  ChronicleScaffold(
    title = state.title,
    onNavigateUp = { state.eventSink(CollectionDetailsEvent.NavigateUp) },
    modifier = modifier,
  ) {
    BookGrid(
      books = state.books,
      emptyMessage = stringResource(R.string.no_books_found),
      serverConnected = state.isConnected,
      coverUrl = plexConfig::toServerString,
      onBookClick = { state.eventSink(CollectionDetailsEvent.BookOpened(it)) },
      style = state.style,
      modifier = Modifier.fillMaxSize(),
    )
  }
}
