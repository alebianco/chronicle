package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.IOException
import timber.log.Timber

/**
 * The settings store: DataStore for durability, a snapshot in memory for reading.
 *
 * **Why a snapshot rather than plain DataStore.** `PrefsRepo` exposes synchronous `var` properties
 * and 113 call sites read them directly — 21 from the player, including the line that sets
 * `ExoPlayer.skipSilenceEnabled` while configuring playback. DataStore is `suspend`/`Flow` only by
 * design, so those sites have three options: become `suspend` (they cannot; there is no coroutine
 * at an ExoPlayer setter), block on I/O (worse than the `SharedPreferences` in-memory read it
 * replaces), or read a snapshot kept current by a collector. The third is the only one that does
 * not make playback worse, so that is what this does.
 *
 * The snapshot is **not a cache in front of a slow store** — that framing is what makes this look
 * like a workaround. It is the same arrangement `SharedPreferences` already had: an in-memory map
 * that reads return immediately from, with writes going to disk asynchronously. The difference is
 * that DataStore's write path is transactional and its errors are observable, where
 * `SharedPreferences.apply()` swallowed both.
 *
 * **One-time migration.** [SharedPreferencesMigration] moves the existing `Chronicle.xml` on first
 * read and deletes it afterwards, so an upgrading user keeps every setting. The credentials file is
 * deliberately untouched here; it is a separate store with separate backup rules.
 */
class SettingsDataStore(
  private val dataStore: DataStore<Preferences>,
  scope: CoroutineScope,
) {
  private val _snapshot = MutableStateFlow(emptyPreferences())

  /** The current values, readable without suspending. */
  val snapshot: StateFlow<Preferences> = _snapshot

  init {
    // Seeded synchronously so the very first read — which happens during Application.onCreate,
    // before any collector has run — sees real values rather than defaults. A first frame drawn
    // from empty preferences is the FirstFrameFlashTest failure mode, one layer down.
    runBlocking {
      runCatching { _snapshot.value = dataStore.data.first() }
        .onFailure { Timber.e(it, "Could not seed the settings snapshot; using defaults") }
    }
    scope.launch {
      dataStore.data
        .catch { cause ->
          // A corrupt file must not take the app down. Emitting empty preferences means the user
          // sees defaults rather than a crash, and the next write repairs the file.
          if (cause is IOException) {
            Timber.e(cause, "Settings store unreadable; falling back to defaults")
            emit(emptyPreferences())
          } else {
            throw cause
          }
        }.collect { _snapshot.value = it }
    }
  }

  /** Reads the current value of [key], or [default] when it has never been written. */
  fun <T> get(
    key: Preferences.Key<T>,
    default: T,
  ): T = _snapshot.value[key] ?: default

  /** Observes [key]. Used by the screens that already collected a preference as a `Flow`. */
  fun <T> flow(
    key: Preferences.Key<T>,
    default: T,
  ): Flow<T> = snapshot.map { it[key] ?: default }.distinctUntilChanged()

  /**
   * Writes [key], updating the snapshot immediately.
   *
   * The snapshot moves first so a read that follows a write in the same frame sees the new value —
   * the property that `SharedPreferences` had for free and that callers here depend on. The disk
   * write follows on [scope]; it is transactional, and a failure is logged rather than swallowed.
   */
  fun <T> set(
    key: Preferences.Key<T>,
    value: T,
  ) {
    _snapshot.value = _snapshot.value.toMutablePreferences().apply { this[key] = value }
    writeScope.launch {
      runCatching { dataStore.edit { it[key] = value } }
        .onFailure { Timber.e(it, "Could not persist ${key.name}") }
    }
  }

  /** Applies several writes as one transaction, for the settings import path. */
  suspend fun edit(block: (MutablePreferences) -> Unit) {
    val updated = dataStore.edit(block)
    _snapshot.value = updated
  }

  private val writeScope = scope

  companion object {
    /**
     * Builds the store, migrating `Chronicle.xml` on first read.
     *
     * The file name matches the old one so the on-disk identity is recognisable, and so the backup
     * rules that name it keep meaning what they say — see `BackupRulesTest`.
     */
    fun create(
      context: Context,
      scope: CoroutineScope,
      legacyPrefsName: String,
    ): SettingsDataStore {
      val store =
        PreferenceDataStoreFactory.create(
          migrations = listOf(SharedPreferencesMigration(context, legacyPrefsName)),
          scope = scope,
          produceFile = { context.preferencesDataStoreFile(legacyPrefsName) },
        )
      return SettingsDataStore(store, scope)
    }

    /** For tests that still need to seed values through the old API. */
    fun legacyPrefs(
      context: Context,
      name: String,
    ): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
  }
}
