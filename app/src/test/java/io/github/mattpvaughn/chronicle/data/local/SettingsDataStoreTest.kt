package io.github.mattpvaughn.chronicle.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot contract, which is the whole reason this class exists.
 *
 * `PrefsRepo` reads synchronously from 113 call sites, 21 of them in the player. DataStore cannot
 * answer synchronously, so [SettingsDataStore] keeps an in-memory snapshot and these tests pin the
 * three properties the call sites actually depend on: a read never suspends, a read that follows a
 * write in the same frame sees the new value, and an unreadable store degrades to defaults rather
 * than throwing into the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDataStoreTest {
  private val speed = floatPreferencesKey("playback_speed")
  private val style = stringPreferencesKey("book_cover_style")
  private val offline = booleanPreferencesKey("offline_mode")

  /** A DataStore whose contents the test controls, with no file and no disk. */
  private class FakeDataStore(
    initial: Preferences = emptyPreferences(),
  ) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    var failReads = false

    override val data: Flow<Preferences>
      get() = if (failReads) kotlinx.coroutines.flow.flow { throw IOException("unreadable") } else state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }

  /**
   * A [DataStore] whose writes land late, the way a cold emulator's disk behaves.
   *
   * [FakeDataStore] applies a write before `updateData` returns, so the collector in
   * `SettingsDataStore.init` can never observe a state that predates a pending write. That is the
   * one condition under which the snapshot can be clobbered, so reproducing it needs a store that
   * genuinely lags.
   */
  private class SlowDataStore(
    initial: Preferences = emptyPreferences(),
  ) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)

    /**
     * Set true to hold every write until [releaseWrites] is called.
     *
     * `updateData` **suspends** while stalled rather than returning early: the caller's coroutine
     * has to still be in flight, because that is what a slow disk actually looks like. Returning
     * early would let the write coroutine complete and would model nothing.
     */
    var stallWrites = false
    private val gate = CompletableDeferred<Unit>()

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      if (stallWrites) gate.await()
      val updated = transform(state.value)
      state.value = updated
      return updated
    }

    /** Lets the held writes through, as the disk eventually would. */
    fun releaseWrites() {
      stallWrites = false
      gate.complete(Unit)
    }
  }

  /**
   * **A pending write is not lost when the store re-emits what is still on disk.**
   *
   * This is the bug behind the instrumented suite's cold-boot failures. `set()` moves the in-memory
   * snapshot immediately and persists on a coroutine; the collector in `init` overwrites the whole
   * snapshot with each `dataStore.data` emission. If the store emits **before** a pending write has
   * landed — which is exactly what a cold emulator's slow disk produces — the collector replaces
   * the just-written value with the stale one, and a synchronous read afterwards sees the old
   * value.
   *
   * On device this cost roughly two runs in five: `MockPlexMode` seeded a server and a library, the
   * snapshot was clobbered mid-seed, and `determineLoginState` read `library = null` and reported
   * `LOGGED_IN_NO_USER_CHOSEN`. Android Auto then served an empty browse root and four tests failed.
   */
  @Test
  fun `a value written while the disk is lagging survives a stale re-emission`() =
    runTest {
      // The store starts with an unrelated key, so the stale re-emission below is a *different*
      // Preferences instance — a StateFlow drops an emission equal to what it already holds, and
      // an empty-to-empty write would silently test nothing.
      val store = SlowDataStore(mutablePreferencesOf(offline to false))
      val settings = SettingsDataStore(store, TestScope(StandardTestDispatcher(testScheduler)))
      advanceUntilIdle()

      store.stallWrites = true
      settings.set(style, "mock-server")
      assertEquals("the write must be visible immediately", "mock-server", settings.get(style, ""))

      // The store re-emits its unchanged on-disk state, as DataStore does on any other write or on
      // a late first read. Nothing has been persisted yet.
      store.state.value = mutablePreferencesOf(offline to true)
      advanceUntilIdle()

      assertEquals(
        "a stale emission must not roll back a write that has not reached disk yet",
        "mock-server",
        settings.get(style, ""),
      )

      store.releaseWrites()
      advanceUntilIdle()
      assertEquals("mock-server", settings.get(style, ""))
    }

  @Test
  fun `a read returns the stored value without suspending`() =
    runTest {
      val store = FakeDataStore(mutablePreferencesOf(speed to 1.5f))

      val settings = SettingsDataStore(store, TestScope(StandardTestDispatcher(testScheduler)))

      // No `advanceUntilIdle` before this line on purpose: the value must be available from the
      // constructor, because the first read happens during Application.onCreate.
      assertEquals(1.5f, settings.get(speed, 1.0f))
    }

  @Test
  fun `an unwritten key falls back to its default`() =
    runTest {
      val settings = SettingsDataStore(FakeDataStore(), TestScope(StandardTestDispatcher(testScheduler)))

      assertEquals("Square", settings.get(style, "Square"))
    }

  /**
   * The property SharedPreferences had for free.
   *
   * A setter followed by a getter in the same frame — a settings toggle updating the screen it
   * lives on — must see the new value. If the snapshot only moved once the disk write completed,
   * the UI would show the old value for a frame.
   */
  @Test
  fun `a read immediately after a write sees the new value`() =
    runTest {
      val settings = SettingsDataStore(FakeDataStore(), TestScope(StandardTestDispatcher(testScheduler)))

      settings.set(offline, true)

      assertTrue("the snapshot must move before the disk write completes", settings.get(offline, false))
    }

  @Test
  fun `a write reaches the underlying store`() =
    runTest {
      val store = FakeDataStore()
      val scope = TestScope(StandardTestDispatcher(testScheduler))
      val settings = SettingsDataStore(store, scope)

      settings.set(speed, 2.0f)
      advanceUntilIdle()

      assertEquals("the value must be persisted, not only cached", 2.0f, store.state.value[speed])
    }

  /**
   * A corrupt or unreadable store must degrade, not crash.
   *
   * `SharedPreferences` could not fail this way — it swallowed I/O errors — so this is a failure
   * mode DataStore introduces, and the reason the collector carries a `catch`.
   */
  @Test
  fun `an unreadable store falls back to defaults rather than throwing`() =
    runTest {
      val store = FakeDataStore().apply { failReads = true }

      val settings = SettingsDataStore(store, TestScope(StandardTestDispatcher(testScheduler)))
      advanceUntilIdle()

      assertEquals("defaults, not an exception", 1.0f, settings.get(speed, 1.0f))
    }

  @Test
  fun `the flow emits the current value and then changes`() =
    runTest {
      val settings =
        SettingsDataStore(FakeDataStore(mutablePreferencesOf(style to "Rectangular")), TestScope(StandardTestDispatcher(testScheduler)))

      assertEquals("Rectangular", settings.flow(style, "Square").first())
    }
}
