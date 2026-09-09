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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.features.settings.PreferenceModel
import io.github.mattpvaughn.chronicle.features.settings.SettingsViewModel
import io.github.mattpvaughn.chronicle.navigation.LicensesScreenKey
import io.github.mattpvaughn.chronicle.navigation.SeriesIndexTesterScreenKey
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

data class SettingsCircuitState(
  val rows: List<SettingsRow>,
  val eventSink: (SettingsEvent) -> Unit,
) : CircuitUiState

sealed interface SettingsEvent : CircuitUiEvent {
  /** A clickable row. Each model carries its own action, which the ViewModel built. */
  data class RowClicked(val model: PreferenceModel) : SettingsEvent

  data class SwitchToggled(val model: PreferenceModel, val isChecked: Boolean) : SettingsEvent
}

/**
 * ### Why the two navigations are not events
 *
 * The settings list is data — a `SettingsRow` carries its own `click` lambda — so "the user tapped
 * the licences row" is not distinguishable at this layer from "the user tapped anything else".
 * The ViewModel raises `showLicenses` and `showSeriesIndexTester` as one-shot `Event` flows
 * instead, and the presenter collects them here.
 *
 * A plain `LaunchedEffect`, **not** `EventEffect`. The two do the same job, but `EventEffect` reads
 * `LocalLifecycleOwner` for its STARTED gate — an Android composition local that a plain JVM test
 * cannot provide, so a presenter using it is testable only under Robolectric. The gate buys
 * nothing here either: Circuit already stops presenting a screen that leaves the back stack, and
 * unlike a `Toast` a navigation raised while backgrounded is not something the user can miss.
 */
class SettingsPresenter(
  private val viewModel: @Composable () -> SettingsViewModel,
  private val navigator: Navigator,
) : Presenter<SettingsCircuitState> {
  @Composable
  override fun present(): SettingsCircuitState {
    val viewModel = viewModel()
    val rows by viewModel.settingsRows.collectAsState()

    LaunchedEffect(Unit) {
      viewModel.showSeriesIndexTester.collect { event ->
        event?.getContentIfNotHandled()?.let { navigator.goTo(SeriesIndexTesterScreenKey) }
      }
    }
    LaunchedEffect(Unit) {
      viewModel.showLicenses.collect { event ->
        event?.getContentIfNotHandled()?.let { navigator.goTo(LicensesScreenKey) }
      }
    }

    return SettingsCircuitState(rows = rows) { event ->
      when (event) {
        is SettingsEvent.RowClicked -> event.model.click.onClick()
        is SettingsEvent.SwitchToggled -> viewModel.setSwitch(event.model, event.isChecked)
      }
    }
  }
}

/**
 * The settings list.
 *
 * ### The document pickers get simpler
 *
 * The Fragment had to register both launchers as **fields**, with a comment explaining that
 * `registerForActivityResult` throws if called after STARTED, and hold the ViewModel `by lazy` so a
 * launcher callback arriving after process death could still reach it.
 * `rememberLauncherForActivityResult` handles that registration itself, so both constraints — and
 * the reason for the `by lazy` — simply stop existing.
 *
 * The picker and toast effects stay here rather than moving into the presenter: they need a
 * `Context` and an activity-result registry, which a presenter deliberately has none of.
 *
 * This screen has no toolbar, so it takes the status-bar inset itself; that is what
 * `settingsCompose.applyTopSystemBarInset()` did.
 */
@Composable
fun SettingsUi(
  state: SettingsCircuitState,
  viewModel: SettingsViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val resources = context.resources
  val chooser by viewModel.bottomChooserState.collectAsState()

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

  Surface(
    modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
    color = ChronicleColors.Primary,
  ) {
    SettingsScreen(
      rows = state.rows,
      onClick = { state.eventSink(SettingsEvent.RowClicked(it)) },
      onToggle = { model, isChecked ->
        state.eventSink(SettingsEvent.SwitchToggled(model, isChecked))
      },
    )
  }

  // The chooser draws in its own window, so it is a sibling of the screen rather than an overlay
  // inside it.
  BottomChooser(chooser)
}
