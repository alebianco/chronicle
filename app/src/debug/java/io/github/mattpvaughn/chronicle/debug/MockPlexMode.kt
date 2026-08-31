package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import timber.log.Timber

/**
 * Puts a debug build into a fully "logged in" state backed by [MockPlexServer],
 * with no Plex account involved.
 *
 * The point is reproducible screen states: the library, a book, the player and
 * the notification can all be reached and screenshotted from a clean install,
 * on any machine, with no credentials and no dependence on whatever a real
 * server happens to contain. That makes UI changes reviewable — a before/after
 * comparison is only meaningful if both sides show the same data.
 *
 * Debug source set only; this cannot exist in a release build.
 *
 * Enable via adb without rebuilding:
 * ```
 * adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity \
 *   --ez mock_plex true
 * ```
 */
object MockPlexMode {
  private var server: MockPlexServer? = null

  /**
   * Makes `/:/timeline` answer 401, so the terminal-failure path — and the "position not
   * synced" badge that depends on it — can be exercised without a real server outage.
   */
  fun setFailProgressReports(fail: Boolean) {
    server?.failProgressReports = fail
    Timber.i("MockPlexMode: failProgressReports=$fail")
  }

  val isRunning: Boolean
    get() = server != null

  /**
   * Starts the fixture server and seeds the login prefs so
   * `PlexLoginRepo.determineLoginState()` resolves to LOGGED_IN_FULLY.
   *
   * Seeding prefs rather than faking the login *flow* is deliberate: login state
   * is derived from stored values, so this exercises the real repositories,
   * interceptor and network stack — only the far end is a fixture.
   */
  fun enable(context: Context) {
    if (server != null) {
      Timber.i("MockPlexMode already enabled")
      return
    }
    val mock = MockPlexServer(context.applicationContext).apply { start() }
    server = mock

    val plexPrefs = Injector.get().plexPrefs()
    plexPrefs.accountAuthToken = "mock-account-token"
    plexPrefs.server =
      ServerModel(
        name = "Mock Plex Server",
        connections = listOf(Connection(uri = mock.baseUrl, local = true)),
        serverId = "mock-server-0000",
        accessToken = "mock-server-token",
        owned = true,
      )
    plexPrefs.library =
      PlexLibrary(
        name = "Audiobooks",
        type = MediaType.ARTIST,
        id = "1",
      )

    // Point the interceptor's rewrite target at the fixture server.
    Injector.get().plexConfig().url = mock.baseUrl

    // PlexLoginRepo evaluates login state in its own init, which has already run
    // by the time this hook fires, so the seeded prefs need a re-evaluation to
    // take effect.
    Injector.get().plexLoginRepo().determineLoginState()

    // No explicit connectToServer() here: this now runs before the app's own
    // setupNetwork(), so the normal connection flow picks up the seeded server
    // and discovers the mock via /identity on its own.

    Timber.i("MockPlexMode enabled against ${mock.baseUrl}")
  }

  fun disable() {
    server?.shutdown()
    server = null
    Injector.get().plexPrefs().clear()
    Timber.i("MockPlexMode disabled")
  }
}
