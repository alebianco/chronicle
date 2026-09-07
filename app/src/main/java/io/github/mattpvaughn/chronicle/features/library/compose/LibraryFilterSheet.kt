package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors

/**
 * One selectable option in the filter sheet.
 *
 * [key] is the *stored preference value*, and [labelRes] the text shown. The XML kept both in one
 * place — `android:tag="@string/key_sort_by_title"` — which is the shape CLAUDE.md warns about: a
 * `Chip`'s tag must not be a string resource when it is parsed as data, because a locale rendering
 * it differently matches no branch. Splitting them means the key is never localised.
 */
data class FilterOption(
  val key: String,
  val labelRes: Int,
)

/**
 * The library's sort/view-style/hide-played panel.
 *
 * Replaces the `filter_view` `ConstraintLayout` in `fragment_library.xml`, which was a persistent
 * `BottomSheetBehavior` inside the screen's `CoordinatorLayout` — two `ChipGroup`s, a switch, and a
 * two-way binding between the sheet's own state and `viewModel.isFilterShown` maintained by a
 * `BottomSheetCallback` in one direction and a flow collector in the other.
 *
 * `ModalBottomSheet` needs neither: visibility is a parameter, dismissal is a callback, and there
 * is no peek height to get stuck at — answered structurally here rather than by
 * `expandBottomSheetOnStart()`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterSheet(
  sortOptions: List<FilterOption>,
  selectedSortKey: String,
  onSortKeyChange: (String) -> Unit,
  isSortDescending: Boolean,
  onToggleSortDirection: () -> Unit,
  viewStyleOptions: List<FilterOption>,
  selectedViewStyleKey: String,
  onViewStyleChange: (String) -> Unit,
  hidePlayed: Boolean,
  onToggleHidePlayed: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = modifier,
    containerColor = ChronicleColors.PrimaryDark,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.filter),
          style = MaterialTheme.typography.titleMedium,
          color = ChronicleColors.TextPrimary,
        )
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.done_filtering), color = ChronicleColors.Accent)
        }
      }

      // Sort, with the direction toggle beside its heading — the `sort_by_container` click target.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.sort_by),
          style = MaterialTheme.typography.labelLarge,
          color = ChronicleColors.TextSecondary,
        )
        IconButton(onClick = onToggleSortDirection) {
          // The XML icon was `ic_arrow_trending_down_white`, **static** — it never reflected the
          // direction, and only the content description changed. Kept exactly as it was: making
          // the arrow follow the sort order would be an improvement, but an unannounced visual
          // change inside a migration is how a regression gets mistaken for a fix.
          Icon(
            painter = painterResource(R.drawable.ic_arrow_trending_down_white),
            contentDescription =
              stringResource(
                if (isSortDescending) {
                  R.string.toggle_library_sort_ascending
                } else {
                  R.string.toggle_library_sort_descending
                },
              ),
            tint = ChronicleColors.TextPrimary,
          )
        }
      }
      ChipRow(sortOptions, selectedSortKey, onSortKeyChange)

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.hide_played_switch),
          color = ChronicleColors.TextPrimary,
        )
        Switch(checked = hidePlayed, onCheckedChange = { onToggleHidePlayed() })
      }

      Text(
        text = stringResource(R.string.view_style),
        style = MaterialTheme.typography.labelLarge,
        color = ChronicleColors.TextSecondary,
      )
      ChipRow(viewStyleOptions, selectedViewStyleKey, onViewStyleChange)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
  options: List<FilterOption>,
  selectedKey: String,
  onSelect: (String) -> Unit,
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEach { option ->
      FilterChip(
        selected = option.key == selectedKey,
        onClick = { onSelect(option.key) },
        label = { Text(stringResource(option.labelRes)) },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = ChronicleColors.Accent,
            labelColor = ChronicleColors.TextPrimary,
          ),
      )
    }
  }
}
