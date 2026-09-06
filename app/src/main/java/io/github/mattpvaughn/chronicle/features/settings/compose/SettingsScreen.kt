package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.features.settings.PreferenceModel
import io.github.mattpvaughn.chronicle.features.settings.PreferenceType

/**
 * One settings row, resolved (cu-199).
 *
 * `PreferenceModel` is already the UI state — that is what makes this screen the easy second
 * migration. The one thing it does *not* carry is the switch's current value: `SettingsList`'s
 * ViewHolder read `prefsRepo` during `bind` and wrote back to it in two handlers, so a View was
 * reaching into a repository. Resolving it here keeps the composable stateless and puts the write
 * on the ViewModel, where it can be tested.
 */
data class SettingsRow(
  val model: PreferenceModel,
  val isChecked: Boolean = false,
)

/**
 * The settings screen.
 *
 * Replaces `SettingsList` — a hand-rolled `FrameLayout` wrapping a programmatic `RecyclerView`
 * with three ViewHolders, a `DiffUtil`, and a reverse lookup through `prefIntMap` that threw
 * `NoWhenBranchMatchedException` on a miss. All of it becomes a `when` over a sealed type that the
 * compiler checks.
 */
@Composable
fun SettingsScreen(
  rows: List<SettingsRow>,
  onClick: (PreferenceModel) -> Unit,
  onToggle: (PreferenceModel, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    LazyColumn(contentPadding = PaddingValuesCompat) {
      // Keyed on the preference key, so toggling one switch recomposes one row rather than the
      // list. The old list rebuilt all 36 models on any preference change — which every toggle
      // caused, since the ViewHolder wrote straight to `prefsRepo`.
      items(rows, key = { it.model.key.ifEmpty { it.model.title.toString() } }) { row ->
        when (row.model.type) {
          PreferenceType.TITLE -> SectionTitle(row)
          PreferenceType.BOOLEAN -> SwitchRow(row, onToggle)
          // CLICKABLE, and the two dead variants INTEGER/FLOAT which no `makePreferences` row
          // constructs — they mapped to the same ViewHolder here too.
          else -> ClickableRow(row, onClick)
        }
      }
    }
  }
}

private val PaddingValuesCompat = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)

@Composable
private fun SectionTitle(row: SettingsRow) {
  Text(
    // Uppercased, because the View style did: `TextAppearance.Subtitle.Settings` sets
    // `android:textAllCaps`. Material3's `labelLarge` does not, so a straight port silently
    // changed every section header from "APPEARANCE" to "Appearance" — caught by comparing
    // against the cu-175 baseline screenshot, not by any test.
    text = row.model.title.format(LocalResources.current).toString().uppercase(),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
  )
}

@Composable
private fun ClickableRow(
  row: SettingsRow,
  onClick: (PreferenceModel) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable { onClick(row.model) }
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    RowTitle(row)
    RowExplanation(row)
  }
}

@Composable
private fun SwitchRow(
  row: SettingsRow,
  onToggle: (PreferenceModel, Boolean) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      // The whole row toggles, not just the switch — the View version wired
      // `preferenceSwitchContent` for exactly this, and losing it would shrink a full-width
      // target to a 48dp one.
      .clickable { onToggle(row.model, !row.isChecked) }
      .padding(horizontal = 24.dp, vertical = 12.dp)
      .semantics {
        toggleableState = if (row.isChecked) ToggleableState.On else ToggleableState.Off
      },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      RowTitle(row)
      RowExplanation(row)
    }
    Switch(
      checked = row.isChecked,
      // Null, so the switch does not take its own click: the row above already handles it, and
      // two handlers on one gesture is how a toggle double-fires back to its original value.
      onCheckedChange = null,
    )
  }
}

@Composable
private fun RowTitle(row: SettingsRow) {
  Text(
    text = row.model.title.format(LocalResources.current).toString(),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onBackground,
  )
}

@Composable
private fun RowExplanation(row: SettingsRow) {
  if (!row.model.hasExplanation()) return
  Text(
    text = row.model.explanation.format(LocalResources.current).toString(),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp),
  )
}
