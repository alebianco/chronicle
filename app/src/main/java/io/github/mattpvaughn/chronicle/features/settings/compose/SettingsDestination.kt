package io.github.mattpvaughn.chronicle.features.settings.compose

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import io.github.mattpvaughn.chronicle.features.settings.SettingsViewModel
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.EventEffect
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
import io.github.mattpvaughn.chronicle.views.getString
import timber.log.Timber

/** The type the backup is created as. */
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * The *open* picker's filter, deliberately a wildcard.
 *
 * Carried over verbatim from `SettingsFragment`: `OpenDocument` shows a document matching **any**
 * entry, so listing `application/json` beside a wildcard would be the wildcard alone. Providers
 * disagree about the type of a hand-copied .json — Downloads says `application/json`, a file
 * through a sync client or a zip often arrives as `application/octet-stream` — and a narrower
 * filter greys out exactly the file the user wants with no way to tell why. A wrong pick is cheap,
 * since `SettingsBackupRepo` reports an unreadable file rather than applying anything.
 */
private val BACKUP_OPEN_MIME_TYPES = arrayOf("*/*")

/**
 * The settings list, as a navigation destination.
 *
 * ### The document pickers get simpler
 *
 * The Fragment had to register both launchers as **fields**, with a comment explaining that
 * `registerForActivityResult` throws if called after STARTED, and hold the ViewModel `by lazy` so a
 * launcher callback arriving after process death could still reach it.
 * `rememberLauncherForActivityResult` handles that registration itself, so both constraints — and
 * the reason for the `by lazy` — simply stop existing.
 *
 * This screen has no toolbar, so it takes the status-bar inset itself; that is what
 * `settingsCompose.applyTopSystemBarInset()` did.
 */
@Composable
fun SettingsDestination(
  onShowSeriesIndexTester: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val resources = context.resources
  val rows by viewModel.settingsRows.collectAsStateWithLifecycle()
  val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()
  val showLicenses by viewModel.showLicenseActivity.collectAsStateWithLifecycle()

  val exportFileLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { uri ->
      // Null when the user backs out of the picker — a normal cancellation, and deliberately says
      // nothing to the user.
      uri?.let { viewModel.onExportFileChosen(it) }
    }

  val importFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.onImportFileChosen(it) }
    }

  EventEffect(viewModel.messageForUser) { formattable ->
    Toast.makeText(context, resources.getString(formattable), Toast.LENGTH_SHORT).show()
  }
  EventEffect(viewModel.webLink) { link ->
    context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
  }
  EventEffect(viewModel.exportFileRequest) { defaultFileName ->
    try {
      exportFileLauncher.launch(defaultFileName)
    } catch (e: ActivityNotFoundException) {
      Timber.w(e, "No document picker available for export")
      viewModel.onNoFilePickerAvailable()
    }
  }
  EventEffect(viewModel.importFileRequest) {
    try {
      importFileLauncher.launch(BACKUP_OPEN_MIME_TYPES)
    } catch (e: ActivityNotFoundException) {
      Timber.w(e, "No document picker available for import")
      viewModel.onNoFilePickerAvailable()
    }
  }
  EventEffect(viewModel.showSeriesIndexTester) { onShowSeriesIndexTester() }

  // A `LaunchedEffect`, not a bare `if`: starting an activity straight from the composition is a
  // side effect in the composition phase, so it would fire again on any recomposition that happened
  // before the flag was cleared. Keyed on the flag, so it runs once per transition to true.
  LaunchedEffect(showLicenses) {
    if (showLicenses) {
      context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
      viewModel.setShowLicenseActivity(false)
    }
  }

  Surface(
    modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
    color = ChronicleColors.Primary,
  ) {
    SettingsScreen(
      rows = rows,
      onClick = { it.click.onClick() },
      onToggle = viewModel::setSwitch,
    )
  }

  // The chooser draws in its own window, so it is a sibling of the screen rather than an overlay
  // inside it.
  BottomChooser(chooser)
}
