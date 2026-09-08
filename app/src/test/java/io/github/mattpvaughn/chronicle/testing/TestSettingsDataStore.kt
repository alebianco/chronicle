package io.github.mattpvaughn.chronicle.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.mattpvaughn.chronicle.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File

/**
 * A [SettingsDataStore] over a real DataStore backed by a throwaway file.
 *
 * A real store rather than a fake, because the tests using it are about behaviour the store
 * provides — defaults, the same-frame read after a write, the migration from `SharedPreferences`.
 * A fake would assert against a reimplementation of the thing under test.
 *
 * `UnconfinedTestDispatcher` so writes complete before the next line: these tests read back
 * synchronously and are not exercising the write's asynchrony.
 */
fun testSettingsDataStore(name: String = "test-settings"): SettingsDataStore {
  val scope = CoroutineScope(Job() + UnconfinedTestDispatcher())
  val file = File.createTempFile(name, ".preferences_pb").apply { delete() }
  return SettingsDataStore(
    PreferenceDataStoreFactory.create(scope = scope) { file },
    scope,
  )
}
