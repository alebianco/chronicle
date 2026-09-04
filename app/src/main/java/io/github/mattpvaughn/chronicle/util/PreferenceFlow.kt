package io.github.mattpvaughn.chronicle.util

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A `SharedPreferences` value as a `Flow` (cu-52), replacing the
 * `Boolean`/`String`/`Float`PreferenceLiveData trio.
 *
 * `callbackFlow` is the direct analogue of `LiveData.onActive`/`onInactive`: it registers the
 * listener when collection starts and `awaitClose` unregisters it when collection stops, so the
 * lifetime is tied to the collector rather than to an observer count.
 *
 * Two details carried over deliberately:
 *
 * - The **current value is emitted first**, before any change arrives. The LiveData versions did
 *   this in `onActive`, and a screen that only learned the value on the next *edit* would render
 *   its default indefinitely — the `FirstFrameFlashTest` failure mode (cu-68).
 * - `SharedPreferences` fires its listener on whichever thread called `apply()`, which is why the
 *   LiveData versions could not use `value =` safely. A `Flow` has no such constraint: `trySend`
 *   is thread-safe, and the collector resumes on its own dispatcher.
 *
 * `distinctUntilChanged` because `apply()` notifies for every write to the file, not only for
 * writes that changed *this* key.
 */
private fun <T> SharedPreferences.preferenceFlow(
  key: String,
  read: SharedPreferences.() -> T,
): Flow<T> =
  callbackFlow {
    trySend(read())
    val listener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
        if (changed == key) trySend(read())
      }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
  }.distinctUntilChanged()

/** Exposes a boolean in [SharedPreferences] as a [Flow]. */
fun SharedPreferences.booleanFlow(
  key: String,
  defaultValue: Boolean,
): Flow<Boolean> = preferenceFlow(key) { getBoolean(key, defaultValue) }

/** Exposes a string in [SharedPreferences] as a [Flow]. */
fun SharedPreferences.stringFlow(
  key: String,
  defaultValue: String,
): Flow<String> = preferenceFlow(key) { getString(key, defaultValue) ?: defaultValue }

/** Exposes a float in [SharedPreferences] as a [Flow]. */
fun SharedPreferences.floatFlow(
  key: String,
  defaultValue: Float,
): Flow<Float> = preferenceFlow(key) { getFloat(key, defaultValue) }
