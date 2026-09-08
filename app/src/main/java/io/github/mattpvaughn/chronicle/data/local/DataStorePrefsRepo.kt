package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_ALLOW_AUTO
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_AUTO_RESTART_SLEEP_TIMER
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_AUTO_REWIND_ENABLED
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_BOOK_COVER_STYLE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_BOOK_SORT_BY
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_DEBUG_DISABLE_PROGRESS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_HIDE_PLAYED_AUDIOBOOKS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_IS_LIBRARY_SORT_DESCENDING
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_JUMP_BACKWARD_SECONDS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_JUMP_FORWARD_SECONDS
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_LAST_REFRESH
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_LIBRARY_MEDIA_TYPE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_LIBRARY_VIEW_STYLE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_OFFLINE_MODE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_PAUSE_ON_FOCUS_LOST
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_PLAYBACK_SPEED
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_REFRESH_RATE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_SHAKE_TO_SNOOZE_ENABLED
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_SKIP_SILENCE
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.KEY_SYNC_DIR_PATH
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.LIBRARY_MEDIA_TYPES
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.LIBRARY_MEDIA_TYPE_BOOK
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLES
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel.Companion.PLAYBACK_SPEED_DEFAULT
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * [PrefsRepo] over DataStore.
 *
 * The properties stay synchronous. 113 call sites read them directly and 21 of those are in the
 * player — `MediaPlayerService` reads `skipSilence` while configuring ExoPlayer, where there is no
 * coroutine to suspend in. [SettingsDataStore] holds the snapshot that makes that possible; see its
 * documentation for why that is the same arrangement `SharedPreferences` already had rather than a
 * cache bolted onto a slow store.
 *
 * **Every default and every validation is carried across unchanged.** The defaults are the app's
 * behaviour on a fresh install, and the two `IllegalArgumentException`s guard against a sort key or
 * view style that no screen can render.
 */
class DataStorePrefsRepo
  @Inject
  constructor(
    private val settings: SettingsDataStore,
    private val externalDeviceDirs: List<@JvmSuppressWildcards File>,
    private val appContext: Context,
  ) : PrefsRepo {
    private val syncDirKey = stringPreferencesKey(KEY_SYNC_DIR_PATH)

    /**
     * The directory downloads live in.
     *
     * Returns the **stored** path whenever one is set, even if that volume is not currently
     * mounted. It used to fall back to `externalDeviceDirs().first()` when the stored path was not
     * in the current list, which is the subtler half of the SD-card-removed bug: with an SD card
     * removed, this returned a *different, readable* directory, the cache scan found none of the
     * expected files there, and it un-cached the whole library. Returning the real (absent) path
     * lets `scanCachedMediaDir` report `Unavailable` and change nothing.
     */
    override var cachedMediaDir: File
      get() {
        val stored = settings.get(syncDirKey, "")
        if (stored.isNotEmpty()) {
          return File(stored)
        }
        val deviceStorage = externalDeviceDirs.firstOrNull() ?: appContext.filesDir
        settings.set(syncDirKey, deviceStorage.absolutePath)
        return deviceStorage
      }
      set(value) = settings.set(syncDirKey, value.absolutePath)

    override var bookCoverStyle: String by string(KEY_BOOK_COVER_STYLE, "Square")
    override var offlineMode: Boolean by boolean(KEY_OFFLINE_MODE, false)
    override var lastRefreshTimeStamp: Long by long(KEY_LAST_REFRESH, System.currentTimeMillis())
    override var refreshRateMinutes: Long by long(KEY_REFRESH_RATE, 60L)
    override var jumpForwardSeconds: Long by long(KEY_JUMP_FORWARD_SECONDS, 30L)
    override var jumpBackwardSeconds: Long by long(KEY_JUMP_BACKWARD_SECONDS, 10L)
    override var playbackSpeed: Float by float(KEY_PLAYBACK_SPEED, PLAYBACK_SPEED_DEFAULT)
    override var skipSilence: Boolean by boolean(KEY_SKIP_SILENCE, false)
    override var autoRewind: Boolean by boolean(KEY_AUTO_REWIND_ENABLED, true)
    override var shakeToSnooze: Boolean by boolean(KEY_SHAKE_TO_SNOOZE_ENABLED, true)
    override var autoRestartSleepTimer: Boolean by boolean(KEY_AUTO_RESTART_SLEEP_TIMER, true)
    override var pauseOnFocusLost: Boolean by boolean(KEY_PAUSE_ON_FOCUS_LOST, true)
    override var allowAuto: Boolean by boolean(KEY_ALLOW_AUTO, true)
    override var isLibrarySortedDescending: Boolean by boolean(KEY_IS_LIBRARY_SORT_DESCENDING, true)
    override var hidePlayedAudiobooks: Boolean by boolean(KEY_HIDE_PLAYED_AUDIOBOOKS, false)
    override var debugOnlyDisableLocalProgressTracking: Boolean by
      boolean(KEY_DEBUG_DISABLE_PROGRESS, false)

    override var bookSortKey: String by
      validatedString(KEY_BOOK_SORT_BY, Audiobook.SORT_KEY_TITLE, Audiobook.SORT_KEYS) {
        "Unknown sort key: $it"
      }

    override var libraryMediaType: String by
      validatedString(KEY_LIBRARY_MEDIA_TYPE, LIBRARY_MEDIA_TYPE_BOOK, LIBRARY_MEDIA_TYPES) {
        "Unknown view type key: $it"
      }

    override var libraryBookViewStyle: String by
      validatedString(KEY_LIBRARY_VIEW_STYLE, VIEW_STYLE_COVER_GRID, VIEW_STYLES) {
        "Unknown view type key: $it"
      }

    override fun setBoolean(
      key: String,
      value: Boolean,
    ) = settings.set(booleanPreferencesKey(key), value)

    override fun getBoolean(
      key: String,
      defaultValue: Boolean,
    ): Boolean = settings.get(booleanPreferencesKey(key), defaultValue)

    override fun clearAll() = settings.clear()

    override fun containsKey(key: String): Boolean = settings.contains(key)

    /**
     * The listener API, kept because four call sites use it — three ViewModels and
     * `MediaPlayerService`.
     *
     * Implemented over the snapshot rather than changing the interface: `PrefsRepo` currently
     * exposes `SharedPreferences.OnSharedPreferenceChangeListener`, which leaks the old
     * implementation into its own contract. Retiring that is worth doing, but doing it in the same
     * change as the storage swap would mean a listener bug and a storage bug are
     * indistinguishable.
     */
    private val listeners =
      ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()

    override fun registerPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
      listeners[listener] = Unit
      settings.addChangeListener(listener)
    }

    override fun unregisterPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
      listeners.remove(listener)
      settings.removeChangeListener(listener)
    }

    // --- delegates -------------------------------------------------------------------------
    //
    // The properties are uniform — a typed key, a default, and for three of them a validation —
    // so they are expressed once rather than twenty times. The previous implementation repeated
    // the get/set pair per property, which is where a default could drift from its documentation
    // without anything noticing.

    private fun boolean(
      key: String,
      default: Boolean,
    ) = object : ReadWriteProperty<Any?, Boolean> {
      private val k = booleanPreferencesKey(key)

      override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
      ) = settings.get(k, default)

      override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Boolean,
      ) = settings.set(k, value)
    }

    private fun long(
      key: String,
      default: Long,
    ) = object : ReadWriteProperty<Any?, Long> {
      private val k = longPreferencesKey(key)

      override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
      ) = settings.get(k, default)

      override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Long,
      ) = settings.set(k, value)
    }

    private fun float(
      key: String,
      default: Float,
    ) = object : ReadWriteProperty<Any?, Float> {
      private val k = floatPreferencesKey(key)

      override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
      ) = settings.get(k, default)

      override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: Float,
      ) = settings.set(k, value)
    }

    private fun string(
      key: String,
      default: String,
    ) = object : ReadWriteProperty<Any?, String> {
      private val k = stringPreferencesKey(key)

      override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
      ) = settings.get(k, default)

      override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: String,
      ) = settings.set(k, value)
    }

    /**
     * A string that must be one of [allowed].
     *
     * The throw is deliberate and carried across unchanged: a sort key or view style outside the
     * known set is a programming error that no screen can render, and failing at the write is how
     * it stays findable.
     */
    private fun validatedString(
      key: String,
      default: String,
      allowed: List<String>,
      message: (String) -> String,
    ) = object : ReadWriteProperty<Any?, String> {
      private val k = stringPreferencesKey(key)

      override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
      ) = settings.get(k, default)

      override fun setValue(
        thisRef: Any?,
        property: KProperty<*>,
        value: String,
      ) {
        require(value in allowed) { message(value) }
        settings.set(k, value)
      }
    }
  }
