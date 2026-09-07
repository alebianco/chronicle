package io.github.mattpvaughn.chronicle.features.browse.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.features.browse.BrowseViewModel
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold

/**
 * Browse the library by author, narrator or series, as a navigation destination.
 *
 * Replaces `BrowseFragment` + `fragment_browse.xml`. That layout was the odd one out — a bare
 * `Toolbar` in a `ConstraintLayout` with no `AppBarLayout` and no inset call, so it was the only
 * screen whose toolbar did not inset itself. [ChronicleScaffold] makes it consistent with the rest.
 */
@Composable
fun BrowseDestination(
  onNavigateUp: () -> Unit,
  onFacetClick: (FacetKind, Facet) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: BrowseViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  ChronicleScaffold(
    title = stringResource(R.string.browse_title),
    onNavigateUp = onNavigateUp,
    modifier = modifier,
  ) {
    BrowseScreen(
      state = state,
      onSelectFacet = viewModel::showFacet,
      onFacetClick = { onFacetClick(state.selected, it) },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
