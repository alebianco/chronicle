package io.github.mattpvaughn.chronicle.data.sources.plex

import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What separates re-authenticating from logging out.
 *
 * A Plex token can be invalidated server-side while the user's choice of server and library remains
 * perfectly good, so recovery should be one OAuth PIN — not re-picking a library they already
 * picked, and certainly not losing downloads. Before this, the only path was a full logout.
 *
 * These exercise the prefs layer directly rather than through `PlexLoginRepo`, which resolves
 * collaborators through Dagger; what matters here is precisely *which fields survive*.
 */
class ReauthenticationTest {
  private val server =
    ServerModel(
      name = "Tower",
      connections = emptyList(),
      serverId = "server-1",
      accessToken = "server-token",
    )
  private val library = PlexLibrary(name = "Audiobooks", type = MediaType.ALBUM, id = "12")
  private val user = PlexUser(id = 7L, uuid = "u-7", title = "Reader", authToken = "user-token")

  private fun repo(): PlexPrefsRepo =
    FakePlexPrefsRepo().apply {
      accountAuthToken = "account-token"
      this.server = this@ReauthenticationTest.server
      this.library = this@ReauthenticationTest.library
      this.user = this@ReauthenticationTest.user
    }

  @Test
  fun `clearing credentials drops the account token`() {
    val prefs = repo()

    prefs.clearCredentials()

    assertEquals("", prefs.accountAuthToken)
  }

  /**
   * The per-user token is derived from the account token, so keeping it would leave a dead
   * credential that outlives the re-auth and fails the next request in a more confusing way.
   */
  @Test
  fun `clearing credentials drops the derived user token but keeps the user`() {
    val prefs = repo()

    prefs.clearCredentials()

    assertNotNull("the same person is signing back in", prefs.user)
    assertEquals("u-7", prefs.user?.uuid)
    assertEquals("", prefs.user?.authToken)
  }

  /** The whole point: the user does not re-pick what they already picked. */
  @Test
  fun `clearing credentials keeps the chosen server and library`() {
    val prefs = repo()

    prefs.clearCredentials()

    assertEquals("server-1", prefs.server?.serverId)
    assertEquals("12", prefs.library?.id)
  }

  /** Contrast with a real logout, which must still discard everything. */
  @Test
  fun `a full clear discards the server and library too`() {
    val prefs = repo()

    prefs.clear()

    assertEquals("", prefs.accountAuthToken)
    assertNull(prefs.server)
    assertNull(prefs.library)
    assertNull(prefs.user)
  }

  @Test
  fun `clearing credentials on an already-empty repo is harmless`() {
    val prefs = FakePlexPrefsRepo()

    prefs.clearCredentials()

    assertEquals("", prefs.accountAuthToken)
    assertNull(prefs.user)
  }

  /**
   * After re-auth the app must be able to tell it needs a token — an empty account token is what
   * `determineLoginState` keys `NOT_LOGGED_IN` off, and that is what routes to the login screen.
   */
  @Test
  fun `after clearing credentials the account token reads as absent`() {
    val prefs = repo()

    prefs.clearCredentials()

    assertTrue(prefs.accountAuthToken.isEmpty())
  }

  /**
   * Pins [FakePlexPrefsRepo] against the real implementation.
   *
   * The fake duplicates `clear`/`clearCredentials` rather than delegating, because the difference
   * between them is the behaviour under test. A duplicate can drift, so this runs the same
   * assertions through `SharedPreferencesPlexPrefsRepo` under Robolectric: if the real one changes,
   * this fails even though every test above still passes against the fake.
   */
  @RunWith(RobolectricTestRunner::class)
  class AgainstTheRealImplementation {
    private fun realRepo(): PlexPrefsRepo {
      val context = ApplicationProvider.getApplicationContext<android.content.Context>()
      val prefs =
        context.getSharedPreferences("reauth-test-${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
      val authPrefs =
        context.getSharedPreferences("reauth-auth-${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
      return SharedPreferencesPlexPrefsRepo(prefs, authPrefs).apply {
        accountAuthToken = "account-token"
        // A real connection, not emptyList(): the production getter returns null for a server
        // with no connections, so an empty one is indistinguishable from an absent one and the
        // test would pass or fail for the wrong reason.
        server =
          ServerModel(
            "Tower",
            listOf(Connection(uri = "https://192-168-1-7.abc.plex.direct:32400", local = true)),
            "server-1",
            "server-token",
          )
        library = PlexLibrary("Audiobooks", MediaType.ALBUM, "12")
        user = PlexUser(id = 7L, uuid = "u-7", title = "Reader", authToken = "user-token")
      }
    }

    @Test
    fun `clearCredentials keeps server and library, drops both tokens`() {
      val prefs = realRepo()

      prefs.clearCredentials()

      assertEquals("", prefs.accountAuthToken)
      assertEquals("", prefs.user?.authToken)
      assertEquals("u-7", prefs.user?.uuid)
      assertEquals("server-1", prefs.server?.serverId)
      assertEquals("12", prefs.library?.id)
    }

    @Test
    fun `clear discards everything`() {
      val prefs = realRepo()

      prefs.clear()

      assertEquals("", prefs.accountAuthToken)
      assertNull(prefs.server)
      assertNull(prefs.library)
      assertNull(prefs.user)
    }

    /**
     * The gap the prefs-layer tests above could not see.
     *
     * `beginReauthentication` preserves server and library, then publishes `NOT_LOGGED_IN` — and
     * `Navigator.showLogin()` used to answer that by calling `plexConfig.clear()`, throwing away
     * the very configuration that had just been kept. The user signed in again and was made to
     * re-pick a library they had already picked.
     *
     * `Navigator` needs a `FragmentManager` and an `Activity`, so it cannot be constructed here;
     * what is pinned instead is the contract it depends on — **reaching the login screen is not
     * itself a reason to discard configuration**. An explicit logout clears deliberately, and
     * `clear discards everything` above covers that. If `showLogin` ever wipes again, the
     * behaviour this asserts is what it will contradict.
     */
    @Test
    fun `re-authentication leaves a library to come back to`() {
      val prefs = realRepo()

      // What beginReauthentication does...
      prefs.clearCredentials()
      // ...and what showing the login screen must NOT undo.
      assertEquals("server-1", prefs.server?.serverId)
      assertEquals("12", prefs.library?.id)
      assertNotNull(prefs.user)

      // Signing in again supplies only the missing credential.
      prefs.accountAuthToken = "fresh-account-token"

      assertEquals("fresh-account-token", prefs.accountAuthToken)
      assertEquals("12", prefs.library?.id)
      assertTrue("re-auth must not require choosing a library again", prefs.library != null)
    }
  }
}
