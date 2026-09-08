package io.github.mattpvaughn.chronicle.data.local

import android.content.SharedPreferences
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.LIBRARY_MEDIA_TYPES
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.LIBRARY_MEDIA_TYPE_BOOK
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLES
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import java.io.File

/**
 * An interface for getting/setting persistent preferences for Chronicle
 */
interface PrefsRepo {
  /** The directory where media files are synced */
  var cachedMediaDir: File

  /** The style of book covers in the app- i.e. rectangular, square */
  var bookCoverStyle: String

  /** Whether the app should be able to access the network */
  var offlineMode: Boolean

  /** The user's preferred speed of audio playback */
  var playbackSpeed: Float

  /** Whether the user has given access to Auto */
  var allowAuto: Boolean

  /** Whether to fast-forward through silent bits of audio during playback */
  var skipSilence: Boolean

  /** Whether the app should rewind a small bit if user hasn't played an audiobook in a while */
  var autoRewind: Boolean

  /** Whether the app will extend the sleep timer upon device shake */
  var shakeToSnooze: Boolean

  /**
   * Whether an expired sleep timer re-arms itself when playback resumes.
   *
   * Defaults **on**: the timer firing means "I fell asleep", and the usual next action is to resume
   * and want the same timer again. A preference exists because silently re-arming a timer the user
   * believes they dismissed would be worse than not re-arming at all.
   */
  var autoRestartSleepTimer: Boolean

  /** Pause when audio focus lost */
  var pauseOnFocusLost: Boolean

  /** The last time the library was refreshed, as Unix timestamp (in millis) */
  var lastRefreshTimeStamp: Long

  /** The minimum number of minutes between data refreshes*/
  var refreshRateMinutes: Long

  /** The time interval for jumping forward in the player view.*/
  var jumpForwardSeconds: Long

  /** The time interval for jumping backward in the player view.*/
  var jumpBackwardSeconds: Long

  /** The key by which the books in the library are sorted. One of [Audiobook.SORT_KEYS] */
  var bookSortKey: String

  /** The type of elements shown in the library view. One of [MediaType.TYPES]*/
  var libraryMediaType: String

  /** The style of view display in the library view (e.g. book cover, text only, etc.) */
  var libraryBookViewStyle: String

  /** Whether the library is sorted in descending (true) or ascending (false) order */
  var isLibrarySortedDescending: Boolean

  /** Whether played audiobooks should be hidden in the library */
  var hidePlayedAudiobooks: Boolean

  /**
   * Get a saved preference value corresponding to [key], providing [defaultValue] if no value
   * is already set. Return false in the case of no value already set if [defaultValue] is not
   * provided
   */
  fun getBoolean(
    key: String,
    defaultValue: Boolean = false,
  ): Boolean

  /** Save a preference with key == [key] and value == [value] to the preferences repo */
  fun setBoolean(
    key: String,
    value: Boolean,
  )

  /** Clear all saved preferences */
  fun clearAll()

  /** Register an [SharedPreferences.OnSharedPreferenceChangeListener] */
  fun registerPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener)

  /** Unregister an already registered [SharedPreferences.OnSharedPreferenceChangeListener] */
  fun unregisterPrefsListener(listener: SharedPreferences.OnSharedPreferenceChangeListener)

  fun containsKey(key: String): Boolean

  /** Disable progress tracking in the local DB for debugging purposes */
  var debugOnlyDisableLocalProgressTracking: Boolean

  companion object {
    const val KEY_SYNC_DIR_PATH = "key_sync_location"
    const val KEY_BOOK_COVER_STYLE = "key_book_cover_style"
    const val KEY_APP_OPEN_COUNT = "key_app_open_count"
    const val KEY_OFFLINE_MODE = "key_offline_mode"
    const val KEY_LAST_REFRESH = "key_last_refresh"
    const val KEY_REFRESH_RATE = "key_refresh_rate"
    const val KEY_JUMP_FORWARD_SECONDS = "key_jump_forward_seconds"
    const val KEY_JUMP_BACKWARD_SECONDS = "key_jump_backward_seconds"
    const val KEY_PLAYBACK_SPEED = "key_playback_speed"
    const val KEY_DEBUG_DISABLE_PROGRESS = "debug_key_disable_local_progress"
    const val KEY_SKIP_SILENCE = "key_skip_silence"
    const val KEY_AUTO_REWIND_ENABLED = "key_auto_rewind_enabled"
    const val KEY_ALLOW_AUTO = "key_allow_auto"
    const val KEY_SHAKE_TO_SNOOZE_ENABLED = "key_shake_to_snooze_enabled"
    const val KEY_AUTO_RESTART_SLEEP_TIMER = "key_auto_restart_sleep_timer"
    const val KEY_PAUSE_ON_FOCUS_LOST = "key_pause_on_focus_lost"
    const val KEY_BOOK_SORT_BY = "key_sort_by"
    const val KEY_IS_LIBRARY_SORT_DESCENDING = "key_is_sort_descending"
    const val KEY_HIDE_PLAYED_AUDIOBOOKS = "key_hide_played_audiobooks"
    const val KEY_LIBRARY_MEDIA_TYPE = "key_media_type"
    const val KEY_LIBRARY_VIEW_STYLE = "key_library_view_style"
    const val VIEW_STYLE_COVER_GRID = "view_style_cover_grid"
    const val VIEW_STYLE_TEXT_LIST = "view_style_text_list"
    const val VIEW_STYLE_DETAILS_LIST = "view_style_details_list"
    val VIEW_STYLES =
      listOf(
        VIEW_STYLE_COVER_GRID,
        VIEW_STYLE_DETAILS_LIST,
        VIEW_STYLE_TEXT_LIST,
      )

    const val LIBRARY_MEDIA_TYPE_BOOK = "book"
    const val LIBRARY_MEDIA_TYPE_AUTHOR = "author"
    const val LIBRARY_MEDIA_TYPE_FOLDER = "folder"
    const val LIBRARY_MEDIA_TYPE_COLLECTION = "collection"

    /**
     * The values [PrefsRepo.libraryMediaType] accepts.
     *
     * Published beside [VIEW_STYLES] because settings *import* has to agree with the setter about
     * what is valid. These lived as a private `viewTypes` list in the implementation, so
     * the importer could not see them and wrote unvalidated strings straight to prefs.
     */
    val LIBRARY_MEDIA_TYPES =
      listOf(
        LIBRARY_MEDIA_TYPE_BOOK,
        LIBRARY_MEDIA_TYPE_AUTHOR,
        LIBRARY_MEDIA_TYPE_FOLDER,
        LIBRARY_MEDIA_TYPE_COLLECTION,
      )

    const val BOOK_COVER_STYLE_SQUARE = "Square"
    const val BOOK_COVER_STYLE_RECT = "Rectangular"

    /** The values [PrefsRepo.bookCoverStyle] accepts. See [LIBRARY_MEDIA_TYPES] for why. */
    val BOOK_COVER_STYLES = listOf(BOOK_COVER_STYLE_SQUARE, BOOK_COVER_STYLE_RECT)
  }
}

/**
 *  An implementation of [PrefsRepo] wrapping [SharedPreferences]
 */
