package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import android.content.Intent
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
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
object DebugHooks {
  private const val PREFS = "chronicle_debug"
  private const val KEY_MOCK_PLEX = "mock_plex"
  private const val EXTRA_MOCK_PLEX = "mock_plex"

  /** Applied at Application start, before any network setup. */
  fun onApplicationCreate(application: ChronicleApplication) {
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
  fun onMainActivityIntent(intent: Intent?) {
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
