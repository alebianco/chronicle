package io.github.mattpvaughn.chronicle.views.compose

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.views.SpeedChooserState
import kotlin.math.roundToInt

/**
 * The four preset speeds and their labels, matching the `speed_presets` chips.
 *
 * The label is a string resource rather than a formatted number: `playback_speed_1_0x` and friends
 * already exist and are what the chips rendered. Note CLAUDE.md's warning is about the *tag* — the
 * key must never be a localisable resource — and the value here is the plain `Float`, so the
 * hazard does not apply.
 */
private val PRESETS =
  listOf(
    1.0f to R.string.playback_speed_1_0x,
    1.2f to R.string.playback_speed_1_2x,
    1.5f to R.string.playback_speed_1_5x,
    2.0f to R.string.playback_speed_2_0x,
  )

/**
 * The playback-speed popover.
 *
 * Replaces `ModalBottomSheetSpeedChooser`, one of the three screens still written in Views. All the
 * decisions already lived in [SpeedChooserState]; what is gone is the plumbing around them —
 * an `isRendering` re-entrancy flag guarding every programmatic write so a listener would not
 * fire back, a `SharedPreferences.OnSharedPreferenceChangeListener` re-reading the state, and a
 * `NestedScrollView` needed because the sheet clipped its last control when expanded.
 *
 * The re-entrancy guard has nothing to guard: state flows one way, so setting a value cannot call
 * back into the setter. The scroll view survives as `verticalScroll` for the same reason it existed
 * — a short landscape window still has to reach the last switch.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedChooserSheet(
  state: SpeedChooserState,
  skipSilence: Boolean,
  onSpeedChange: (Float) -> Unit,
  onToggleOverride: (Boolean) -> Unit,
  onToggleSkipSilence: (Boolean) -> Unit,
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
          .padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.playback_speed_title),
        style = MaterialTheme.typography.titleMedium,
        color = ChronicleColors.TextPrimary,
      )

      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.forEach { (preset, labelRes) ->
          FilterChip(
            selected = state.speed == preset,
            onClick = { onSpeedChange(preset) },
            label = { Text(stringResource(labelRes)) },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.playback_speed_min), color = ChronicleColors.TextSecondary)
        Text(formatSpeed(state.speed), color = ChronicleColors.TextPrimary)
        Text(stringResource(R.string.playback_speed_max), color = ChronicleColors.TextSecondary)
      }

      // `steps` is the count *between* the ends, hence the -1. A Compose `Slider` clamps rather
      // than throwing, so the `snapToStep` guard that existed because `Slider.setValue` **throws**
      // off-grid is no longer load-bearing here — it stays in `SpeedChooserState` because
      // the value it protects also reaches the player.
      Slider(
        value = state.speed,
        onValueChange = onSpeedChange,
        valueRange = SpeedChooserState.SPEED_MIN..SpeedChooserState.SPEED_MAX,
        steps =
          (
            (SpeedChooserState.SPEED_MAX - SpeedChooserState.SPEED_MIN) /
              SpeedChooserState.SPEED_STEP
          ).roundToInt() - 1,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.playback_speed_this_book_only),
          color = ChronicleColors.TextPrimary,
        )
        Switch(
          checked = state.isOverrideEnabled,
          onCheckedChange = onToggleOverride,
          enabled = state.canOverride,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.playback_skip_silence), color = ChronicleColors.TextPrimary)
        Switch(checked = skipSilence, onCheckedChange = onToggleSkipSilence)
      }
    }
  }
}

/** `1.0x`, `1.25x` — the label formatter the `Slider` used. */
private fun formatSpeed(speed: Float): String = "${(speed * 100).roundToInt() / 100f}x"
