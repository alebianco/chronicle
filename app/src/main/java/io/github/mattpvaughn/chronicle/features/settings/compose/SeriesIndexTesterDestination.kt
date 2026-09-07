package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.settings.SeriesIndexTesterViewModel
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * The series-numbering rules tester as a navigation destination.
 *
 * Replaces `SeriesIndexTesterFragment` + `fragment_series_index_tester.xml`. The toolbar, its back
 * arrow and the `applyTopSystemBarInset()` call all collapse into [ChronicleScaffold].
 *
 * `hiltViewModel()` scopes the ViewModel to this back-stack entry rather than to a Fragment, so it
 * survives configuration change and is cleared when the entry is popped — the same lifetime
 * `by viewModels()` gave, without needing a Fragment to own it.
 */
@Composable
fun SeriesIndexTesterDestination(
  onNavigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SeriesIndexTesterViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  ChronicleScaffold(
    title = stringResource(R.string.series_rules_screen_title),
    onNavigateUp = onNavigateUp,
    modifier = modifier,
  ) {
    SeriesIndexTesterScreen(
      state = state,
      onTitleSortChanged = viewModel::onTitleSortChanged,
      onSampleChosen = viewModel::onSampleChosen,
      modifier = Modifier.fillMaxSize(),
    )
  }
}
