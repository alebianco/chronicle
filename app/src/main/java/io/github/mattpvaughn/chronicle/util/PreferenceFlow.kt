package io.github.mattpvaughn.chronicle.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mattpvaughn.chronicle.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow

/**
 * A stored setting as a `Flow`.
 *
 * **These read the same store `PrefsRepo` writes.** They used to observe `SharedPreferences`
 * directly, which was correct until the settings moved to DataStore and quietly stopped being: a
 * screen watching `KEY_OFFLINE_MODE` on the XML would never see the write that went to DataStore,
 * so toggling offline mode, the sort order, playback speed or skip silence updated the store and
 * left the UI showing the old value. One store per concern is what prevents that class of bug, not
 * a preference about consistency.
 *
 * Two properties carried over from the `SharedPreferences` versions, both load-bearing:
 *
 * - The **current value is emitted first**. A screen that only learned the value on the next *edit*
 *   would render its default indefinitely — the `FirstFrameFlashTest` failure mode.
 * - `distinctUntilChanged`, so a write to any other key does not re-emit this one.
 *
 * The callback plumbing is gone: [SettingsDataStore] already exposes its snapshot as a `StateFlow`,
 * so there is no listener to register or unregister and no thread-confinement caveat to document.
 */
fun SettingsDataStore.booleanFlow(
  key: String,
  defaultValue: Boolean,
): Flow<Boolean> = flow(booleanPreferencesKey(key), defaultValue)

/** Exposes a string setting as a [Flow]. */
fun SettingsDataStore.stringFlow(
  key: String,
  defaultValue: String,
): Flow<String> = flow(stringPreferencesKey(key), defaultValue)

/** Exposes a float setting as a [Flow]. */
fun SettingsDataStore.floatFlow(
  key: String,
  defaultValue: Float,
): Flow<Float> = flow(floatPreferencesKey(key), defaultValue)
