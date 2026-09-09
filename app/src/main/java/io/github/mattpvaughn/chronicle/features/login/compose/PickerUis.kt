package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.login.ChooseLibraryViewModel
import io.github.mattpvaughn.chronicle.features.login.ChooseServerViewModel
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser

/**
 * The server picker, as a Circuit screen.
 *
 * The three pickers are the same screen with different data, which is what [PickerScreen] and
 * [OnboardingScaffold] between them now say out loud.
 */
@Composable
fun ChooseServerUi(
  modifier: Modifier = Modifier,
  viewModel: ChooseServerViewModel = hiltViewModel(),
) {
  val servers by viewModel.servers.collectAsStateWithLifecycle()
  val status by viewModel.loadingStatus.collectAsStateWithLifecycle()

  ToastEffect(viewModel.userMessage)

  OnboardingScaffold(
    title = stringResource(R.string.choose_server),
    onRefresh = viewModel::refresh,
    modifier = modifier,
  ) {
    PickerScreen(
      status = status,
      items = servers.map { PickerItem(id = it.serverId, title = it.name, value = it) },
      errorMessage = stringResource(R.string.no_servers_found),
      onItemClick = viewModel::chooseServer,
    )
  }
}

/**
 * The library picker, as a Circuit screen.
 *
 * Its refresh icon is wired here. In `onboarding_plex_choose_library.xml` the icon was present but
 * had **no click listener in Kotlin** — a button that did nothing, next to a server picker whose
 * identical icon worked.
 */
@Composable
fun ChooseLibraryUi(
  modifier: Modifier = Modifier,
  viewModel: ChooseLibraryViewModel = hiltViewModel(),
) {
  val libraries by viewModel.libraries.collectAsStateWithLifecycle()
  val status by viewModel.loadingStatus.collectAsStateWithLifecycle()
  val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()

  ToastEffect(viewModel.userMessage)

  OnboardingScaffold(
    title = stringResource(R.string.choose_library),
    onRefresh = viewModel::refresh,
    modifier = modifier,
  ) {
    PickerScreen(
      status = status,
      items = libraries.map { PickerItem(id = it.id, title = it.name, value = it) },
      errorMessage = stringResource(R.string.no_libraries_found),
      onItemClick = viewModel::chooseLibrary,
    )
  }

  BottomChooser(chooser)
}
