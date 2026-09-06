package io.github.mattpvaughn.chronicle.application.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.EXPANDED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.HIDDEN
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.MiniPlayerHeight
import io.github.mattpvaughn.chronicle.navigation.Destination
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors

/** The bottom navigation bar's fixed content height — `@dimen/bottom_nav_bar_height`. */
private val BottomNavHeight = 64.dp

/** `@integer/short_animation_ms`, the duration the ConstraintSet transition used. */
private const val SHEET_ANIMATION_MS = 400

/**
 * One bottom-navigation tab.
 *
 * The icon and label come from `menu/bottom_nav_menu.xml`, which retires with this — a Compose
 * `NavigationBar` builds its items from data rather than inflating a menu.
 */
private data class Tab(
  val destination: Destination,
  val iconRes: Int,
  val labelRes: Int,
)

private val TABS =
  listOf(
    Tab(Destination.Home, R.drawable.nav_home, R.string.tab_home),
    Tab(Destination.Library, R.drawable.nav_library, R.string.tab_library),
    Tab(Destination.Collections, R.drawable.ic_collections, R.string.tab_collections),
    Tab(Destination.Settings, R.drawable.nav_settings, R.string.tab_settings),
  )

/**
 * The whole app shell (cu-206): bottom navigation, the nav host, and the currently-playing sheet
 * stacked on top of both.
 *
 * This replaces `activity_main.xml`, whose player sheet was moved between three hand-written
 * `ConstraintSet`s by `CurrentlyPlayingBindingAdapters.setBottomSheetState`.
 *
 * ### Why the sheet is driven by state rather than by dragging
 *
 * The three states live in [BottomSheetState] on `MainActivityViewModel`, and four things read
 * them back — the back handler, the notification intent path, the media-session callbacks and the
 * player itself (cu-198). The XML version was never a `BottomSheetBehavior`: it was three
 * constraint sets plus a `GestureDetector` that only ever *toggled*. So this renders the state it
 * is given and keeps the ViewModel as the single source of truth, rather than adopting
 * `AnchoredDraggable`, whose internal state would be a second copy of it — and keeping one source
 * of truth is what preserves cu-73's back-handling fix.
 *
 * Insets are handled here rather than in `applyWindowInsets`: the nav bar grows by the bottom inset
 * and pads by the same amount, so its content keeps a full [BottomNavHeight] while the extra sits
 * under the system bar (cu-73, found on the owner's phone in 3-button navigation mode).
 */
@Composable
fun ChronicleApp(
  navController: NavHostController,
  isLoggedIn: Boolean,
  showCollectionsTab: Boolean,
  sheetState: BottomSheetState,
  onTabSelected: (Destination) -> Unit,
  miniPlayer: @Composable () -> Unit,
  expandedPlayer: @Composable () -> Unit,
  navHost: @Composable (Modifier) -> Unit,
) {
  Surface(modifier = Modifier.fillMaxSize(), color = ChronicleColors.Primary) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarTotalHeight = if (isLoggedIn) BottomNavHeight + bottomInset else 0.dp

    Box(modifier = Modifier.fillMaxSize()) {
      // Screen content sits under everything, padded clear of the nav bar and, when the player is
      // showing, of the collapsed handle too — so a list's last row can still be scrolled into
      // view rather than sitting permanently behind the mini player.
      val contentBottomPadding =
        navBarTotalHeight + if (sheetState == HIDDEN) 0.dp else MiniPlayerHeight

      navHost(Modifier.fillMaxSize().padding(bottom = contentBottomPadding))

      // The collapsed handle. Slides in and out, which is what the ConstraintSet transition did.
      AnimatedVisibility(
        visible = sheetState == COLLAPSED,
        enter =
          slideInVertically(animationSpec = tween(SHEET_ANIMATION_MS)) { it } +
            fadeIn(tween(SHEET_ANIMATION_MS)),
        exit =
          slideOutVertically(animationSpec = tween(SHEET_ANIMATION_MS)) { it } +
            fadeOut(tween(SHEET_ANIMATION_MS)),
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = navBarTotalHeight),
      ) {
        miniPlayer()
      }

      // The expanded player covers the content and the status bar, but stops above the nav bar —
      // the same constraint (`BOTTOM -> bottom_nav TOP`) the expanded ConstraintSet used.
      //
      // A plain `if`, **not** an `AnimatedVisibility`, and that is load-bearing rather than a
      // simplification. `AnimatedVisibility` keeps its content composed while hidden, so the
      // player would recompose at tick rate behind a collapsed sheet — `ProgressUpdater` publishes
      // once a second during playback, and the whole point of cu-198's gate is that a collapsed
      // player does *no* work (cu-110, cu-117, cu-141). Not composing it at all is a stronger
      // guarantee than any guard, which is what `CollapsedSheetGuardTest` pins.
      //
      // The cost is that the expanded player appears without a slide. The collapsed handle above
      // keeps its animation because it is cheap and always-composed anyway.
      if (sheetState == EXPANDED) {
        Surface(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(bottom = navBarTotalHeight),
          color = ChronicleColors.Primary,
        ) {
          Box(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            expandedPlayer()
          }
        }
      }

      if (isLoggedIn) {
        ChronicleBottomBar(
          navController = navController,
          showCollectionsTab = showCollectionsTab,
          bottomInset = bottomInset,
          onTabSelected = onTabSelected,
          modifier = Modifier.align(Alignment.BottomCenter),
        )
      }
    }
  }
}

@Composable
private fun ChronicleBottomBar(
  navController: NavHostController,
  showCollectionsTab: Boolean,
  bottomInset: Dp,
  onTabSelected: (Destination) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
  val visibleTabs =
    TABS.filter { it.destination != Destination.Collections || showCollectionsTab }

  NavigationBar(
    modifier = modifier.fillMaxWidth().height(BottomNavHeight + bottomInset),
    containerColor = ChronicleColors.Primary,
    windowInsets = WindowInsets.navigationBars,
  ) {
    visibleTabs.forEach { tab ->
      NavigationBarItem(
        selected = currentRoute == tab.destination.route,
        onClick = { onTabSelected(tab.destination) },
        icon = {
          Icon(
            painter = painterResource(tab.iconRes),
            contentDescription = stringResource(tab.labelRes),
          )
        },
        label = { Text(stringResource(tab.labelRes)) },
        // `labelVisibilityMode="selected"` in the XML menu.
        alwaysShowLabel = false,
        colors =
          NavigationBarItemDefaults.colors(
            selectedIconColor = ChronicleColors.Accent,
            selectedTextColor = ChronicleColors.Accent,
            unselectedIconColor = ChronicleColors.TextSecondary,
            unselectedTextColor = ChronicleColors.TextSecondary,
            indicatorColor = ChronicleColors.PrimaryDark,
          ),
      )
    }
  }
}
