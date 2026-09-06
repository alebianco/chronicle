package io.github.mattpvaughn.chronicle.application

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.scopes.ActivityScoped
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.EXPANDED
import io.github.mattpvaughn.chronicle.application.compose.ChronicleApp
import io.github.mattpvaughn.chronicle.application.compose.MiniPlayerHost
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.sources.plex.AccountAuthState
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.debug.DebugHooks
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.PlayerDestination
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.ACTION_PLAYBACK_ERROR
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLAYBACK_ERROR_MESSAGE
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.Destination
import io.github.mattpvaughn.chronicle.navigation.compose.ChronicleNavHost
import io.github.mattpvaughn.chronicle.navigation.destinationForLogin
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@ActivityScoped
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
  @Inject
  lateinit var localBroadcastManager: LocalBroadcastManager

  private val viewModel: MainActivityViewModel by viewModels()

  /**
   * The player's ViewModel, owned by the activity.
   *
   * The player sheet is drawn *above* the nav host rather than inside it, because it covers
   * whatever screen the user is on and survives navigating between them — the relationship
   * `currently_playing_container` had to `fragNavHost`. So it cannot use `hiltViewModel()`, which
   * scopes to a back-stack entry.
   */
  private val currentlyPlayingViewModel: CurrentlyPlayingViewModel by viewModels()

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  /**
   * Set from the composition so the back handler and the notification intent path can reach it.
   *
   * Both run after a frame has been drawn, so neither can observe the null. Cleared in
   * `onDestroy`, since the controller holds the whole graph.
   */
  private var navController: NavHostController? = null

  @Inject
  lateinit var plexPrefsRepo: PlexPrefsRepo

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var cachedFileManager: ICachedFileManager

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var dispatchers: DispatcherProvider

  @Inject
  lateinit var mediaServiceConnection: MediaServiceConnection

  @Inject
  lateinit var accountAuthState: AccountAuthState

  override fun onCreate(savedInstanceState: Bundle?) {
    Timber.i("MainActivity onCreate()")

    // **Before the debug hooks and before `viewModel` is touched** (cu-185). Hilt injects this
    // activity's members inside `super.onCreate()`, and `by viewModels()` needs the activity at
    // CREATED — reading either earlier crashed on launch with "You can 'consumeRestoredStateForKey'
    // only after the corresponding component has moved to the 'CREATED' state".
    super.onCreate(savedInstanceState)

    // No-op in release: the release source set provides an empty DebugHooks, so
    // the mock-Plex machinery is not compiled into a release build at all.
    DebugHooks.onMainActivityIntent(intent)
    DebugHooks.onFailSyncIntent(intent)
    DebugHooks.onInvalidateServerTokenIntent(intent)
    DebugHooks.onShowPlayerIntent(intent, this, viewModel)
    DebugHooks.onShowBrowseIntent(intent, this, ::navigateToRoute)
    DebugHooks.onShowSettingsIntent(intent, this, ::navigateToRoute)
    DebugHooks.onMoveSyncLocationIntent(intent, this)
    // Debug-only: `--el play_book <id>` starts playback once the media service is
    // connected. connect{} is required — transportControls is null until then,
    // which is why driving playback from a bare intent alone does not work.
    if (mediaServiceConnection.isConnected.value) {
      DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
    } else {
      mediaServiceConnection.connect {
        DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
      }
    }

    // Debug-only: `--el download_book <id>` starts a download (cu-132).
    DebugHooks.onDownloadBookIntent(intent, cachedFileManager, bookRepository, lifecycleScope)

    localBroadcastManager = LocalBroadcastManager.getInstance(this)

    // The whole UI is Compose now (cu-206). This replaces `activity_main.xml` — a
    // `ConstraintLayout` holding a `BottomNavigationView`, a `FragmentContainerView` and a
    // hand-built player sheet moved between three `ConstraintSet`s — along with every write that
    // drove it, including `applyWindowInsets`, whose guideline arithmetic recomputed the collapsed
    // player's position from the system bar inset (cu-73). `ChronicleApp` reads the insets itself.
    setContent {
      val sheetState by viewModel.currentlyPlayingLayoutState.collectAsStateWithLifecycle()
      val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
      val hasCollections by viewModel.hasCollections.collectAsStateWithLifecycle()
      val controller = rememberNavController()
      navController = controller

      LoginNavigation(controller)

      ChronicleTheme {
        ChronicleApp(
          navController = controller,
          isLoggedIn = isLoggedIn,
          showCollectionsTab = hasCollections,
          sheetState = sheetState,
          onTabSelected = { destination ->
            controller.navigate(destination.route) {
              // Tabs are roots, not a stack. `Navigator` cleared the back stack by hand before
              // every switch with `while (backStackEntryCount > 0) popBackStackImmediate()`.
              popUpTo(controller.graph.startDestinationId) { saveState = true }
              launchSingleTop = true
              restoreState = true
            }
            viewModel.minimizeCurrentlyPlaying()
          },
          miniPlayer = { MiniPlayerHost(viewModel, plexConfig) },
          expandedPlayer = {
            PlayerDestination(
              plexConfig = plexConfig,
              onCollapse = { viewModel.setBottomSheetState(COLLAPSED) },
              viewModel = currentlyPlayingViewModel,
            )
          },
          navHost = { navModifier ->
            ChronicleNavHost(
              navController = controller,
              prefsRepo = prefsRepo,
              plexConfig = plexConfig,
              modifier = navModifier,
            )
          },
        )

        AccountRevokedNotice { controller.navigate(Destination.Settings.ROUTE) }
      }
    }

    registerBackHandler()

    collectEventsWhileStarted(viewModel.errorMessage) { errorMessage ->
      Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
    }

    // If the app is being launched by voice assistant with a query
    val query = intent.getStringExtra(SearchManager.QUERY)
    if (!query.isNullOrEmpty()) {
      mediaServiceConnection.connect {
        mediaServiceConnection.transportControls?.playFromSearch(query, Bundle())
      }
    }

    handleNotificationIntent(intent)
  }

  /**
   * Navigates by route, for the debug hooks.
   *
   * They are posted to the next main-loop pass, so [navController] is set by the time this runs.
   */
  private fun navigateToRoute(route: String) {
    navController?.navigate(route)
  }

  /**
   * Routes on login state, which was `Navigator`'s init block.
   *
   * The decision itself is [destinationForLogin], a pure function with its own tests; this is only
   * the plumbing. It lives on the activity rather than in a screen because it must outlive any one
   * of them — a login event can arrive while the user is anywhere.
   *
   * Each destination becomes a fresh root: `popUpTo(graph.id) { inclusive = true }` clears
   * everything behind it, so backing out of onboarding cannot land on a stale Home rendered from
   * the previous session's Room data (cu-124).
   */
  @Composable
  private fun LoginNavigation(controller: NavHostController) {
    LaunchedEffect(controller) {
      plexLoginRepo.loginEvent.collect { event ->
        if (event.hasBeenHandled) return@collect
        val destination = destinationForLogin(event.peekContent()) ?: return@collect
        event.getContentIfNotHandled() ?: return@collect
        Timber.i("Login event changed to ${event.peekContent()}")
        controller.navigate(destination.route) {
          popUpTo(controller.graph.id) { inclusive = true }
        }
      }
    }
  }

  /**
   * The standing notice for a revoked account (decision-17).
   *
   * A revoked account is a condition the user has to act on, not a passing error, so it gets an
   * indefinite Snackbar rather than a Toast — which would vanish before it was read and leave the
   * app looking merely broken. Before decision-17 nothing was shown at all: `account_signed_out`
   * existed as a string and was referenced nowhere (cu-73).
   *
   * The action routes to Settings, where "Sign in again" already restores sync without losing the
   * server, library or downloads; this adds discovery, not a new recovery path.
   */
  @Composable
  private fun AccountRevokedNotice(onReauthenticate: () -> Unit) {
    val state by accountAuthState.state.collectAsStateWithLifecycle()
    val hostState = remember { SnackbarHostState() }
    val message = stringResource(R.string.account_signed_out)
    val action = stringResource(R.string.settings_reauthenticate)

    LaunchedEffect(state) {
      if (state != AccountAuthState.State.Revoked) {
        // Recovering dismisses the notice, which `signedOutSnackbar?.dismiss()` did by hand.
        hostState.currentSnackbarData?.dismiss()
        return@LaunchedEffect
      }
      val result =
        hostState.showSnackbar(
          message = message,
          actionLabel = action,
          duration = SnackbarDuration.Indefinite,
        )
      if (result == SnackbarResult.ActionPerformed) {
        onReauthenticate()
      }
    }

    SnackbarHost(hostState)
  }

  /**
   * Back handling, registered with [androidx.activity.OnBackPressedDispatcher].
   *
   * **Not** an `onBackPressed()` override. At `targetSdk` 36 on Android 16 the platform's
   * predictive-back gesture is mandatory and the legacy override is never called — so every branch
   * below was silently dead and a back press quit the app (cu-73).
   */
  private fun registerBackHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          // The expanded player is over everything, so back closes it first.
          if (viewModel.currentlyPlayingLayoutState.value == EXPANDED) {
            viewModel.setBottomSheetState(COLLAPSED)
            return
          }

          // Onboarding is not somewhere to escape *into the app* from. Back used to fall through
          // to the Home-tab branch below, landing the user on a Home that looks fully working —
          // because it renders the previous session's books out of Room — while the app's own
          // state still said LOGGED_IN_NO_LIBRARY_CHOSEN and the prefs had no library at all
          // (cu-124). Leaving is the honest response: nothing was chosen, so there is nothing to
          // show.
          if (viewModel.isOnboarding.value) {
            leaveApp()
            return
          }

          val controller = navController
          if (controller == null) {
            leaveApp()
            return
          }

          if (controller.popBackStack()) {
            return
          }

          // Home is the root: from another tab, back goes there rather than leaving the app.
          val current = controller.currentBackStackEntry?.destination?.route
          if (current != null && current != Destination.Home.ROUTE) {
            controller.navigate(Destination.Home.ROUTE) {
              popUpTo(controller.graph.startDestinationId) { inclusive = true }
              launchSingleTop = true
            }
            return
          }

          leaveApp()
        }

        /**
         * Hands the press back to the platform, so the predictive-back animation runs instead of a
         * bare `finish()`.
         */
        private fun leaveApp() {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
        }
      },
    )
  }

  override fun onStart() {
    super.onStart()
    Timber.i("MainActivity onStart()")
    localBroadcastManager.registerReceiver(onPlaybackError, IntentFilter(ACTION_PLAYBACK_ERROR))
  }

  override fun onStop() {
    Timber.i("MainActivity onStop()")
    localBroadcastManager.unregisterReceiver(onPlaybackError)
    super.onStop()
  }

  override fun onDestroy() {
    // The controller outlives the composition otherwise, and it holds the whole graph.
    navController = null
    super.onDestroy()
  }

  // Non-null since androidx.activity 1.10 (raised to 1.13.0 by Compose, cu-181). The body already
  // treats the intent as nullable throughout because `handleNotificationIntent` and every
  // `DebugHooks` entry point still accept `Intent?` -- they are also called from `onCreate`, where
  // a null intent is genuinely possible.
  override fun onNewIntent(intent: Intent) {
    handleNotificationIntent(intent)
    // The activity is singleInstance, so a re-launch arrives here rather than in
    // onCreate — the debug hooks have to be handled in both places.
    DebugHooks.onFailSyncIntent(intent)
    DebugHooks.onInvalidateServerTokenIntent(intent)
    DebugHooks.onShowPlayerIntent(intent, this, viewModel)
    DebugHooks.onShowBrowseIntent(intent, this, ::navigateToRoute)
    DebugHooks.onShowSettingsIntent(intent, this, ::navigateToRoute)
    DebugHooks.onMoveSyncLocationIntent(intent, this)
    DebugHooks.onDownloadBookIntent(intent, cachedFileManager, bookRepository, lifecycleScope)
    if (mediaServiceConnection.isConnected.value) {
      DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
    } else {
      mediaServiceConnection.connect {
        DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
      }
    }
    super.onNewIntent(intent)
  }

  private fun handleNotificationIntent(intent: Intent?) {
    val openCurrentlyPlaying =
      intent?.extras?.getBoolean(
        FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING, false,
      ) == true
    if (openCurrentlyPlaying) {
      viewModel.maximizeCurrentlyPlaying()
    }

    val openAudiobookWithId =
      intent?.extras?.getString(FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID)
        ?: NO_AUDIOBOOK_FOUND_ID
    if (openAudiobookWithId != NO_AUDIOBOOK_FOUND_ID) {
      lifecycleScope.launch {
        // Only the DB read goes to IO. The navigation must run on the main thread — it used to sit
        // inside the IO block (cu-169).
        val audiobook =
          withContext(dispatchers.io) {
            bookRepository.getAudiobookAsync(openAudiobookWithId)
          }
        if (audiobook != null && audiobook != EMPTY_AUDIOBOOK) {
          navController?.navigate(Destination.BookDetails(audiobook.id).route)
        }
      }
    }
  }

  private val onPlaybackError =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context,
        intent: Intent,
      ) {
        when (intent.action) {
          ACTION_PLAYBACK_ERROR -> {
            val errorMessage =
              intent.getStringExtra(PLAYBACK_ERROR_MESSAGE)
                ?: getString(R.string.playback_error_unknown)
            val userMessage =
              when {
                errorMessage.contains(
                  "404",
                ) -> getString(R.string.playback_error_404)
                errorMessage.contains(
                  "503",
                ) -> getString(R.string.playback_error_503)
                errorMessage.contains(
                  "401",
                ) -> getString(R.string.playback_error_401)
                else -> errorMessage
              }
            viewModel.showUserMessage(userMessage)
          }
          else -> throw NoWhenBranchMatchedException(
            getString(R.string.playback_error_unknown),
          )
        }
      }
    }

  companion object {
    const val FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING = "OPEN_ACTIVITY_TO_AUDIOBOOK"
    const val REQUEST_CODE_OPEN_APP_TO_CURRENTLY_PLAYING = -12
    const val FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID = "OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID"

    // add audiobook id to this number to avoid repeats
    const val REQUEST_CODE_PREFIX_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID = -1001110
  }
}
