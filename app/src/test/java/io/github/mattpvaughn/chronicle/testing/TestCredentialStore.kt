package io.github.mattpvaughn.chronicle.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.mattpvaughn.chronicle.data.sources.plex.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File

/**
 * A [CredentialStore] over a real DataStore backed by a throwaway file.
 *
 * Real rather than fake for the same reason as the settings equivalent: the tests using it are
 * about the fallback order across the three credential layers, and a fake would be asserting
 * against a reimplementation of the thing under test.
 */
fun testCredentialStore(name: String = "test-credentials"): CredentialStore {
  val scope = CoroutineScope(Job() + UnconfinedTestDispatcher())
  val file = File.createTempFile(name, ".preferences_pb").apply { delete() }
  return CredentialStore(
    PreferenceDataStoreFactory.create(scope = scope) { file },
  )
}
