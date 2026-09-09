package io.github.mattpvaughn.chronicle.features.settings.compose

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensesUiState
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensesViewModel
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold
import timber.log.Timber

/**
 * The third-party licences list.
 *
 * Replaces `OssLicensesMenuActivity`, which came from `play-services-oss-licenses` — a **Google
 * Play Services** dependency, and so a distribution blocker under decision-1, which puts
 * sideload/F-Droid/homelab first.
 *
 * ### Why opening a link is not an event
 *
 * [LicensesEvent] carries only navigation. Opening a licence URL needs a `Context`, and a presenter
 * is a plain object that deliberately has none — routing the intent through an event would mean
 * either handing the presenter a `Context` (making it untestable off-device, the thing this
 * migration is for) or handing the `Ui` a callback back, which is the lambda tangle Circuit
 * removes. The `Ui` already has `LocalContext`, and opening a browser changes no screen state, so
 * it stays there.
 */
data class LicensesCircuitState(
  val ui: LicensesUiState,
  val eventSink: (LicensesEvent) -> Unit,
) : CircuitUiState

sealed interface LicensesEvent : CircuitUiEvent {
  data object NavigateUp : LicensesEvent
}

class LicensesPresenter(
  private val viewModel: @Composable () -> LicensesViewModel,
  private val navigator: Navigator,
) : Presenter<LicensesCircuitState> {
  @Composable
  override fun present(): LicensesCircuitState {
    val viewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    return LicensesCircuitState(ui = state) { event ->
      when (event) {
        LicensesEvent.NavigateUp -> navigator.pop()
      }
    }
  }
}

@Composable
fun LicensesUi(
  state: LicensesCircuitState,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  ChronicleScaffold(
    title = stringResource(R.string.licenses_screen_title),
    onNavigateUp = { state.eventSink(LicensesEvent.NavigateUp) },
    modifier = modifier,
  ) {
    LicensesScreen(
      state = state.ui,
      onLicenseClick = { license ->
        val url = license.url ?: return@LicensesScreen
        try {
          context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
          // A device with no browser at all. Logged rather than swallowed, and deliberately silent
          // to the user: the licence name is still on screen and still readable, so there is
          // nothing actionable to say.
          Timber.w(e, "No browser available to open a license URL")
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
