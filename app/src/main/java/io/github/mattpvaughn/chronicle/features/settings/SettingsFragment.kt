package io.github.mattpvaughn.chronicle.features.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.databinding.FragmentSettingsBinding
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.settings.compose.SettingsScreen
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.getString
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import timber.log.Timber
import javax.inject.Inject

class SettingsFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: SettingsViewModel.Factory

  @Inject
  lateinit var mediaServiceConnection: MediaServiceConnection

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  @Inject
  lateinit var cachedFileManager: ICachedFileManager

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var plexPrefsRepo: PlexPrefsRepo

  @Inject
  lateinit var plexConfig: PlexConfig

  /**
   * The document pickers, registered as fields.
   *
   * `registerForActivityResult` must be called before the fragment reaches STARTED, so these
   * cannot be created inside an observer or a click handler — doing so throws once the fragment
   * is resumed. The result is null when the user backs out of the picker, which is a normal
   * cancellation and deliberately says nothing to the user.
   */
  private val exportFileLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
      uri?.let { viewModel.onExportFileChosen(it) }
    }

  private val importFileLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.onImportFileChosen(it) }
    }

  /**
   * The ViewModel, as a field so the launcher callbacks above can reach it.
   *
   * `by lazy` rather than assignment in `onCreateView`: a callback can fire before the view is
   * recreated after a process death, and a `lateinit` would not be initialised yet.
   */
  private val viewModel: SettingsViewModel by lazy {
    ViewModelProvider(this, viewModelFactory).get(SettingsViewModel::class.java)
  }

  companion object {
    @JvmStatic
    fun newInstance() = SettingsFragment()

    /** The type the backup is created as. */
    private const val BACKUP_MIME_TYPE = "application/json"

    /**
     * The *open* picker's filter, deliberately a wildcard.
     *
     * `OpenDocument` shows a document if it matches **any** entry, so listing
     * `application/json` alongside a wildcard would be the same as the wildcard alone. The
     * wildcard is the honest choice rather than a lazy one: providers disagree about the type of
     * a hand-copied .json — Downloads reports `application/json`, but files that have been
     * through a sync client or a zip often arrive as `application/octet-stream` — and a
     * narrower filter greys out exactly the file the user is looking for, with no way for them
     * to tell why. A wrong pick is cheap here, since [SettingsBackupRepo] reports an
     * unreadable file rather than applying anything.
     */
    private val BACKUP_OPEN_MIME_TYPES = arrayOf("*/*")
  }

  override fun onAttach(context: Context) {
    check(injectFromHost { it.inject(this) }) { "${javaClass.simpleName} needs an ActivityComponentHost" }
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    val binding = FragmentSettingsBinding.inflate(inflater, container, false)

    // Was `bottomChooserState` / `preferences` binding adapters in fragment_settings.xml.
    viewLifecycleOwner.collectWhileStarted(viewModel.bottomChooserState) { state ->
      setBottomChooserState(binding.bottomSheetChooser, state)
    }

    // The rows are `SettingsScreen` now (cu-199). This replaces `SettingsList` entirely — a
    // FrameLayout wrapping a programmatic RecyclerView, three ViewHolders, a DiffUtil, and a
    // reverse lookup through `prefIntMap` that threw `NoWhenBranchMatchedException` on a miss.
    //
    // The switch value comes resolved from the ViewModel rather than being read out of
    // `prefsRepo` during bind, which is what the ViewHolder did — a View reaching into a
    // repository, and the reason this screen was untestable before cu-33.
    binding.settingsCompose.setContent {
      val rows by viewModel.settingsRows.collectAsStateWithLifecycle()

      ChronicleTheme {
        SettingsScreen(
          rows = rows,
          onClick = { it.click.onClick() },
          onToggle = viewModel::setSwitch,
        )
      }
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.messageForUser) { formattableString ->
      Toast.makeText(context, resources.getString(formattableString), Toast.LENGTH_SHORT).show()
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.webLink) { link ->
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.exportFileRequest) { defaultFileName ->
      try {
        exportFileLauncher.launch(defaultFileName)
      } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No document picker available for export")
        viewModel.onNoFilePickerAvailable()
      }
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.importFileRequest) {
      try {
        importFileLauncher.launch(BACKUP_OPEN_MIME_TYPES)
      } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No document picker available for import")
        viewModel.onNoFilePickerAvailable()
      }
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.showSeriesIndexTester) {
      navigator.showSeriesIndexTester()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.showLicenseActivity) {
      if (it) {
        startActivity(Intent(context, OssLicensesMenuActivity::class.java))
        viewModel.setShowLicenseActivity(false)
      }
    }

    // Settings has no toolbar, so the list itself takes the top inset (cu-63).

    binding.settingsCompose.applyTopSystemBarInset()

    return binding.root
  }
}
