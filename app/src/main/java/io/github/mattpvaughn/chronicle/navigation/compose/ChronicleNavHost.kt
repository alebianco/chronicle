package io.github.mattpvaughn.chronicle.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsDestination
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseDestination
import io.github.mattpvaughn.chronicle.features.browse.compose.FacetBooksDestination
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionDetailsDestination
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsDestination
import io.github.mattpvaughn.chronicle.features.home.compose.HomeDestination
import io.github.mattpvaughn.chronicle.features.library.compose.LibraryDestination
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseLibraryDestination
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseServerDestination
import io.github.mattpvaughn.chronicle.features.login.compose.ChooseUserDestination
import io.github.mattpvaughn.chronicle.features.login.compose.LoginDestination
import io.github.mattpvaughn.chronicle.features.settings.compose.SeriesIndexTesterDestination
import io.github.mattpvaughn.chronicle.features.settings.compose.SettingsDestination
import io.github.mattpvaughn.chronicle.navigation.Destination
import io.github.mattpvaughn.chronicle.navigation.decodeArg

/**
 * The app's navigation graph.
 *
 * Replaces `Navigator`, which committed `FragmentManager` transactions against a
 * `FragmentContainerView`. Three things it did by hand are now the framework's job:
 *
 * - **`clearBackStack()`** — a `while (backStackEntryCount > 0) popBackStackImmediate()` loop before
 *   every tab switch — is `popUpTo(startDestination)`.
 * - **`isFragmentWithTagVisible(TAG)`**, which drove both back handling and the "don't re-add home
 *   if it's already showing" guard, is `currentBackStackEntry`. The tags themselves go with it.
 * - **Arguments in a `Bundle`** become route arguments, landing in the same `SavedStateHandle` the
 *   ViewModels already read.
 *
 * The **login destinations are ordinary entries here**, not a separate flow. `Navigator` drove them
 * from `IPlexLoginRepo.loginEvent`, and that collector moves to `MainActivity`, which is the one
 * place that can outlive any single screen.
 *
 * Carries `@UnstableApi` because the Cast SDK's opt-in reaches here from `CastButton` through
 * `DetailsDestination`. It carries on to `MainActivity.onCreate` — a lambda call site is still a
 * usage — which is as far as it goes, since `onCreate` is an override nothing else calls.
 */
@UnstableApi
@Composable
fun ChronicleNavHost(
  navController: NavHostController,
  prefsRepo: PrefsRepo,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
  startDestination: String = Destination.Home.ROUTE,
) {
  // Navigating to a book, from any of the six screens that list one.
  val openBook: (Audiobook) -> Unit = { book ->
    navController.navigate(Destination.BookDetails(book.id).route)
  }

  NavHost(
    navController = navController,
    startDestination = startDestination,
    modifier = modifier,
  ) {
    composable(Destination.Home.ROUTE) {
      HomeDestination(plexConfig = plexConfig, onBookClick = openBook)
    }

    composable(Destination.Library.ROUTE) {
      LibraryDestination(
        prefsRepo = prefsRepo,
        plexConfig = plexConfig,
        onBookClick = openBook,
        onBrowseClick = { navController.navigate(Destination.Browse.ROUTE) },
      )
    }

    composable(Destination.Collections.ROUTE) {
      CollectionsDestination(
        plexConfig = plexConfig,
        onCollectionClick = { navController.navigate(Destination.CollectionDetails(it.id).route) },
        onBookClick = openBook,
      )
    }

    composable(Destination.Settings.ROUTE) {
      SettingsDestination(
        onShowSeriesIndexTester = {
          navController.navigate(Destination.SeriesIndexTester.ROUTE)
        },
      )
    }

    composable(Destination.Browse.ROUTE) {
      BrowseDestination(
        onNavigateUp = navController::popBackStack,
        onFacetClick = { kind, facet ->
          navController.navigate(Destination.FacetBooks(kind, facet.value).route)
        },
      )
    }

    composable(Destination.SeriesIndexTester.ROUTE) {
      SeriesIndexTesterDestination(onNavigateUp = navController::popBackStack)
    }

    composable(
      route = Destination.BookDetails.ROUTE_PATTERN,
      arguments =
        listOf(
          navArgument(Destination.BookDetails.ARG_BOOK_ID) { type = NavType.StringType },
        ),
    ) {
      DetailsDestination(
        plexConfig = plexConfig,
        onNavigateUp = navController::popBackStack,
        onSeriesClick = { series ->
          navController.navigate(Destination.FacetBooks(FacetKind.Series, series).route)
        },
      )
    }

    composable(
      route = Destination.CollectionDetails.ROUTE_PATTERN,
      arguments =
        listOf(
          navArgument(Destination.CollectionDetails.ARG_COLLECTION_ID) {
            type = NavType.StringType
          },
        ),
    ) {
      CollectionDetailsDestination(
        plexConfig = plexConfig,
        onNavigateUp = navController::popBackStack,
        onBookClick = openBook,
      )
    }

    composable(
      route = Destination.FacetBooks.ROUTE_PATTERN,
      arguments =
        listOf(
          navArgument(Destination.FacetBooks.ARG_KIND) { type = NavType.StringType },
          navArgument(Destination.FacetBooks.ARG_VALUE) { type = NavType.StringType },
        ),
    ) { entry ->
      // The facet value is the toolbar title, and it is the one argument read back out of the
      // route rather than from the ViewModel — decoded, because it was encoded on the way in.
      val value =
        entry.arguments?.getString(Destination.FacetBooks.ARG_VALUE).orEmpty().let(::decodeArg)
      FacetBooksDestination(
        title = value,
        plexConfig = plexConfig,
        onNavigateUp = navController::popBackStack,
        onBookClick = openBook,
      )
    }

    composable(Destination.Login.ROUTE) { LoginDestination(prefsRepo = prefsRepo) }
    composable(Destination.ChooseUser.ROUTE) { ChooseUserDestination() }
    composable(Destination.ChooseServer.ROUTE) { ChooseServerDestination() }
    composable(Destination.ChooseLibrary.ROUTE) { ChooseLibraryDestination() }
  }
}
