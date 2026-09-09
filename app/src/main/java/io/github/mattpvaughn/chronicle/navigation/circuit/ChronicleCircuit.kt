package io.github.mattpvaughn.chronicle.navigation.circuit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.runtime.ui.ui
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsViewModel
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsCircuitState
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsPresenter
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsUi
import io.github.mattpvaughn.chronicle.features.browse.FacetBooksViewModel
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseCircuitState
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowsePresenter
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseUi
import io.github.mattpvaughn.chronicle.features.browse.compose.FacetBooksCircuitState
import io.github.mattpvaughn.chronicle.features.browse.compose.FacetBooksPresenter
import io.github.mattpvaughn.chronicle.features.browse.compose.FacetBooksUi
import io.github.mattpvaughn.chronicle.features.collections.CollectionDetailsViewModel
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionDetailsCircuitState
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionDetailsPresenter
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionDetailsUi
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsCircuitState
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsPresenter
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsUi
import io.github.mattpvaughn.chronicle.features.home.compose.HomeCircuitState
import io.github.mattpvaughn.chronicle.features.home.compose.HomePresenter
import io.github.mattpvaughn.chronicle.features.home.compose.HomeUi
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryCircuitState
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryPresenter
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryUi
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseLibraryUi
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseServerUi
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseUserUi
import io.github.mattpvaughn.chronicle.features.login.compose.LoginUi
import io.github.mattpvaughn.chronicle.features.settings.compose.LicensesCircuitState
import io.github.mattpvaughn.chronicle.features.settings.compose.LicensesPresenter
import io.github.mattpvaughn.chronicle.features.settings.compose.LicensesUi
import io.github.mattpvaughn.chronicle.features.settings.compose.SeriesIndexTesterCircuitState
import io.github.mattpvaughn.chronicle.features.settings.compose.SeriesIndexTesterPresenter
import io.github.mattpvaughn.chronicle.features.settings.compose.SeriesIndexTesterUi
import io.github.mattpvaughn.chronicle.features.settings.compose.SettingsCircuitState
import io.github.mattpvaughn.chronicle.features.settings.compose.SettingsPresenter
import io.github.mattpvaughn.chronicle.features.settings.compose.SettingsUi
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.BrowseScreenKey
import io.github.mattpvaughn.chronicle.navigation.ChooseLibraryScreenKey
import io.github.mattpvaughn.chronicle.navigation.ChooseServerScreenKey
import io.github.mattpvaughn.chronicle.navigation.ChooseUserScreenKey
import io.github.mattpvaughn.chronicle.navigation.CollectionDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.CollectionsScreenKey
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.navigation.HomeScreenKey
import io.github.mattpvaughn.chronicle.navigation.LibraryScreenKey
import io.github.mattpvaughn.chronicle.navigation.LicensesScreenKey
import io.github.mattpvaughn.chronicle.navigation.LoginScreenKey
import io.github.mattpvaughn.chronicle.navigation.SeriesIndexTesterScreenKey
import io.github.mattpvaughn.chronicle.navigation.SettingsScreenKey

/**
 * The app's navigation graph: every screen key, its presenter and its UI.
 *
 * This is what `ChronicleNavHost` was, and what `Navigator` was before that. Circuit owns routing
 * now (decision-27), so there is no `NavHost`, no route strings and no `navArgument` declarations —
 * a screen key *is* the route and *is* its arguments.
 *
 * ### Why one `Circuit` rather than a factory per feature
 *
 * Circuit's `Presenter.Factory` is designed to be composed from many, each claiming the screens it
 * knows. That is worth it when features are separate Gradle modules; this app is one module, and
 * splitting thirteen screens across thirteen factories would mean thirteen files to check when a
 * screen renders "unavailable content" instead of one. The `when` here is the whole graph on one
 * page, which is the property `ChronicleNavHost` had and worth keeping.
 *
 * ### How a screen gets its ViewModel
 *
 * Through [recordViewModel], not `hiltViewModel()` directly.
 *
 * `NavigableCircuitContent` provides a `LocalViewModelStoreOwner` **scoped to each back-stack
 * record**, which is the right lifetime — the same per-screen one a `composable {}` entry gave, so
 * a ViewModel is cleared when its screen is popped rather than when the Activity finishes. But that
 * owner does not implement `HasDefaultViewModelProviderFactory`, and `hiltViewModel()` reads
 * exactly that to find Hilt's factory: without it the call silently falls through to the default
 * factory and throws `Cannot create an instance of class …ViewModel` **at runtime, on launch**.
 * [recordViewModel] keeps the record's store and borrows the Activity's factory.
 *
 * The three argument-carrying screens go one step further and use Hilt's assisted injection, since
 * the record owner provides no `SavedStateRegistryOwner` either — so a `SavedStateHandle` reached
 * this way is **empty**. The value comes off the screen key and through `creationCallback` instead,
 * which is a compile-checked path rather than a string key that silently reads null.
 *
 * Carries `@UnstableApi` because the Cast SDK's opt-in reaches here through `DetailsUi`. Under
 * Navigation Compose it carried on into `MainActivity.onCreate`; the annotation stops here now.
 */
@UnstableApi
@Composable
fun rememberChronicleCircuit(
  prefsRepo: PrefsRepo,
  plexConfig: PlexConfig,
): Circuit =
  Circuit.Builder()
    .addPresenterFactory { screen, navigator, _ ->
      presenterFor(screen, navigator, prefsRepo, plexConfig)
    }
    .addUiFactory { screen, _ -> uiFor(screen, prefsRepo, plexConfig) }
    .build()

/**
 * The presenter half of the graph.
 *
 * Split out only to keep each function short enough to read at a glance; the two halves are one
 * table and belong side by side.
 */
@UnstableApi
private fun presenterFor(
  screen: Screen,
  navigator: Navigator,
  prefsRepo: PrefsRepo,
  plexConfig: PlexConfig,
): Presenter<*>? =
  when (screen) {
    HomeScreenKey -> HomePresenter({ recordViewModel() }, plexConfig, navigator)
    LibraryScreenKey -> LibraryPresenter({ recordViewModel() }, prefsRepo, plexConfig, navigator)
    CollectionsScreenKey -> CollectionsPresenter({ recordViewModel() }, plexConfig, navigator)
    SettingsScreenKey -> SettingsPresenter({ recordViewModel() }, navigator)
    BrowseScreenKey -> BrowsePresenter({ recordViewModel() }, navigator)
    SeriesIndexTesterScreenKey -> SeriesIndexTesterPresenter({ recordViewModel() }, navigator)
    LicensesScreenKey -> LicensesPresenter({ recordViewModel() }, navigator)
    // The three argument-carrying screens. `creationCallback` is Hilt's assisted-injection
    // entry point: the lambda receives the generated `@AssistedFactory` and hands it the value
    // straight off the screen key.
    is BookDetailsScreenKey ->
      DetailsPresenter(
        { detailsViewModel(screen) },
        navigator,
      )
    is CollectionDetailsScreenKey ->
      CollectionDetailsPresenter(
        {
          recordViewModel<CollectionDetailsViewModel, CollectionDetailsViewModel.Factory> {
            it.create(screen.collectionId)
          }
        },
        plexConfig,
        navigator,
      )
    is FacetBooksScreenKey ->
      FacetBooksPresenter(
        {
          recordViewModel<FacetBooksViewModel, FacetBooksViewModel.Factory> {
            it.create(screen.kind, screen.value)
          }
        },
        plexConfig,
        navigator,
        // The toolbar title, straight off the key. Under Navigation Compose this was decoded
        // back out of the route's path segment.
        title = screen.value,
      )
    // The four onboarding screens present nothing: they raise no navigation of their own, and
    // each wires its own ViewModel inside its composable. `IPlexLoginRepo.loginEvent` drives
    // every move between them, and the shell collects it — the one place that can outlive any
    // single screen. They still need *a* presenter, because Circuit pairs every UI with one.
    LoginScreenKey, ChooseUserScreenKey, ChooseServerScreenKey, ChooseLibraryScreenKey ->
      NoStatePresenter
    else -> null
  }

/** The UI half of the graph. See [presenterFor]. */
@UnstableApi
private fun uiFor(
  screen: Screen,
  prefsRepo: PrefsRepo,
  plexConfig: PlexConfig,
): Ui<*>? =
  // `ui {}`'s lambda **is** composable — it becomes `Ui.Content` — so `recordViewModel()` inside
  // it resolves against the record's own store owner and returns the same instance the presenter
  // got. The factory lambda around it is not composable, which is why nothing is resolved here.
  when (screen) {
    HomeScreenKey -> ui<HomeCircuitState> { state, modifier -> HomeUi(state, recordViewModel(), plexConfig, modifier) }
    LibraryScreenKey ->
      ui<LibraryCircuitState> { state, modifier ->
        LibraryUi(state, recordViewModel(), plexConfig, modifier)
      }
    CollectionsScreenKey ->
      ui<CollectionsCircuitState> { state, modifier ->
        CollectionsUi(state, recordViewModel(), plexConfig, modifier)
      }
    SettingsScreenKey ->
      ui<SettingsCircuitState> { state, modifier -> SettingsUi(state, recordViewModel(), modifier) }
    BrowseScreenKey -> ui<BrowseCircuitState> { state, modifier -> BrowseUi(state, modifier) }
    SeriesIndexTesterScreenKey ->
      ui<SeriesIndexTesterCircuitState> { state, modifier -> SeriesIndexTesterUi(state, modifier) }
    LicensesScreenKey -> ui<LicensesCircuitState> { state, modifier -> LicensesUi(state, modifier) }
    is BookDetailsScreenKey ->
      ui<DetailsCircuitState> { state, modifier ->
        DetailsUi(state, detailsViewModel(screen), plexConfig, modifier)
      }
    is CollectionDetailsScreenKey ->
      ui<CollectionDetailsCircuitState> { state, modifier ->
        CollectionDetailsUi(state, plexConfig, modifier)
      }
    is FacetBooksScreenKey ->
      ui<FacetBooksCircuitState> { state, modifier -> FacetBooksUi(state, plexConfig, modifier) }
    else -> onboardingUiFor(screen, prefsRepo)
  }

/**
 * The four onboarding screens, which present nothing.
 *
 * Split from [uiFor] so neither `when` runs past detekt's complexity threshold — and they are a
 * coherent group to separate: each wires its own ViewModel inside its composable, and
 * `IPlexLoginRepo.loginEvent` rather than a tap moves between them.
 */
private fun onboardingUiFor(
  screen: Screen,
  prefsRepo: PrefsRepo,
): Ui<*>? =
  when (screen) {
    LoginScreenKey -> staticUi { modifier -> LoginUi(prefsRepo, modifier) }
    ChooseUserScreenKey -> staticUi { modifier -> ChooseUserUi(modifier) }
    ChooseServerScreenKey -> staticUi { modifier -> ChooseServerUi(modifier) }
    ChooseLibraryScreenKey -> staticUi { modifier -> ChooseLibraryUi(modifier) }
    else -> null
  }

/**
 * A screen with no presenter: its `Ui` takes only a `Modifier`.
 *
 * Circuit models every screen as state + UI, and the four onboarding screens genuinely have no
 * state of their own to present — `IPlexLoginRepo.loginEvent` moves between them and each one wires
 * its own ViewModel. Rather than inventing an empty presenter per screen, they share one empty
 * state object.
 */
private data object NoState : CircuitUiState

/** Presents [NoState]. A `fun interface` conversion will not do: `present` is `@Composable`. */
private object NoStatePresenter : Presenter<NoState> {
  @Composable
  override fun present(): NoState = NoState
}

private fun staticUi(content: @Composable (Modifier) -> Unit): Ui<NoState> = ui<NoState> { _, modifier -> content(modifier) }

/**
 * The details ViewModel for one book, built through Hilt's assisted injection.
 *
 * Its own function because both the presenter and the `Ui` need it, and both must resolve it
 * **inside** composition so they land on the same per-record instance rather than two.
 */
@Composable
private fun detailsViewModel(screen: BookDetailsScreenKey): AudiobookDetailsViewModel =
  recordViewModel<AudiobookDetailsViewModel, AudiobookDetailsViewModel.Factory> {
    it.create(screen.bookId)
  }
