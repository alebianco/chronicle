package io.github.mattpvaughn.chronicle.features.browse.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme

/**
 * Browse the library by author, narrator or series, migrated to Compose here.
 *
 * Replaces a `TabLayout` with an `addOnTabSelectedListener`, a `FacetListAdapter` and three
 * `isVisible` decisions written across two `render` passes. The tab order still *is* the enum
 * order — that was deliberate in the View version so no lookup table could drift from it, and it
 * stays deliberate here.
 */
@Composable
fun BrowseScreen(
  state: BrowseUiState,
  onSelectFacet: (FacetKind) -> Unit,
  onFacetClick: (Facet) -> Unit,
  modifier: Modifier = Modifier,
) {
  // `MaterialTheme` defines `colorScheme.background` but paints nothing — a bare `Column` would
  // let the window colour through and render near-invisible text.
  Surface(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      TabRow(selectedTabIndex = FacetKind.entries.indexOf(state.selected)) {
        FacetKind.entries.forEach { kind ->
          Tab(
            selected = kind == state.selected,
            onClick = { onSelectFacet(kind) },
            text = { Text(stringResource(kind.labelRes())) },
          )
        }
      }

      when (val content = state.content) {
        // Nothing at all while the first grouping runs: an empty message here would be a claim
        // about the library that has not been checked yet.
        BrowseContent.Loading -> Unit

        is BrowseContent.Empty ->
          Text(
            text = stringResource(content.kind.emptyRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.screen_horizontal_padding)),
          )

        is BrowseContent.Loaded -> FacetRows(content.facets, onFacetClick)
      }
    }
  }
}

@Composable
private fun FacetRows(
  facets: FacetList,
  onFacetClick: (Facet) -> Unit,
) {
  LazyColumn(modifier = Modifier.fillMaxSize()) {
    // Only when something is actually missing — a complete index carrying a caveat teaches the
    // user to ignore the caveat.
    if (facets.isPartial) {
      item(key = "coverage") {
        Text(
          text =
            pluralStringResource(
              R.plurals.browse_coverage,
              facets.unknownCount,
              facets.unknownCount,
            ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(dimensionResource(R.dimen.margin_normal)),
        )
      }
    }

    // Keyed on the value, which `facetsBy` already returns distinct — the count is not part of
    // identity, so a book gaining a narrator moves the row rather than replacing it.
    items(facets.facets, key = { it.value }) { facet ->
      FacetRow(facet, onClick = { onFacetClick(facet) })
    }
  }
}

@Composable
private fun FacetRow(
  facet: Facet,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(
          horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
          vertical = 12.dp,
        ),
  ) {
    Text(
      text = facet.value,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = pluralStringResource(R.plurals.facet_book_count, facet.bookCount, facet.bookCount),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** The tab label for a facet. Kept next to the enum's use rather than in a `when` per call site. */
internal fun FacetKind.labelRes(): Int =
  when (this) {
    FacetKind.Author -> R.string.browse_by_author
    FacetKind.Narrator -> R.string.browse_by_narrator
    FacetKind.Series -> R.string.browse_by_series
  }

/** What to say when a facet has nothing — different per facet, since the *reason* differs. */
internal fun FacetKind.emptyRes(): Int =
  when (this) {
    FacetKind.Author -> R.string.browse_no_authors
    FacetKind.Narrator -> R.string.browse_no_narrators
    FacetKind.Series -> R.string.browse_no_series
  }

@Preview
@Composable
private fun BrowseLoadedPreview() {
  ChronicleTheme {
    BrowseScreen(
      state =
        BrowseUiState(
          selected = FacetKind.Narrator,
          content =
            BrowseContent.Loaded(
              FacetList(
                kind = FacetKind.Narrator,
                facets = listOf(Facet("Kate Reading", 12), Facet("Simon Vance", 3)),
                unknownCount = 184,
              ),
            ),
        ),
      onSelectFacet = {},
      onFacetClick = {},
    )
  }
}
