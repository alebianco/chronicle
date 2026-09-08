package io.github.mattpvaughn.chronicle.features.settings.compose

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
import com.slack.circuit.runtime.screen.Screen
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.settings.SeriesIndexTesterViewModel
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The series-index rules tester as a Circuit screen — the **first** screen converted under
 * [decision-27], chosen because it is one of the two smallest and, per decision-25's measurement,
 * has no mechanical wiring left to remove. That makes it the honest test of what Circuit costs
 * rather than a flattering one.
 *
 * ### What Circuit actually buys here
 *
 * The `*Destination` this replaces passed two method references, `viewModel::onTitleSortChanged`
 * and `viewModel::onSampleChosen`, plus an `onNavigateUp` lambda. Adding a third interaction meant
 * adding a third parameter, and forgetting to wire one was a silent omission the compiler could not
 * see.
 *
 * [SeriesIndexTesterEvent] is a sealed hierarchy, and the presenter's `when` over it is
 * **exhaustive** — a new event is a compile error until it is handled. That is the property the
 * owner's veto of decision-25 was about, and it is the whole reason this migration is happening.
 *
 * ### What it does not change
 *
 * `SeriesIndexTesterScreen` stays a pure composable taking state and lambdas, per convention 2 —
 * only the `*Destination` layer becomes `Ui` + presenter. And the ViewModel is unchanged: Circuit
 * does not replace it, it wraps it, so the derived-state work and its tests carry over untouched.
 *
 * ### The route key
 *
 * A `data object`, since this screen takes no arguments.
 *
 * Worth recording because the obvious expectation is wrong: Circuit's `Screen` **used to be**
 * `Parcelable`, and still is at 0.31.x. At **0.38.0 it extends `CircuitSaveable`**, a marker
 * interface with no members, so no `@Parcelize` and no hand-written `Parcelable` is needed. An
 * earlier attempt here added both — and `kotlin-parcelize` silently no-ops under AGP 9 anyway
 * under AGP 9, so it failed twice for two unrelated reasons before the actual interface was read.
 *
 * That is the `0.x` risk decision-27 records, in concrete form: the API moved between minors.
 */
data object SeriesIndexTesterScreenKey : Screen

/** Everything the tester renders, plus the sink its UI posts back through. */
data class SeriesIndexTesterCircuitState(
  val ui: SeriesIndexTesterUiState,
  val eventSink: (SeriesIndexTesterEvent) -> Unit,
) : CircuitUiState

/**
 * Every interaction this screen has.
 *
 * Sealed on purpose: the presenter's `when` is exhaustive, so a fourth event cannot be added and
 * left unhandled. The `*Destination` version expressed these as three independent lambdas, where
 * omitting one compiled fine.
 */
sealed interface SeriesIndexTesterEvent : CircuitUiEvent {
  /** The user typed into the input. */
  data class TitleSortChanged(val input: String) : SeriesIndexTesterEvent

  /** The user tapped one of their own library's unparsed titles. */
  data class SampleChosen(val titleSort: String) : SeriesIndexTesterEvent

  /** The back arrow. Navigation is an event here rather than a lambda parameter. */
  data object NavigateUp : SeriesIndexTesterEvent
}

/**
 * Wraps the existing [SeriesIndexTesterViewModel] rather than replacing it.
 *
 * The ViewModel keeps its derived state — `winningRule`, `parsedPosition`, the four-source
 * `combineDistinct` — and its tests keep passing unchanged. Circuit owns *how the screen is reached
 * and how its events are typed*, not how its state is derived.
 *
 * **`collectAsState`, not `collectAsStateWithLifecycle`.** The `*Destination` used the lifecycle
 * variant, which reads `LocalLifecycleOwner` — an Android composition local that a plain JVM test
 * cannot provide, so a presenter using it throws "CompositionLocal LocalLifecycleOwner not present"
 * and is testable only under Robolectric. Circuit already scopes a presenter's composition to the
 * screen's presence on the backstack, so the lifecycle variant buys nothing here and costs the
 * testability that is the point of the migration. The ViewModel's `WhileSubscribed` still drops its
 * upstream when the presenter leaves composition.
 */
class SeriesIndexTesterPresenter(
  private val viewModel: SeriesIndexTesterViewModel,
  private val navigator: Navigator,
) : Presenter<SeriesIndexTesterCircuitState> {
  @Composable
  override fun present(): SeriesIndexTesterCircuitState {
    val state by viewModel.uiState.collectAsState()

    return SeriesIndexTesterCircuitState(ui = state) { event ->
      when (event) {
        is SeriesIndexTesterEvent.TitleSortChanged -> viewModel.onTitleSortChanged(event.input)
        is SeriesIndexTesterEvent.SampleChosen -> viewModel.onSampleChosen(event.titleSort)
        SeriesIndexTesterEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

/**
 * The Circuit `Ui`: the scaffold, and the pure screen inside it.
 *
 * A plain composable rather than a `Ui<T>` implementation, because it is invoked directly by the
 * factory below — the interface buys nothing until a screen is reached generically.
 */
@Composable
fun SeriesIndexTesterUi(
  state: SeriesIndexTesterCircuitState,
  modifier: Modifier = Modifier,
) {
  ChronicleScaffold(
    title = stringResource(R.string.series_rules_screen_title),
    onNavigateUp = { state.eventSink(SeriesIndexTesterEvent.NavigateUp) },
    modifier = modifier,
  ) {
    SeriesIndexTesterScreen(
      state = state.ui,
      onTitleSortChanged = { state.eventSink(SeriesIndexTesterEvent.TitleSortChanged(it)) },
      onSampleChosen = { state.eventSink(SeriesIndexTesterEvent.SampleChosen(it)) },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
