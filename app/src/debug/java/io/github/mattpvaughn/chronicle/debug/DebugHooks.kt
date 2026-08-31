package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.ACTIVE_TRACK
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
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
    val bookId = intent?.takeIf { it.hasExtra(EXTRA_PLAY_BOOK) }?.getLongExtra(EXTRA_PLAY_BOOK, -1L)
    if (bookId == null || bookId <= 0L) {
      return
    }
    val controls = mediaServiceConnection.transportControls
    if (controls == null) {
      Timber.w("play_book: media service not connected yet; ignoring")
      return
    }
    Timber.i("play_book: starting playback of book $bookId")
    controls.playFromMediaId(
      bookId.toString(),
      Bundle().apply {
        putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
        putLong(KEY_SEEK_TO_TRACK_WITH_ID, ACTIVE_TRACK)
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
   */
  override fun onFailSyncIntent(intent: Intent?) {
    if (intent == null || !intent.hasExtra(EXTRA_FAIL_SYNC)) {
      return
    }
    MockPlexMode.setFailProgressReports(intent.getBooleanExtra(EXTRA_FAIL_SYNC, false))
  }

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
