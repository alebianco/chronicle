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

  /**
   * Keys whose write has been applied to [_snapshot] but has not yet reached disk.
   *
   * **This is what stops a slow disk from rolling a write back.** `set` moves the snapshot
   * immediately and persists on [writeScope]; the collector above replaces the whole snapshot with
   * each `dataStore.data` emission. Without this set, an emission that predates a pending write —
   * which is ordinary on a cold device, where the first disk write is slow — restores the *old*
   * value over the new one, and a synchronous read afterwards sees stale data.
   *
   * That is not hypothetical: it cost roughly two instrumented runs in five. `MockPlexMode`
   * seeded a server and a library, the snapshot was clobbered mid-seed, `determineLoginState` read
   * `library = null`, and Android Auto served an empty browse root.
   *
   * Guarded by [pendingLock] because the collector and the write coroutines are different jobs on
   * the same scope.
   *
   * **Declared above `init` on purpose.** The collector there reads them on its first emission,
   * which can happen before a property declared further down has been initialised — that reads as
   * `NullPointerException: Cannot enter synchronized block because "<local6>" is null`, thrown on a
   * coroutine thread where it surfaces as an unrelated test failing before it starts.
   */
  private val pendingWrites = mutableSetOf<Preferences.Key<*>>()
  private val pendingLock = Any()

  /** Re-applies anything still in flight on top of [this], which came from disk. */
  private fun Preferences.withPendingWrites(): Preferences {
    val live = synchronized(pendingLock) { pendingWrites.toList() }
    if (live.isEmpty()) return this
    val current = _snapshot.value
    return toMutablePreferences().apply {
      live.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val typed = key as Preferences.Key<Any>
        val value = current[typed]
        if (value != null) this[typed] = value else remove(typed)
      }
    }
  }

  init {
    // Keys the settings store must never hold. `SharedPreferencesMigration` copies a whole prefs
    // file, so anything that used to share `Chronicle.xml` came across — including `uuid`, the
    // client identifier that identifies this install to Plex. That belongs with the credentials in
    // `no_backup/`: this store *is* backed up, so a restore onto a second device would give both
    // installs the same identity. Stripped on every start, because a stale copy is enough.
    val notSettings = setOf("uuid", "auth_token", "server_token", "user")
    // Seeded synchronously so the very first read — which happens during Application.onCreate,
    // before any collector has run — sees real values rather than defaults. A first frame drawn
    // from empty preferences is the FirstFrameFlashTest failure mode, one layer down.
    runBlocking {
      runCatching { _snapshot.value = dataStore.data.first() }
        .onFailure { Timber.e(it, "Could not seed the settings snapshot; using defaults") }
    }
    scope.launch {
      runCatching {
        val present = notSettings.filter { name -> snapshotHasKey(name) }
        if (present.isNotEmpty()) {
          Timber.i("Removing ${present.size} credential key(s) that do not belong in the settings store")
          present.forEach { remove(it) }
        }
      }.onFailure { Timber.e(it, "Could not strip credential keys from the settings store") }
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
        }.collect { fromDisk -> _snapshot.value = fromDisk.withPendingWrites() }
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
    val previous = _snapshot.value[key]
    _snapshot.value = _snapshot.value.toMutablePreferences().apply { this[key] = value }
    if (previous != value) notifyChanged(key.name)
    synchronized(pendingLock) { pendingWrites += key }
    writeScope.launch {
      runCatching { dataStore.edit { it[key] = value } }
        .onFailure { Timber.e(it, "Could not persist ${key.name}") }
      synchronized(pendingLock) { pendingWrites -= key }
    }
  }

  /** Every stored setting, keyed by name — the shape `SharedPreferences.all` had. */
  fun all(): Map<String, Any> = _snapshot.value.asMap().entries.associate { it.key.name to it.value }

  /** True when [key] has ever been written. */
  fun contains(key: String): Boolean = _snapshot.value.asMap().keys.any { it.name == key }

  private fun snapshotHasKey(name: String): Boolean = _snapshot.value.asMap().keys.any { it.name == name }

  /** Removes one setting. */
  fun remove(key: String) {
    // The key type does not matter for removal — Preferences keys compare by name — so this takes
    // a String and lets callers drop a value without knowing which typed key wrote it.
    val existing = _snapshot.value.asMap().keys.firstOrNull { it.name == key } ?: return
    _snapshot.value = _snapshot.value.toMutablePreferences().apply { remove(existing) }
    // Tracked like a write: a removal still in flight must not be undone by a disk emission that
    // predates it. `withPendingWrites` reads the absence from the snapshot and re-applies it.
    synchronized(pendingLock) { pendingWrites += existing }
    writeScope.launch {
      runCatching { dataStore.edit { prefs -> prefs.remove(existing) } }
        .onFailure { Timber.e(it, "Could not remove $key") }
      synchronized(pendingLock) { pendingWrites -= existing }
    }
  }

  /** Removes every setting. Used by the "reset settings" path. */
  fun clear() {
    _snapshot.value = emptyPreferences()
    writeScope.launch {
      runCatching { dataStore.edit { it.clear() } }
        .onFailure { Timber.e(it, "Could not clear the settings store") }
    }
  }

  /**
   * Change listeners, in the shape `PrefsRepo` already exposes.
   *
   * `PrefsRepo` types this as `SharedPreferences.OnSharedPreferenceChangeListener`, which leaks the
   * old implementation into its own contract — worth retiring, but not in the same change as the
   * storage swap, where a listener bug and a storage bug would be indistinguishable. So the
   * listeners are driven from the snapshot instead: every write diffs the previous preferences
   * against the new ones and notifies for each key that actually changed.
   *
   * The `null` SharedPreferences argument is safe because every call site ignores it and switches
   * on the key alone — checked across all four before writing this.
   */
  private val changeListeners =
    java.util.Collections.newSetFromMap(
      java.util.concurrent.ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>(),
    )

  fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
    changeListeners.add(listener)
  }

  fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
    changeListeners.remove(listener)
  }

  private fun notifyChanged(key: String) {
    changeListeners.forEach { runCatching { it.onSharedPreferenceChanged(null, key) } }
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
