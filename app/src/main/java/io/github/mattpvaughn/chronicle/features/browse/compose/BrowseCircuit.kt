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
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.features.browse.BrowseViewModel
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * Browse the library by author, narrator or series.
 *
 * Replaces `BrowseDestination`, which passed `onNavigateUp` and `onFacetClick` as lambdas from the
 * nav graph. Both are events here, so the presenter's `when` is exhaustive and a third interaction
 * cannot be added and left unwired.
 */
data class BrowseCircuitState(
  val ui: BrowseUiState,
  val eventSink: (BrowseEvent) -> Unit,
) : CircuitUiState

sealed interface BrowseEvent : CircuitUiEvent {
  /** The user switched between authors, narrators and series. */
  data class FacetKindSelected(val kind: FacetKind) : BrowseEvent

  /** The user tapped one facet — an author, a narrator, a series. */
  data class FacetOpened(val facet: Facet) : BrowseEvent

  data object NavigateUp : BrowseEvent
}

class BrowsePresenter(
  private val viewModel: @Composable () -> BrowseViewModel,
  private val navigator: Navigator,
) : Presenter<BrowseCircuitState> {
  @Composable
  override fun present(): BrowseCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    return BrowseCircuitState(ui = state) { event ->
      when (event) {
        is BrowseEvent.FacetKindSelected -> viewModel.showFacet(event.kind)
        // The facet's *kind* comes from the state rather than the event: which tab is showing is
        // the presenter's business, and the row the user tapped only knows its own value.
        is BrowseEvent.FacetOpened ->
          navigator.goTo(FacetBooksScreenKey(state.selected, event.facet.value))
        BrowseEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

@Composable
fun BrowseUi(
  state: BrowseCircuitState,
  modifier: Modifier = Modifier,
) {
  ChronicleScaffold(
    title = stringResource(R.string.browse_title),
    onNavigateUp = { state.eventSink(BrowseEvent.NavigateUp) },
    modifier = modifier,
  ) {
    BrowseScreen(
      state = state.ui,
      onSelectFacet = { state.eventSink(BrowseEvent.FacetKindSelected(it)) },
      onFacetClick = { state.eventSink(BrowseEvent.FacetOpened(it)) },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
