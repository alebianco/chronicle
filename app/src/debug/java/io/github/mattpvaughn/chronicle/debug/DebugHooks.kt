package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.sources.plex.ProgressApi
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber

/**
 * Debug build: honours the mock-Plex switch.
 *
 * The release source set provides a no-op twin, so none of the fixture
 * machinery is compiled into a release build.
 *
 * The switch is **persisted**, not read from the launch intent alone. Mock mode
 * has to be established before `setupNetwork()` runs, otherwise the app
 * refreshes connections against the real plex.tv, gets a 401 for the fake token,
 * and clears the seeded server. An intent extra arrives too late for that, so it
 * only records the preference and the next process start applies it.
 */
object DebugHooks : DebugHooksContract {
  private const val PREFS = "chronicle_debug"
  private const val KEY_MOCK_PLEX = "mock_plex"
  private const val EXTRA_MOCK_PLEX = "mock_plex"
  private const val EXTRA_PLAY_BOOK = "play_book"
  private const val EXTRA_FAIL_SYNC = "fail_sync"
  private const val KEY_FAIL_SYNC = "fail_sync"
  private const val EXTRA_SHOW_PLAYER = "show_player"
  private const val EXTRA_INVALIDATE_SERVER_TOKEN = "invalidate_server_token"

  /**
   * The bogus token. Non-empty on purpose: `SharedPreferencesPlexPrefsRepo.server`'s getter
   * returns `null` when the access token is empty, which the app reads as "no server chosen" —
   * so blanking it would skip the 401 path entirely and test nothing.
   */
  private const val INVALID_SERVER_TOKEN = "invalid-token-for-debug-hook"

  /** Applied at Application start, before any network setup. */
  override fun onApplicationCreate(application: ChronicleApplication) {
    if (!isEnabled(application)) {
      return
    }
    Timber.i("Mock Plex mode is enabled; seeding a fixture-backed session")
    MockPlexMode.enable(application)
  }

  /**
   * Records the mock-Plex preference and restarts the process so it takes effect:
   *
   * ```
   * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \
   *   --ez mock_plex true
   * ```
   */

  override fun onMainActivityIntent(intent: Intent?) {
    if (intent == null || !intent.hasExtra(EXTRA_MOCK_PLEX)) {
      return
    }
    val context = ChronicleApplication.get()
    val requested = intent.getBooleanExtra(EXTRA_MOCK_PLEX, false)
    if (requested == isEnabled(context)) {
      return
    }
    setEnabled(context, requested)
    Timber.i("Mock Plex mode set to $requested; restarting process to apply")
    // The flag is read at Application start, so the process has to come back for
    // it to matter. Restarting is cleaner than half-applying it to a live app.
    Runtime.getRuntime().exit(0)
  }

  /**
   * Starts playback of a book by id, so playback can be exercised from a script
   * without depending on tap coordinates:
   *
   * ```
   * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \\
   *   --el play_book 1001
   * ```
   *
   * Driving the play button with `input tap` proved unreliable — coordinates
   * that work on one screen state miss on another, and the media-key path needs
   * an already-active session. This goes through `playFromMediaId`, the exact
   * call the play button makes, so it exercises the real path rather than a
   * shortcut around it (cu-64).
   */
  override fun onPlayBookIntent(
    intent: Intent?,
    mediaServiceConnection: MediaServiceConnection,
  ) {
    // Accepts both `--el play_book 123` (the documented form, kept working) and
    // `--es play_book <id>`, since ids are Strings now and need not be numeric (cu-71).
    val bookId =
      intent?.takeIf { it.hasExtra(EXTRA_PLAY_BOOK) }?.let { source ->
        source.getStringExtra(EXTRA_PLAY_BOOK)
          ?: source.getLongExtra(EXTRA_PLAY_BOOK, -1L).takeIf { it > 0L }?.toString()
      }
    if (bookId.isNullOrEmpty()) {
      return
    }
    val controls = mediaServiceConnection.transportControls
    if (controls == null) {
      Timber.w("play_book: media service not connected yet; ignoring")
      return
    }
    Timber.i("play_book: starting playback of book $bookId")
    controls.playFromMediaId(
      bookId,
      // No KEY_SEEK_TO_TRACK_WITH_ID: absence resumes the most recently listened track.
      Bundle().apply {
        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
      },
    )
  }

  /**
   * Makes progress reports fail terminally, so the "position not synced" badge can be
   * seen:
   *
   * ```
   * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \
   *   --ez fail_sync true
   * ```
   *
   * Works against a **real** Plex server as well as the fixture one (cu-73). It used to only set
   * a flag on [MockPlexMode]'s server, which is null unless mock mode is running — so on a live
   * server this was a silent no-op and the badge was unreachable without an actual outage.
   *
   * The flag is **persisted**, for the same reason the mock-Plex switch is: the report happens in
   * [io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker], which WorkManager
   * may run in a process that never saw the intent. An in-memory flag would work only for reports
   * enqueued before the next process death, which is exactly the kind of flakiness a debug hook
   * must not have.
   */
  override fun onFailSyncIntent(intent: Intent?) {
    if (intent == null || !intent.hasExtra(EXTRA_FAIL_SYNC)) {
      return
    }
    val fail = intent.getBooleanExtra(EXTRA_FAIL_SYNC, false)
    // Kept for mock mode, where failing at the fixture server exercises the real HTTP path.
    MockPlexMode.setFailProgressReports(fail)
    // And recorded for the live-server case, read back by [wrapProgressApi].
    ChronicleApplication.get().applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_FAIL_SYNC, fail)
      .commit()
    Timber.i("Progress-report failure injection is now $fail (persisted)")
  }

  /**
   * Replaces the stored server access token with a wrong one:
   *
   * ```
   * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \
   *   --ez invalidate_server_token true
   * ```
   *
   * The next authenticated request then 401s, and [PlexTokenAuthenticator] should re-fetch the
   * real token from `/api/v2/resources`, retry once, and succeed — invisibly.
   *
   * **This is the only way to reach that path.** Rotating the token server-side does not: Plex
   * keeps honouring the superseded one, and `setupNetwork` adopts the new one on the next launch
   * before any authenticated request, so no 401 ever happens (measured in cu-73). Editing the
   * prefs file does not either — `SharedPreferences` caches in memory, so a running app never
   * re-reads it.
   *
   * Writes through the **repository**, not the file, for that reason. Applied from `MainActivity`,
   * so it lands *after* `setupNetwork`'s refresh rather than being repaired by it.
   *
   * Not persisted, unlike `fail_sync`: the point is one invalid request. A persisted bad token
   * would be overwritten by the startup refresh on every launch anyway, so persisting it would
   * only make the hook look unreliable.
   */
  override fun onInvalidateServerTokenIntent(intent: Intent?) {
    if (intent == null || !intent.getBooleanExtra(EXTRA_INVALIDATE_SERVER_TOKEN, false)) {
      return
    }
    val plexPrefs = Injector.get().plexPrefs()
    val server = plexPrefs.server
    if (server == null) {
      Timber.w("invalidate_server_token: no server is configured; nothing to invalidate")
      return
    }
    plexPrefs.server = server.copy(accessToken = INVALID_SERVER_TOKEN)
    // Presence, never the value — logging a real token is what TokenLoggingTest exists to stop,
    // and the replacement is a constant, so there is nothing useful to print either way.
    Timber.i("invalidate_server_token: server access token replaced with a bogus value")
  }

  /**
   * Expands the currently-playing sheet:
   *
   * ```
   * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \
   *   --ez show_player true
   * ```
   *
   * Exists because `--el play_book` drives playback through the **media session**, which never
   * navigates the UI — so the player sheet stayed unlaid-out and the "position not synced" badge
   * could not be screenshotted without tap coordinates (cu-73). Coordinates are exactly what
   * makes a device check unrepeatable across form factors, which is why this is a hook and not a
   * documented tap.
   *
   * Observes rather than expanding immediately: the sheet is HIDDEN until a playback state
   * arrives, and [MainActivityViewModel.expandCurrentlyPlaying] is a deliberate no-op then. So
   * this waits for the first COLLAPSED and expands once, then stops observing.
   */
  override fun onShowPlayerIntent(
    intent: Intent?,
    lifecycleOwner: LifecycleOwner,
    viewModel: MainActivityViewModel,
  ) {
    if (intent == null || !intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)) {
      return
    }
    Timber.i("Waiting for playback before expanding the player sheet (show_player)")
    viewModel.currentlyPlayingLayoutState.observe(lifecycleOwner) { state ->
      if (state == MainActivityViewModel.BottomSheetState.COLLAPSED) {
        Timber.i("Expanding the player sheet (show_player)")
        viewModel.expandCurrentlyPlaying()
      }
    }
  }

  /**
   * Substitutes a [ProgressApi] that always fails terminally, when `fail_sync` is set.
   *
   * A 4xx rather than an [java.io.IOException]: [ProgressReporter] treats a 4xx as
   * `PERMANENT_FAILURE` and an IOException as `RETRY`, and only the former reaches
   * `Result.failure()` — which is the single WorkManager state
   * [io.github.mattpvaughn.chronicle.features.player.hasFailedSync] looks for. Injecting a retry
   * would leave the worker RUNNING and the badge hidden, which would look like the badge was
   * broken.
   *
   * `markWatched` is deliberately left alone: it is a separate call whose failure
   * [ProgressReporter] swallows on purpose, and failing it would prove nothing about the badge.
   */
  override fun wrapProgressApi(api: ProgressApi): ProgressApi {
    val context = ChronicleApplication.get().applicationContext
    val fail =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_FAIL_SYNC, false)
    if (!fail) {
      return api
    }
    Timber.i("Failing this progress report deliberately (fail_sync)")
    return AlwaysFailingProgressApi(api)
  }

  /**
   * Fails every progress report with a 400, delegating everything else.
   *
   * Delegating rather than stubbing keeps the injection narrow: only the one call the badge
   * depends on changes behaviour.
   */
  private class AlwaysFailingProgressApi(
    private val delegate: ProgressApi,
  ) : ProgressApi {
    override suspend fun reportProgress(
      ratingKey: String,
      offset: String,
      key: String,
      duration: Long,
      playState: String,
      playbackTime: Long,
      playQueueItemId: Long,
    ) {
      throw HttpException(
        Response.error<Unit>(
          400,
          "fail_sync debug hook".toResponseBody("text/plain".toMediaType()),
        ),
      )
    }

    override suspend fun markWatched(key: String) = delegate.markWatched(key)
  }

  /**
   * Turns mock mode on before `Application.onCreate` reads it.
   *
   * The instrumented suite (cu-54) needs the flag set from `AndroidJUnitRunner.onCreate`, which is
   * the only hook that runs before the application starts. Exposed here rather than duplicating
   * the file and key names in the test, where they would drift silently — the flag not being read
   * looks exactly like mock mode being off.
   */
  fun setMockPlexEnabled(
    context: Context,
    enabled: Boolean,
  ) = setEnabled(context, enabled)

  private fun isEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getBoolean(KEY_MOCK_PLEX, false)

  private fun setEnabled(
    context: Context,
    enabled: Boolean,
  ) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_MOCK_PLEX, enabled)
      .commit()
  }
}
