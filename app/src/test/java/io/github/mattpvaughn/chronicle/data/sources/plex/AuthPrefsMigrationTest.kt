package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.ChronicleJson
import io.github.mattpvaughn.chronicle.data.local.SettingsDataStore
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.github.mattpvaughn.chronicle.testing.testCredentialStore
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Moving the credentials into their own preferences file.
 *
 * The migration's whole risk is **signing a user out**. `PlexLoginRepo.determineLoginState` reads
 * `NOT_LOGGED_IN` from an empty account token, and `server` reads as null when its token is
 * missing — so a half-completed move does not degrade gracefully, it presents as a logged-out
 * account with no server chosen. These tests exist to prove that cannot happen, at every point a
 * crash could land.
 *
 * The ordering being protected: values and marker land in the auth file in **one** `commit()`,
 * and only then are the originals removed from the settings file. At no point is a credential
 * absent from both files.
 */
@RunWith(RobolectricTestRunner::class)
class AuthPrefsMigrationTest {
  private lateinit var settings: SharedPreferences
  private lateinit var auth: SharedPreferences

  private val user =
    PlexUser(id = 1, uuid = "user-uuid", title = "Listener", authToken = "user-token")

  @Before
  fun setUp() {
    credentials = testCredentialStore()
    settingsDataStore = testSettingsDataStore()
    val context: Context = ApplicationProvider.getApplicationContext()
    settings = context.getSharedPreferences("cu108-settings", Context.MODE_PRIVATE)
    auth = context.getSharedPreferences("cu108-auth", Context.MODE_PRIVATE)
    settings.edit().clear().commit()
    auth.edit().clear().commit()
  }

  /**
   * One credential store across every `newRepo()` in a test.
   *
   * These tests model relaunches — "the upgrade reads it", "a fresh sign-in after the clear" — so
   * the instances must share persistent state. A new store per call would give each relaunch an
   * empty disk and make the fallback assertions vacuous.
   */
  private lateinit var credentials: CredentialStore
  private lateinit var settingsDataStore: SettingsDataStore

  private fun newRepo() = SharedPreferencesPlexPrefsRepo(settings, auth, credentials, settingsDataStore)

  /** Writes credentials the legacy way: into the settings file. */
  private fun writeLegacyCredentials() {
    settings.edit()
      .putString("auth_token", "account-token")
      .putString("server_token", "server-access-token")
      .putString("user", ChronicleJson.encodeToString(user))
      // A setting, to prove the migration leaves unrelated keys alone.
      .putBoolean("key_skip_silence", true)
      .commit()
  }

  @Test
  fun `credentials move out of the settings file`() {
    writeLegacyCredentials()

    val repo = newRepo()

    assertEquals("account-token", repo.accountAuthToken)
    assertEquals("user-token", repo.user?.authToken)
    assertEquals("account-token", credentials.get("auth_token"))
    assertFalse(
      "the settings file must no longer hold a token",
      settings.contains("auth_token"),
    )
    assertFalse(settings.contains("server_token"))
    assertFalse(settings.contains("user"))
  }

  @Test
  fun `an unrelated setting is untouched`() {
    writeLegacyCredentials()

    newRepo()

    assertTrue(settings.getBoolean("key_skip_silence", false))
  }

  @Test
  fun `the migration is idempotent`() {
    writeLegacyCredentials()

    newRepo()
    // Every subsequent launch constructs the repo again. The marker must short-circuit, and in
    // particular the second run must not clear a working token now that the settings file has
    // none left to copy.
    newRepo()
    val third = newRepo()

    assertEquals("account-token", third.accountAuthToken)
    assertEquals("server-access-token", credentials.get("server_token"))
    assertNotNull(third.user)
  }

  @Test
  fun `a fresh install migrates nothing and stays signed out`() {
    val repo = newRepo()

    assertEquals("", repo.accountAuthToken)
    // The marker is still set, so the check costs nothing on later launches.
    assertTrue(credentials.flag("credentials_migrated"))
  }

  @Test
  fun `an interrupted migration recovers on the next launch`() {
    writeLegacyCredentials()
    // Simulates a crash *before* the auth file was committed: nothing moved, no marker. The
    // values are still in the settings file, which is exactly why this is survivable.
    assertFalse(credentials.flag("credentials_migrated"))

    val repo = newRepo()

    assertEquals("account-token", repo.accountAuthToken)
  }

  @Test
  fun `a crash between the write and the cleanup leaves the user signed in`() {
    writeLegacyCredentials()
    // Simulates the other window: the auth file committed (values + marker), but the settings
    // file was never cleaned up. Reads prefer the auth file, so the duplicate is invisible.
    auth.edit()
      .putBoolean("credentials_migrated", true)
      .putString("auth_token", "account-token")
      .putString("server_token", "server-access-token")
      .putString("user", ChronicleJson.encodeToString(user))
      .commit()

    val repo = newRepo()

    assertEquals("account-token", repo.accountAuthToken)
    assertEquals("user-token", repo.user?.authToken)
  }

  @Test
  fun `the auth file wins over a stale settings copy`() {
    // The case that makes the previous test safe: if the two disagree, the auth file is the
    // authority, or a signed-out account could be resurrected from a leftover value.
    settings.edit().putString("auth_token", "stale-token").commit()
    // Seeded into the credential store, which is what the repo reads now — and the marker with it,
    // so the migration does not run and overwrite the value this test is about.
    credentials.setFlag("credentials_migrated", true)
    credentials.put("auth_token", "current-token")

    assertEquals("current-token", newRepo().accountAuthToken)
  }

  @Test
  fun `writing a token clears any stale settings copy`() {
    settings.edit().putString("auth_token", "stale-token").commit()
    auth.edit().putBoolean("credentials_migrated", true).commit()
    val repo = newRepo()

    repo.accountAuthToken = "new-token"

    assertEquals("new-token", repo.accountAuthToken)
    assertFalse(
      "a stale copy must not survive, or a later fallback read could resurrect it",
      settings.contains("auth_token"),
    )
  }

  @Test
  fun `clearing credentials empties both files`() {
    writeLegacyCredentials()
    val repo = newRepo()

    repo.clearCredentials()

    assertEquals("", repo.accountAuthToken)
    assertEquals("the cleared token must be empty in the credential store", "", credentials.get("auth_token"))
    assertFalse(settings.contains("auth_token"))
  }

  @Test
  fun `a signed-out account is not resurrected by a downgrade and upgrade`() {
    // A downgrade writes to the settings file (the old code's only home); the upgrade must not
    // then prefer a stale auth-file value over it. Covered by the write path clearing the other
    // side, so whichever file was written last is the one that answers.
    writeLegacyCredentials()
    val repo = newRepo()
    repo.accountAuthToken = ""

    // The "downgrade": old code writes the settings file directly.
    settings.edit().putString("auth_token", "resurrected").commit()

    // The upgrade reads it, because the auth file's value is empty rather than absent.
    assertEquals(
      "an empty auth value is a real value and must win over the settings file",
      "",
      newRepo().accountAuthToken,
    )
  }

  @Test
  fun `a full clear does not undo the migration`() {
    writeLegacyCredentials()
    val repo = newRepo()

    repo.clear()

    // The markers live in the auth file alongside the credentials, so a sign-out must not wipe
    // them — otherwise the next launch re-runs a migration against a settings file that no
    // longer has anything to move, and (before the guard existed) could clear a fresh token.
    assertTrue(credentials.flag("credentials_migrated"))
    assertTrue(credentials.flag("premium_keys_removed"))

    // And a fresh sign-in after the clear still lands in the auth file.
    val next = newRepo()
    next.accountAuthToken = "new-account-token"
    assertEquals("new-account-token", credentials.get("auth_token"))
    assertFalse(settings.contains("auth_token"))
  }

  // --- The orphaned premium keys ----------------------------------------------------------

  @Test
  fun `the orphaned premium keys are removed`() {
    settings.edit()
      .putBoolean("key_is_premium", true)
      .putString("key_premium_token", "play-purchase-token")
      .commit()

    newRepo()

    assertFalse(settings.contains("key_is_premium"))
    assertFalse(
      "a stale Play purchase token must be deleted, not merely left out of exports",
      settings.contains("key_premium_token"),
    )
  }

  @Test
  fun `premium key removal does not run twice`() {
    credentials.setFlag("premium_keys_removed", true)
    // A key written *after* the removal ran — contrived, but it proves the guard is what stops
    // the removal rather than the keys simply being absent.
    settings.edit().putBoolean("key_is_premium", true).commit()

    newRepo()

    assertTrue(settings.contains("key_is_premium"))
  }

  @Test
  fun `premium removal is recorded even when there was nothing to remove`() {
    newRepo()

    assertTrue(credentials.flag("premium_keys_removed"))
  }
}
