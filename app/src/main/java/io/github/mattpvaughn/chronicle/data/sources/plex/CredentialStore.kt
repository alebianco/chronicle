package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.IOException
import timber.log.Timber
import java.io.File

/**
 * The Plex credentials: account token, server token, the serialized user, and the client uuid.
 *
 * **It lives in `no_backup/`, and that is the whole design.** D8 keeps auth tokens off Auto Backup
 * and device transfer. That used to be expressed as two XML rules excluding
 * `domain="sharedpref" path="ChronicleAuth.xml"` — correct, but fragile in a specific way: an
 * exclusion is scoped to a *domain*, so the moment the file moves anywhere else the rules keep
 * parsing, keep passing their tests, and quietly stop matching anything. Auto Backup then takes the
 * tokens and nothing says so.
 *
 * `Context.noBackupFilesDir` is excluded by Android itself, by construction, with no rule to
 * maintain and nothing to keep in sync across two files and two API levels. The protection cannot
 * lapse silently because there is no longer a rule that *could* lapse.
 *
 * **Writes are synchronous.** The previous implementation used `commit()` with
 * `@SuppressLint("ApplySharedPref")` on every credential write, because the login flow reports
 * success to the user and then immediately re-reads: a deferred write the reader beats is a login
 * that appears to work and is gone on the next launch. `runBlocking` here keeps that guarantee.
 * These are a handful of writes at sign-in and sign-out, not a hot path — the settings store took
 * the opposite trade for the opposite reason.
 */
class CredentialStore(
  private val dataStore: DataStore<Preferences>,
  private val scope: CoroutineScope,
) {
  private val snapshot = MutableStateFlow(emptyPreferences())

  init {
    // Seeded synchronously: the first credential read happens during Application.onCreate, when
    // PlexLoginRepo decides whether the user is signed in. Reading defaults there would present a
    // signed-in user with the login screen.
    runBlocking {
      runCatching { snapshot.value = dataStore.data.first() }
        .onFailure { Timber.e(it, "Could not seed the credential store") }
    }
    scope.launch {
      dataStore.data
        .catch { cause ->
          if (cause is IOException) {
            Timber.e(cause, "Credential store unreadable; treating credentials as absent")
            emit(emptyPreferences())
          } else {
            throw cause
          }
        }.collect { snapshot.value = it }
    }
  }

  /** Reads a credential, or "" when it has never been written. */
  fun get(key: String): String = snapshot.value[stringPreferencesKey(key)] ?: ""

  /**
   * Writes a credential and does not return until it is durable.
   *
   * See the class comment: the login flow re-reads immediately, so this cannot be deferred.
   */
  fun put(
    key: String,
    value: String,
  ) {
    val k = stringPreferencesKey(key)
    snapshot.value = snapshot.value.toMutablePreferences().apply { this[k] = value }
    runBlocking {
      runCatching { dataStore.edit { it[k] = value } }
        .onFailure { Timber.e(it, "Could not persist a credential") }
    }
  }

  /** Removes a credential, durably. */
  fun remove(key: String) {
    val k = stringPreferencesKey(key)
    snapshot.value = snapshot.value.toMutablePreferences().apply { remove(k) }
    runBlocking {
      runCatching { dataStore.edit { it.remove(k) } }
        .onFailure { Timber.e(it, "Could not remove a credential") }
    }
  }

  /**
   * True when [key] has been written at all, **including when it was written empty**.
   *
   * The distinction is load-bearing: signing out writes an empty token, and a caller that treated
   * empty as absent would fall through to an older store and resurrect the credential the user just
   * cleared.
   */
  fun hasKey(key: String): Boolean = snapshot.value.contains(stringPreferencesKey(key))

  /** True when [key] holds a non-empty value. */
  fun contains(key: String): Boolean = get(key).isNotEmpty()

  /**
   * Reads a one-shot migration marker.
   *
   * The markers live here, with the credentials they describe, for the reason the credential split
   * gave when it put them beside the tokens in `ChronicleAuth.xml`: a marker and the data it must land
   * together or not at all. When the tokens moved to `no_backup/` the markers had to follow, or
   * that property would have been quietly lost — and `ChronicleAuth.xml` would be a file named for
   * contents it no longer holds.
   */
  fun flag(key: String): Boolean = snapshot.value[booleanPreferencesKey(key)] ?: false

  /** Sets a one-shot migration marker, durably. */
  fun setFlag(
    key: String,
    value: Boolean,
  ) {
    val k = booleanPreferencesKey(key)
    snapshot.value = snapshot.value.toMutablePreferences().apply { this[k] = value }
    runBlocking {
      runCatching { dataStore.edit { it[k] = value } }
        .onFailure { Timber.e(it, "Could not persist the ${'$'}key marker") }
    }
  }

  /** Drops every credential. The sign-out path. */
  fun clear() {
    snapshot.value = emptyPreferences()
    runBlocking {
      runCatching { dataStore.edit { it.clear() } }
        .onFailure { Timber.e(it, "Could not clear the credential store") }
    }
  }

  companion object {
    /** The file name, in `no_backup/`. Named for what it holds, since the path now carries the rule. */
    const val STORE_FILE = "plex-credentials.preferences_pb"

    /**
     * Builds the store, migrating `ChronicleAuth.xml` on first read.
     *
     * The migration is what moves an existing user's session across; without it, everyone signs in
     * again on upgrade, which D8 tolerates but nobody would thank us for.
     */
    fun create(
      context: Context,
      scope: CoroutineScope,
      legacyPrefsName: String,
    ): CredentialStore {
      val store =
        PreferenceDataStoreFactory.create(
          migrations = listOf(SharedPreferencesMigration(context, legacyPrefsName)),
          scope = scope,
          produceFile = { File(context.noBackupFilesDir, STORE_FILE) },
        )
      return CredentialStore(store, scope)
    }
  }
}
