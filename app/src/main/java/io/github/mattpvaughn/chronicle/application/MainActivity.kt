package io.github.mattpvaughn.chronicle.application

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.COLLAPSED
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.EXPANDED
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_FULLY
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.databinding.ActivityMainBinding
import io.github.mattpvaughn.chronicle.debug.DebugHooks
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingFragment
import io.github.mattpvaughn.chronicle.features.currentlyplaying.setBottomSheetState
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.ACTION_PLAYBACK_ERROR
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLAYBACK_ERROR_MESSAGE
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.injection.components.ActivityComponent
import io.github.mattpvaughn.chronicle.injection.components.DaggerActivityComponent
import io.github.mattpvaughn.chronicle.injection.modules.ActivityModule
import io.github.mattpvaughn.chronicle.injection.scopes.ActivityScope
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.observeEvent
import io.github.mattpvaughn.chronicle.views.bindImageRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class MainActivity : AppCompatActivity() {
  @Inject
  lateinit var localBroadcastManager: LocalBroadcastManager

  @Inject
  lateinit var mainActivityViewModelFactory: MainActivityViewModel.Factory

  private val viewModel: MainActivityViewModel by lazy {
    ViewModelProvider(this, mainActivityViewModelFactory).get(MainActivityViewModel::class.java)
  }

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexPrefsRepo: PlexPrefsRepo

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var mediaServiceConnection: MediaServiceConnection

  var activityComponent: ActivityComponent? = null

  override fun onDestroy() {
    activityComponent = null
    super.onDestroy()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    Timber.i("MainActivity onCreate()")
    activityComponent =
      DaggerActivityComponent.builder()
        .appComponent((application as ChronicleApplication).appComponent)
        .activityModule(ActivityModule(this))
        .build()
    activityComponent!!.inject(this)

    // No-op in release: the release source set provides an empty DebugHooks, so
    // the mock-Plex machinery is not compiled into a release build at all.
    DebugHooks.onMainActivityIntent(intent)
    DebugHooks.onFailSyncIntent(intent)
    // Debug-only: `--el play_book <id>` starts playback once the media service is
    // connected. connect{} is required — transportControls is null until then,
    // which is why driving playback from a bare intent alone does not work.
    if (mediaServiceConnection.isConnected.value == true) {
      DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
    } else {
      mediaServiceConnection.connect {
        DebugHooks.onPlayBookIntent(intent, mediaServiceConnection)
      }
    }

    super.onCreate(savedInstanceState)

    localBroadcastManager = LocalBroadcastManager.getInstance(this)

    val binding =
      ActivityMainBinding.inflate(layoutInflater).also { setContentView(it.root) }

    applyWindowInsets(binding)

    // Was binding expressions in activity_main.xml.
    viewModel.currentlyPlayingLayoutState.observe(this) { state ->
      setBottomSheetState(binding.mainRoot, state)
    }
    viewModel.isLoggedIn.observe(this) { loggedIn ->
      binding.bottomNav.isVisible = loggedIn == true
      // INVISIBLE, not GONE: the collapsed player keeps its layout slot so the
      // content above it does not reflow when it appears.
      binding.currentlyPlayingContainer.visibility =
        if (loggedIn == true) View.VISIBLE else View.INVISIBLE
    }
    viewModel.currentChapterTitle.observe(this) { binding.chapterTitle.text = it }
    viewModel.audiobook.observe(this) { book ->
      binding.bookTitle.text = book?.title.orEmpty()
      binding.currentlyPlayingThumb.contentDescription = book?.title.orEmpty()
      bindImageRounded(
        binding.currentlyPlayingThumb,
        book?.thumb,
        plexConfig.isConnected.value == true,
      )
    }
    viewModel.isPlaying.observe(this) { playing ->
      // A button shows the action a tap performs, not the current state: while
      // playing it must offer pause. The drawables are state-named, which is how
      // this got inverted during the cu-58 conversion — the other two play/pause
      // buttons (CurrentlyPlayingFragment, AudiobookDetailsFragment) both map
      // playing -> pause icon, and NotificationBuilder is not a counterexample
      // because that is a status icon rather than a button.
      binding.pausePlayButton.setImageResource(
        if (playing == true) {
          R.drawable.ic_notification_icon_paused
        } else {
          R.drawable.ic_notification_icon_playing
        },
      )
    }
    binding.pausePlayButton.setOnClickListener { viewModel.pausePlayButtonClicked() }

    binding.currentlyPlayingHandle.setOnClickListener {
      viewModel.onCurrentlyPlayingClicked()
    }

    viewModel.errorMessage.observeEvent(this) { errorMessage ->
      Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
    }

    // TODO: show/hide this item on launch more performantly
    viewModel.hasCollections.observe(this) {
      binding.bottomNav.menu.findItem(R.id.nav_collections).isVisible = it
    }

    binding.bottomNav.setOnItemSelectedListener {
      when (it.itemId) {
        R.id.nav_settings -> navigator.showSettings()
        R.id.nav_library -> navigator.showLibrary()
        R.id.nav_collections -> navigator.showCollections()
        R.id.nav_home -> navigator.showHome()
        else -> throw NoWhenBranchMatchedException("Unknown bottom tab id: ${it.itemId}")
      }
      viewModel.minimizeCurrentlyPlaying()
      return@setOnItemSelectedListener true
    }

    if (savedInstanceState == null) {
      setupCurrentlyPlaying()
      plexLoginRepo.loginEvent.value?.let {
        if (it.peekContent() == LOGGED_IN_FULLY) {
          navigator.showHome()
        }
      }
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

  override fun onBackPressed() {
    // If currently playing view is over fragments, close it via back button
    if (viewModel.currentlyPlayingLayoutState.value == EXPANDED) {
      viewModel.setBottomSheetState(COLLAPSED)
      return
    }
    // default to activity back stack if navigator did not handle anything
    if (!navigator.onBackPressed()) {
      Timber.i("MainActivity super.onBackPressed()")
      if (supportFragmentManager.backStackEntryCount == 0) {
        // The prevent Q+ from leaking the activity internally, don't call
        // super.onBackPressed() if at base fragment, manually end...
        finishAfterTransition()
      } else {
        super.onBackPressed()
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun setupCurrentlyPlaying() {
    val transaction = supportFragmentManager.beginTransaction()
    transaction.replace(
      R.id.currently_playing_fragment_container,
      CurrentlyPlayingFragment.newInstance(),
    )
    transaction.commit()
    val handle = findViewById<View>(R.id.currently_playing_handle)
    val gd =
      GestureDetector(
        this,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
          ): Boolean {
            if (distanceY > distanceX) {
              viewModel.onCurrentlyPlayingHandleDragged()
            }
            return super.onScroll(e1, e2, distanceX, distanceY)
          }
        },
      )
    handle.setOnTouchListener { v, event ->
      gd.onTouchEvent(event)
      v.onTouchEvent(event)
    }
  }

  interface CurrentlyPlayingInterface {
    fun setBottomSheetState(state: MainActivityViewModel.BottomSheetState)
  }

  fun getCurrentlyPlayingInterface(): CurrentlyPlayingInterface {
    return viewModel
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

  /**
   * Insets the app content for the system bars.
   *
   * `targetSdk 36` enforces edge-to-edge: Android no longer insets content, so
   * without this the toolbar draws under the status bar and the bottom nav under
   * the gesture bar (cu-63, a regression shipped by cu-6).
   *
   * Applied once here rather than per-screen: every fragment is hosted inside
   * `main_root`, so padding the shared chrome covers all of them and there is one
   * place to reason about instead of nine. Fragments with their own toolbar get
   * top padding via [applyTopInsetToToolbar] as they are created.
   */
  private fun applyWindowInsets(binding: ActivityMainBinding) {
    ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { view, windowInsets ->
      val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
      // Left/right matter in landscape and on devices with a cutout; the bottom
      // nav takes the bottom inset so it sits above the gesture bar.
      view.updatePadding(left = bars.left, right = bars.right)
      binding.bottomNav.updatePadding(bottom = bars.bottom)
      // Consume nothing: fragments still need the top inset for their toolbars.
      windowInsets
    }
  }

  override fun onNewIntent(intent: Intent?) {
    handleNotificationIntent(intent)
    // The activity is singleInstance, so a re-launch arrives here rather than in
    // onCreate — the debug hooks have to be handled in both places.
    DebugHooks.onFailSyncIntent(intent)
    if (mediaServiceConnection.isConnected.value == true) {
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
      intent?.extras?.getInt(
        FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, NO_AUDIOBOOK_FOUND_ID,
      ) ?: NO_AUDIOBOOK_FOUND_ID
    if (openAudiobookWithId != NO_AUDIOBOOK_FOUND_ID) {
      lifecycleScope.launch {
        withContext(Dispatchers.IO) {
          val audiobook = bookRepository.getAudiobookAsync(openAudiobookWithId)
          if (audiobook != null && audiobook != EMPTY_AUDIOBOOK) {
            navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
          }
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
