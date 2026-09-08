package io.github.mattpvaughn.chronicle.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The flows read the same store `PrefsRepo` writes.
 *
 * This is the regression these tests exist for: the helpers used to observe `SharedPreferences`
 * directly, which was correct until the settings moved to DataStore and silently stopped being. A
 * screen watching `KEY_OFFLINE_MODE` on the XML never saw the write that went to DataStore, so
 * toggling offline mode, the sort order, playback speed or skip silence updated the store and left
 * the UI showing the old value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceFlowTest {
  @Test
  fun `a flow emits the current value before any change`() =
    runTest {
      // The FirstFrameFlashTest failure mode one layer down: a screen that only learned the value
      // on the next *edit* would render its default indefinitely.
      val settings = testSettingsDataStore("pref-flow-current")
      settings.set(booleanPreferencesKey("offline"), true)

      assertEquals(true, settings.booleanFlow("offline", false).first())
    }

  @Test
  fun `an unwritten key emits its default`() =
    runTest {
      val settings = testSettingsDataStore("pref-flow-default")

      assertEquals("Square", settings.stringFlow("cover_style", "Square").first())
    }

  @Test
  fun `a write through the store is visible to the flow`() =
    runTest {
      // The link that was broken: the writer and the observer must be the same store.
      val settings = testSettingsDataStore("pref-flow-write")

      settings.set(stringPreferencesKey("sort"), "author")

      assertEquals("author", settings.stringFlow("sort", "title").first())
    }

  @Test
  fun `a float setting round-trips`() =
    runTest {
      val settings = testSettingsDataStore("pref-flow-float")
      settings.set(androidx.datastore.preferences.core.floatPreferencesKey("speed"), 1.75f)

      assertEquals(1.75f, settings.floatFlow("speed", 1.0f).first())
    }
}
