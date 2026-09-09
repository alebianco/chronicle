package io.github.mattpvaughn.chronicle.data.sources.plex

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential snapshot contract.
 *
 * There was no test class here at all until a lost server token was traced to this file, which is
 * the reason the fault survived two rounds of fixing the *settings* store: the same mechanism
 * existed in both, and only one of them was under test.
 */
class CredentialStoreTest {
  private val serverToken = stringPreferencesKey("server_token")

  /** A store whose writes land after a beat, the way a cold device's disk behaves. */
  private class LaggyStore(
    initial: Preferences = emptyPreferences(),
  ) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }

  /**
   * **A written credential survives a disk emission that predates it.**
   *
   * This is the fault that presented as an empty Android Auto browse root on a cold emulator.
   * `SharedPreferencesPlexPrefsRepo.server` returns null unless the access token reads back, so
   * losing this one value makes a signed-in app look signed out — and `MockPlexMode` writes the
   * account token and the server token back to back while this store is still seeding from a file
   * that does not exist yet.
   *
   * The mechanism was a collector re-applying `dataStore.data` onto the snapshot: every emission
   * replaced it wholesale, so an emission whose read began before a `put` could be delivered after
   * it and drop the value. Measured at 16 losses per 400 against 0 per 400 with the race disabled.
   *
   * Restoring that collector fails this test.
   */
  @Test
  fun `a written credential survives a stale disk emission`() {
    val store = LaggyStore(mutablePreferencesOf(stringPreferencesKey("unrelated") to "x"))
    val credentials = CredentialStore(store)

    // What a reader of the file would have captured before the write below.
    val asReadBeforeTheWrite = store.state.value

    credentials.put("server_token", "mock-server-token")
    assertEquals("the write must be readable immediately", "mock-server-token", credentials.get("server_token"))

    // That earlier read, delivered now: faithful to what was on disk when it ran, simply stale.
    store.state.value = asReadBeforeTheWrite.toMutablePreferences()

    assertEquals(
      "a disk emission that predates the write must not drop the credential",
      "mock-server-token",
      credentials.get("server_token"),
    )
  }

  /** A removal must not be undone by a stale emission either — sign-out has to stick. */
  @Test
  fun `a removed credential stays removed after a stale disk emission`() {
    val store = LaggyStore(mutablePreferencesOf(serverToken to "old-token"))
    val credentials = CredentialStore(store)
    assertEquals("old-token", credentials.get("server_token"))

    val asReadBeforeTheRemoval = store.state.value
    credentials.remove("server_token")

    store.state.value = asReadBeforeTheRemoval.toMutablePreferences()

    assertEquals(
      "a stale emission must not resurrect a credential the user signed out of",
      "",
      credentials.get("server_token"),
    )
  }

  @Test
  fun `a credential written empty is still present`() {
    val credentials = CredentialStore(LaggyStore())
    credentials.put("server_token", "")

    // Load-bearing: signing out writes an empty token, and a caller that read empty as absent
    // would fall through to an older store and resurrect it.
    assertTrue("an empty credential must read as present", credentials.hasKey("server_token"))
    assertFalse("but not as non-empty", credentials.contains("server_token"))
  }

  @Test
  fun `an unreadable store reads as no credentials rather than throwing`() {
    val failing =
      object : DataStore<Preferences> {
        override val data: Flow<Preferences>
          get() = kotlinx.coroutines.flow.flow { throw okio.IOException("unreadable") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw okio.IOException("unreadable")
      }

    // Sends the user through sign-in, which is recoverable; a crash on launch is not.
    val credentials = CredentialStore(failing)
    assertEquals("", credentials.get("server_token"))
    assertFalse(credentials.hasKey("server_token"))
  }
}
