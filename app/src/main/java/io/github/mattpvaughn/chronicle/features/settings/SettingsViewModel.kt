package io.github.mattpvaughn.chronicle.features.settings

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.*
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.FEATURE_FLAG_IS_AUTO_ENABLED
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SettingsBackupRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.features.download.MoveSyncLocationWorker
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.settings.SettingsViewModel.NavigationDestination.*
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.bytesAvailable
import io.github.mattpvaughn.chronicle.util.postEvent
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.*
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Represents the UI state of the settings screen. Responsible for loading and displaying
 * [PreferenceModel]s.
 *
 * Note: not using the built-in [PreferenceFragment] because making custom preferences is horrible
 *       and custom views or pop-ups are fine. This could be improved, though, it's quite indented
 *
 * TODO: Quite a bit of repetition of information in [PreferenceModel], making typos more likely.
 *       Might be worthwhile to look into alternatives used by other apps?
 */
class SettingsViewModel(
  private val bookRepository: IBookRepository,
  private val trackRepository: ITrackRepository,
  private val mediaServiceConnection: MediaServiceConnection,
  private val prefsRepo: PrefsRepo,
  private val plexLoginRepo: IPlexLoginRepo,
  private val cachedFileManager: ICachedFileManager,
  private val plexConfig: PlexConfig,
  private val workManager: WorkManager,
  private val plexPrefs: PlexPrefsRepo,
  private val collectionsRepository: CollectionsRepository,
  private val settingsBackupRepo: SettingsBackupRepo,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val trackRepository: ITrackRepository,
      private val prefsRepo: PrefsRepo,
      private val mediaServiceConnection: MediaServiceConnection,
      private val plexLoginRepo: IPlexLoginRepo,
      private val cachedFileManager: ICachedFileManager,
      private val plexConfig: PlexConfig,
      private val workManager: WorkManager,
      private val plexPrefs: PlexPrefsRepo,
      private val collectionsRepository: CollectionsRepository,
      private val settingsBackupRepo: SettingsBackupRepo,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
          return SettingsViewModel(
            bookRepository = bookRepository,
            trackRepository = trackRepository,
            mediaServiceConnection = mediaServiceConnection,
            prefsRepo = prefsRepo,
            plexLoginRepo = plexLoginRepo,
            cachedFileManager = cachedFileManager,
            plexConfig = plexConfig,
            workManager = workManager,
            plexPrefs = plexPrefs,
            collectionsRepository = collectionsRepository,
            settingsBackupRepo = settingsBackupRepo,
          ) as T
        } else {
          throw IllegalArgumentException(
            "Cannot instantiate $modelClass from SettingsViewModel.Factory",
          )
        }
      }
    }

  private var _preferences = MutableLiveData(makePreferences())
  val preferences: LiveData<List<PreferenceModel>>
    get() = _preferences

  private var _bottomChooserState = MutableLiveData(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: LiveData<BottomChooserState>
    get() = _bottomChooserState

  fun setBottomSheetVisibility(shouldShow: Boolean) {
    bottomChooserState.value?.let {
      _bottomChooserState.postValue(it.copy(shouldShow = shouldShow))
    }
  }

  private var _messageForUser = MutableLiveData<Event<FormattableString>>()
  val messageForUser: LiveData<Event<FormattableString>>
    get() = _messageForUser

  private var _webLink = MutableLiveData<Event<String>>()
  val webLink: LiveData<Event<String>>
    get() = _webLink

  private var _showLicenseActivity = MutableLiveData(false)
  val showLicenseActivity: LiveData<Boolean>
    get() = _showLicenseActivity

  /**
   * Asks the fragment to open the system "create document" picker, carrying the default filename.
   *
   * An event rather than a state flag: the picker must open exactly once per tap, and a
   * [MutableLiveData] the fragment re-reads on a configuration change would open it again.
   * Launching it is the fragment's job because only a `Fragment` owns an
   * `ActivityResultLauncher`.
   */
  private var _exportFileRequest = MutableLiveData<Event<String>>()
  val exportFileRequest: LiveData<Event<String>>
    get() = _exportFileRequest

  /** Asks the fragment to open the system "open document" picker. */
  private var _importFileRequest = MutableLiveData<Event<Unit>>()
  val importFileRequest: LiveData<Event<Unit>>
    get() = _importFileRequest

  private fun showOptionsMenu(
    options: List<FormattableString>,
    title: FormattableString,
    listener: BottomChooserListener,
  ) {
    _bottomChooserState.postValue(
      BottomChooserState(
        options = options,
        title = title,
        listener = listener,
        shouldShow = true,
      ),
    )
  }

  private val prefsListener =
    OnSharedPreferenceChangeListener { _, _ ->
      // Rebuild the prefs list whenever any prefs change
      _preferences.postValue(makePreferences())
    }

  init {
    prefsRepo.registerPrefsListener(prefsListener)
  }

  override fun onCleared() {
    prefsRepo.unregisterPrefsListener(prefsListener)
  }

  /**
   * Renders [refreshRateLabel]'s answer with the app's resources.
   *
   * The decision lives in `RefreshRate.kt` and is unit-tested; only the string lookup is here,
   * because that is the part needing a `Context`. Keeping them apart is what took this out of the
   * service locator's reach (cu-101).
   */
  private fun formatRefreshRate(minutes: Long): String {
    val resources = Injector.get().applicationContext().resources
    return when (val label = refreshRateLabel(minutes)) {
      is RefreshRateLabel.Named -> resources.getString(label.stringRes)
      is RefreshRateLabel.Quantity -> "${label.count} ${resources.getString(label.unitRes)}"
    }
  }

  /**
   * Names [stored] using the chooser's own localized label.
   *
   * The decision lives in `BookCoverStyle.kt` and is unit-tested; only the string lookup is here.
   * An unrecognized value falls back to the default rather than throwing — a pre-fix install can
   * hold `"Rectangle"`, and the key is importable with no value validation (cu-133).
   */
  private fun formatBookCoverStyle(stored: String): String =
    Injector.get().applicationContext().resources
      .getString(BookCoverStyle.ofStoredOrDefault(stored).choiceRes)

  private fun makePreferences(): List<PreferenceModel> {
    val list =
      mutableListOf(
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_appearance),
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title =
            FormattableString.ResourceString(
              stringRes = R.string.settings_book_cover_type_value,
              // The chooser's own localized label, not the persisted English literal. The
              // literal is what made a stored "Rectangle" read back as "Rectangle" under an
              // option offered as "Rectangular" (cu-101).
              placeHolderStrings =
                listOf(
                  formatBookCoverStyle(prefsRepo.bookCoverStyle),
                ),
            ),
          explanation =
            FormattableString.from(
              R.string.settings_book_cover_type_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options =
                    BookCoverStyle.choices.map {
                      FormattableString.from(it.choiceRes)
                    },
                  title =
                    FormattableString.from(
                      R.string.settings_book_cover_type_label,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        check(
                          formattableString is FormattableString.ResourceString,
                        )

                        // Throws rather than silently ignoring, matching the refresh-rate
                        // chooser below: the options come from BookCoverStyle.choices, so an
                        // unrecognized resource here means a wiring mistake, not user input.
                        val style =
                          BookCoverStyle.ofChoice(formattableString.stringRes)
                            ?: throw NoWhenBranchMatchedException(
                              "Unknown item: ${formattableString.stringRes}",
                            )
                        prefsRepo.bookCoverStyle = style.stored
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_sync),
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title =
            FormattableString.ResourceString(
              stringRes = R.string.settings_refresh_rate_value,
              placeHolderStrings = listOf(formatRefreshRate(prefsRepo.refreshRateMinutes)),
            ),
          explanation =
            FormattableString.from(
              R.string.settings_refresh_rate_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options = RefreshRate.choices.map { FormattableString.from(it.choiceRes) },
                  title =
                    FormattableString.from(
                      R.string.settings_refresh_rate_title,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        check(
                          formattableString is FormattableString.ResourceString,
                        )
                        val rate =
                          RefreshRate.ofChoice(formattableString.stringRes)
                            ?: throw NoWhenBranchMatchedException(
                              "Unknown item: ${formattableString.stringRes}",
                            )
                        prefsRepo.refreshRateMinutes = rate.minutes
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title =
            FormattableString.ResourceString(
              stringRes = R.string.settings_sync_location_value,
              placeHolderStrings =
                listOf(
                  Formatter.formatFileSize(
                    Injector.get().applicationContext(),
                    prefsRepo.cachedMediaDir.bytesAvailable(),
                  ),
                ),
            ),
          explanation =
            FormattableString.from(
              R.string.settings_sync_location_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options =
                    Injector.get().externalDeviceDirs().map {
                      FormattableString.ResourceString(
                        stringRes = R.string.settings_sync_space_available,
                        placeHolderStrings =
                          listOf(
                            it.path,
                            Formatter.formatFileSize(
                              Injector.get().applicationContext(),
                              it.bytesAvailable(),
                            ),
                          ),
                      )
                    },
                  title =
                    FormattableString.from(
                      R.string.settings_sync_location_title,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        check(
                          formattableString is FormattableString.ResourceString,
                        )

                        val chosen = formattableString.placeHolderStrings[0]
                        val syncLoc =
                          Injector.get().externalDeviceDirs().firstOrNull {
                            chosen.contains(it.path)
                          }
                        if (syncLoc != null) {
                          setSyncLocation(syncLoc)
                        }
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_delete_synced_title),
          explanation =
            FormattableString.from(
              R.string.settings_delete_synced_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options = listOf(FormattableString.yes, FormattableString.no),
                  title =
                    FormattableString.from(
                      R.string.settings_delete_synced_confirm,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        when (formattableString) {
                          FormattableString.yes -> {
                            viewModelScope.launch {
                              val deletedFileCount =
                                cachedFileManager.uncacheAllInLibrary()
                              showUserMessage(
                                FormattableString.ResourceString(
                                  R.string.settings_delete_synced_response,
                                  placeHolderStrings =
                                    listOf(
                                      deletedFileCount.toString(),
                                    ),
                                ),
                              )
                            }
                          }
                          else -> {
                          } // do nothing
                        }
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_backup),
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_backup_export_title),
          explanation = FormattableString.from(R.string.settings_backup_export_explanation),
          click =
            object : PreferenceClick {
              override fun onClick() {
                // Export needs no confirmation: it writes a new file the user names, and
                // overwriting is the picker's own prompt to make.
                _exportFileRequest.postEvent(defaultBackupFileName())
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_backup_import_title),
          explanation = FormattableString.from(R.string.settings_backup_import_explanation),
          click =
            object : PreferenceClick {
              override fun onClick() {
                // Import replaces settings, so it warns first — the same yes/no shape as
                // deleting synced files above.
                showOptionsMenu(
                  options = listOf(FormattableString.yes, FormattableString.no),
                  title = FormattableString.from(R.string.settings_backup_import_confirm),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        if (formattableString == FormattableString.yes) {
                          _importFileRequest.postEvent(Unit)
                        }
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          PreferenceType.BOOLEAN,
          FormattableString.from(R.string.settings_offline_mode_title),
          PrefsRepo.KEY_OFFLINE_MODE,
          defaultValue = prefsRepo.offlineMode,
        ),
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_playback),
        ),
        PreferenceModel(
          PreferenceType.BOOLEAN,
          FormattableString.from(R.string.settings_skip_silent_audio),
          PrefsRepo.KEY_SKIP_SILENCE,
          defaultValue = prefsRepo.skipSilence,
        ),
        PreferenceModel(
          PreferenceType.BOOLEAN,
          FormattableString.from(R.string.settings_auto_rewind),
          PrefsRepo.KEY_AUTO_REWIND_ENABLED,
          FormattableString.from(R.string.settings_auto_rewind_explanation),
          defaultValue = prefsRepo.autoRewind,
        ),
        PreferenceModel(
          type = PreferenceType.BOOLEAN,
          title = FormattableString.from(R.string.settings_shake_to_snooze_title),
          explanation =
            FormattableString.from(
              R.string.settings_shake_to_snooze_explanation,
            ),
          key = PrefsRepo.KEY_SHAKE_TO_SNOOZE_ENABLED,
          defaultValue = prefsRepo.shakeToSnooze,
        ),
        PreferenceModel(
          type = PreferenceType.BOOLEAN,
          title = FormattableString.from(R.string.settings_auto_restart_sleep_timer_title),
          explanation =
            FormattableString.from(
              R.string.settings_auto_restart_sleep_timer_explanation,
            ),
          key = PrefsRepo.KEY_AUTO_RESTART_SLEEP_TIMER,
          defaultValue = prefsRepo.autoRestartSleepTimer,
        ),
        PreferenceModel(
          type = PreferenceType.BOOLEAN,
          title = FormattableString.from(R.string.settings_pause_on_focus_lost_title),
          explanation =
            FormattableString.from(
              R.string.settings_pause_on_focus_lost_explanation,
            ),
          key = PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST,
          defaultValue = prefsRepo.pauseOnFocusLost,
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title =
            FormattableString.ResourceString(
              stringRes = R.string.settings_jump_forward_value,
              // feels gross
              placeHolderStrings =
                listOf(
                  "${prefsRepo.jumpForwardSeconds} " +
                    Injector.get()
                      .applicationContext().resources.getString(R.string.seconds),
                ),
            ),
          explanation =
            FormattableString.from(
              R.string.settings_jump_forward_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options =
                    JumpInterval.choices.map {
                      FormattableString.from(it.choiceRes)
                    },
                  title =
                    FormattableString.from(
                      R.string.settings_jump_forward_title,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        check(
                          formattableString is FormattableString.ResourceString,
                        )
                        prefsRepo.jumpForwardSeconds =
                          JumpInterval.secondsOfChoice(
                            formattableString.stringRes,
                            orElse = JumpInterval.DEFAULT_FORWARD_SECONDS,
                          )
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title =
            FormattableString.ResourceString(
              stringRes = R.string.settings_jump_backward_value,
              // feels gross
              placeHolderStrings =
                listOf(
                  "${prefsRepo.jumpBackwardSeconds} " +
                    Injector.get()
                      .applicationContext().resources.getString(R.string.seconds),
                ),
            ),
          explanation =
            FormattableString.from(
              R.string.settings_jump_backward_explanation,
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options =
                    JumpInterval.choices.map {
                      FormattableString.from(it.choiceRes)
                    },
                  title =
                    FormattableString.from(
                      R.string.settings_jump_backward_title,
                    ),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        check(
                          formattableString is FormattableString.ResourceString,
                        )
                        prefsRepo.jumpBackwardSeconds =
                          JumpInterval.secondsOfChoice(
                            formattableString.stringRes,
                            orElse = JumpInterval.DEFAULT_BACKWARD_SECONDS,
                          )
                        setBottomSheetVisibility(false)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_account),
        ),
        PreferenceModel(
          PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_change_library),
          explanation =
            FormattableString.ResourceString(
              R.string.settings_current_library,
              listOf(plexPrefs.library?.name ?: ""),
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                viewModelScope.launch {
                  if (!cachedFileManager.hasUserCachedTracks()) {
                    clearConfig(RETURN_TO_LIBRARY_CHOOSER)
                    return@launch
                  }
                  showOptionsMenu(
                    title =
                      FormattableString.from(
                        R.string.prompt_clear_downloads_allow_retain,
                      ),
                    options =
                      listOf(
                        FormattableString.yes,
                        FormattableString.no,
                      ),
                    listener =
                      object : BottomChooserItemListener() {
                        override fun onItemClicked(formattableString: FormattableString) {
                          check(
                            formattableString is FormattableString.ResourceString,
                          )
                          if (formattableString.stringRes == R.string.yes) {
                            // Keep downloaded
                            clearConfig(
                              RETURN_TO_LIBRARY_CHOOSER,
                              clearDownloads = false,
                            )
                          } else {
                            // Delete downloaded
                            clearConfig(
                              RETURN_TO_LIBRARY_CHOOSER,
                              clearDownloads = true,
                            )
                          }
                          setBottomSheetVisibility(false)
                        }
                      },
                  )
                }
              }
            },
        ),
        PreferenceModel(
          PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_change_server),
          explanation =
            FormattableString.ResourceString(
              R.string.settings_current_server,
              listOf(plexPrefs.server?.name ?: ""),
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                viewModelScope.launch {
                  if (!cachedFileManager.hasUserCachedTracks()) {
                    clearConfig(RETURN_TO_SERVER_CHOOSER)
                    return@launch
                  }
                  showOptionsMenu(
                    title =
                      FormattableString.from(
                        R.string.settings_clear_downloads_warning,
                      ),
                    options =
                      listOf(
                        FormattableString.yes,
                        FormattableString.no,
                      ),
                    listener =
                      object : BottomChooserItemListener() {
                        override fun onItemClicked(formattableString: FormattableString) {
                          if (formattableString == FormattableString.yes) {
                            clearConfig(RETURN_TO_SERVER_CHOOSER)
                          }
                          setBottomSheetVisibility(false)
                        }
                      },
                  )
                }
              }
            },
        ),
        PreferenceModel(
          PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_change_user),
          explanation =
            FormattableString.ResourceString(
              R.string.settings_current_user,
              listOf(plexPrefs.user?.username ?: ""),
            ),
          click =
            object : PreferenceClick {
              override fun onClick() {
                viewModelScope.launch {
                  if (!cachedFileManager.hasUserCachedTracks()) {
                    clearConfig(RETURN_TO_USER_CHOOSER)
                    return@launch
                  }
                  showOptionsMenu(
                    title =
                      FormattableString.from(
                        R.string.settings_clear_downloads_warning,
                      ),
                    options =
                      listOf(
                        FormattableString.yes,
                        FormattableString.no,
                      ),
                    listener =
                      object : BottomChooserItemListener() {
                        override fun onItemClicked(formattableString: FormattableString) {
                          if (formattableString == FormattableString.yes) {
                            clearConfig(RETURN_TO_USER_CHOOSER)
                          }
                          setBottomSheetVisibility(false)
                        }
                      },
                  )
                }
              }
            },
        ),
        PreferenceModel(
          PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_reauthenticate),
          explanation = FormattableString.from(R.string.settings_reauthenticate_summary),
          click =
            object : PreferenceClick {
              override fun onClick() {
                // Not a logout: keeps the chosen user, server, library *and* downloads, so the
                // recovery for an expired token is one OAuth PIN rather than the whole setup
                // again (cu-84). Plex has no refresh token, so a human at a browser is
                // unavoidable — re-picking a library they already picked was not.
                // beginReauthentication posts NOT_LOGGED_IN, which is what drives navigation to
                // the login screen — the same mechanism clearConfig uses via determineLoginState.
                plexLoginRepo.beginReauthentication()
              }
            },
        ),
        PreferenceModel(
          PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_log_out),
          click =
            object : PreferenceClick {
              override fun onClick() {
                viewModelScope.launch {
                  val logout = {
                    viewModelScope.launch {
                      cachedFileManager.uncacheAllInLibrary()
                    }
                    plexConfig.clear()
                    mediaServiceConnection.transportControls?.stop()
                    clearConfig(RETURN_TO_LOGIN)
                  }
                  if (!cachedFileManager.hasUserCachedTracks()) {
                    logout()
                    return@launch
                  }
                  showOptionsMenu(
                    title =
                      FormattableString.from(
                        R.string.settings_clear_downloads_warning,
                      ),
                    options =
                      listOf(
                        FormattableString.yes,
                        FormattableString.no,
                      ),
                    listener =
                      object : BottomChooserItemListener() {
                        override fun onItemClicked(formattableString: FormattableString) {
                          if (formattableString == FormattableString.yes) {
                            logout()
                          }
                          setBottomSheetVisibility(false)
                        }
                      },
                  )
                }
                Timber.i("Logging out")
              }
            },
        ),
        PreferenceModel(
          PreferenceType.TITLE,
          FormattableString.from(R.string.settings_category_etc),
        ),
        // The r/ChronicleApp subreddit belongs to the upstream project, not this
        // fork; pointing users there for support would send them somewhere that
        // cannot help them. Replaced with a credits entry (D12 rule 4).
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_github_title),
          explanation = FormattableString.from(R.string.settings_github_explanation),
          click =
            object : PreferenceClick {
              override fun onClick() {
                _webLink.postEvent("https://github.com/alebianco/chronicle")
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_credits_title),
          explanation = FormattableString.from(R.string.settings_credits_explanation),
          click =
            object : PreferenceClick {
              override fun onClick() {
                showOptionsMenu(
                  options = listOf(FormattableString.from(R.string.settings_credits_body)),
                  title = FormattableString.from(R.string.settings_credits_title),
                  listener =
                    object : BottomChooserItemListener() {
                      override fun onItemClicked(formattableString: FormattableString) {
                        // Informational only — dismissing is the only action.
                        _bottomChooserState.postValue(EMPTY_BOTTOM_CHOOSER)
                      }
                    },
                )
              }
            },
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_version_title),
          explanation = FormattableString.from(BuildConfig.VERSION_NAME),
        ),
        PreferenceModel(
          type = PreferenceType.CLICKABLE,
          title = FormattableString.from(R.string.settings_licenses_title),
          explanation = FormattableString.from(R.string.settings_licenses_explanation),
          click =
            object : PreferenceClick {
              override fun onClick() {
                _showLicenseActivity.postValue(true)
              }
            },
        ),
      )

    if (BuildConfig.DEBUG) {
      list.addAll(
        listOf(
          PreferenceModel(
            PreferenceType.TITLE,
            FormattableString.from(string = "Developer options"),
          ),
          PreferenceModel(
            PreferenceType.CLICKABLE,
            FormattableString.from(string = "Clear shared prefs"),
            click =
              object : PreferenceClick {
                override fun onClick() {
                  prefsRepo.clearAll()
                }
              },
          ),
          PreferenceModel(
            PreferenceType.CLICKABLE,
            FormattableString.from(string = "Clear DB"),
            click =
              object : PreferenceClick {
                override fun onClick() {
                  clearConfig(clearDownloads = false)
                }
              },
          ),
          PreferenceModel(
            PreferenceType.CLICKABLE,
            FormattableString.from(string = "Clear cached images"),
            click =
              object : PreferenceClick {
                override fun onClick() {
                  viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                      SingletonImageLoader.get(Injector.get().applicationContext()).let { loader ->
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                      }
                    }
                  }
                }
              },
          ),
          PreferenceModel(
            PreferenceType.BOOLEAN,
            FormattableString.from(string = "Disable local progress tracking"),
            PrefsRepo.KEY_DEBUG_DISABLE_PROGRESS,
            defaultValue = false,
          ),
        ),
      )
    }

    if (FEATURE_FLAG_IS_AUTO_ENABLED) {
      val autoRewindPref = list.find { it.key == PrefsRepo.KEY_AUTO_REWIND_ENABLED }
      if (autoRewindPref != null) {
        val insertIndex = list.indexOf(autoRewindPref)
        if (insertIndex != -1) {
          list.add(
            insertIndex + 1,
            PreferenceModel(
              type = PreferenceType.BOOLEAN,
              title = FormattableString.from(R.string.allow_auto),
              explanation = FormattableString.from(R.string.allow_auto_explanation),
              key = PrefsRepo.KEY_ALLOW_AUTO,
              defaultValue = prefsRepo.allowAuto,
            ),
          )
        }
      }
    }

    return list
  }

  /**
   * Sets future synced files to be downloaded to [syncDir] and moves existing synced files
   * to [syncDir]
   */
  private fun setSyncLocation(syncDir: File) {
    prefsRepo.cachedMediaDir = syncDir

    val worker = OneTimeWorkRequestBuilder<MoveSyncLocationWorker>().build()

    workManager.beginUniqueWork(
      MoveSyncLocationWorker.WORKER_ID,
      ExistingWorkPolicy.REPLACE,
      worker,
    ).enqueue()
  }

  private enum class NavigationDestination {
    RETURN_TO_LIBRARY_CHOOSER,
    RETURN_TO_SERVER_CHOOSER,
    RETURN_TO_LOGIN,
    RETURN_TO_USER_CHOOSER,
    DO_NOT_NAVIGATE,
  }

  /**
   * Clears the server cached data, and navigates to reset the data on a chooser depending on the
   * [navigateTo] provided
   */
  private fun clearConfig(
    navigateTo: NavigationDestination = DO_NOT_NAVIGATE,
    clearDownloads: Boolean = true,
  ) {
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      if (clearDownloads) {
        cachedFileManager.uncacheAllInLibrary()
      }
      withContext(Dispatchers.IO) {
        bookRepository.clear()
        trackRepository.clear()
        collectionsRepository.clear()
      }
      mediaServiceConnection.transportControls?.stop()
      when (navigateTo) {
        RETURN_TO_LIBRARY_CHOOSER -> plexConfig.clearLibrary()
        RETURN_TO_SERVER_CHOOSER -> plexConfig.clearServer()
        RETURN_TO_LOGIN -> plexConfig.clear()
        RETURN_TO_USER_CHOOSER -> plexConfig.clearUser()
        DO_NOT_NAVIGATE -> {
        }
      }
      plexLoginRepo.determineLoginState()
    }
  }

  fun showUserMessage(formattableString: FormattableString) {
    _messageForUser.postEvent(formattableString)
  }

  fun setShowLicenseActivity(showLicense: Boolean) {
    _showLicenseActivity.postValue(showLicense)
  }

  /**
   * The filename offered in the picker, e.g. `chronicle-backup-2026-09-02.json`.
   *
   * ISO order so a folder of exports sorts chronologically, and `Locale.US` digits so the name is
   * stable regardless of the device locale — a filename is not user-facing prose.
   */
  private fun defaultBackupFileName(): String {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return Injector.get().applicationContext()
      .getString(R.string.settings_backup_export_filename, today)
  }

  /** Writes the backup to the document the user just picked. */
  fun onExportFileChosen(destination: Uri) {
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      when (val result = settingsBackupRepo.exportTo(destination)) {
        is SettingsBackupRepo.ExportResult.Written ->
          showUserMessage(
            FormattableString.ResourceString(
              R.string.settings_backup_export_response,
              placeHolderStrings = listOf(result.settingCount.toString()),
            ),
          )

        is SettingsBackupRepo.ExportResult.Failed ->
          showUserMessage(FormattableString.from(R.string.settings_backup_export_failed))
      }
    }
  }

  /**
   * Applies the backup in the document the user just picked.
   *
   * Every outcome says something specific. A refused file that reported nothing would look
   * identical to a successful restore that changed nothing, which is the silent failure cu-77 set
   * out to avoid.
   */
  fun onImportFileChosen(source: Uri) {
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      when (val result = settingsBackupRepo.importFrom(source)) {
        is SettingsBackupRepo.ImportResult.Applied -> {
          showUserMessage(
            if (result.skipped > 0) {
              FormattableString.ResourceString(
                R.string.settings_backup_import_response_skipped,
                placeHolderStrings =
                  listOf(result.applied.toString(), result.skipped.toString()),
              )
            } else {
              FormattableString.ResourceString(
                R.string.settings_backup_import_response,
                placeHolderStrings = listOf(result.applied.toString()),
              )
            },
          )
          // The prefs listener rebuilds the list on each individual write, but it is
          // registered against the same file this just edited in one commit, so refresh
          // explicitly rather than relying on the callback's timing.
          _preferences.postValue(makePreferences())
        }

        is SettingsBackupRepo.ImportResult.WrongVersion ->
          showUserMessage(
            FormattableString.ResourceString(
              R.string.settings_backup_import_wrong_version,
              placeHolderStrings = listOf(result.fileVersion.toString()),
            ),
          )

        is SettingsBackupRepo.ImportResult.Unreadable ->
          showUserMessage(
            FormattableString.from(R.string.settings_backup_import_unreadable),
          )
      }
    }
  }

  /** No document picker on the device — rare, but a bare crash would be worse. */
  fun onNoFilePickerAvailable() {
    Timber.w("No activity could handle the document picker intent")
    showUserMessage(FormattableString.from(R.string.settings_backup_no_picker))
  }
}
