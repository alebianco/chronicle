package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString

/**
 * A list of choices in a modal sheet.
 *
 * Replaces `BottomSheetChooser`, a `FrameLayout` with a hand-rolled show/hide animation, an inner
 * `RecyclerView` adapter and a `DiffUtil` — used by five screens through a binding-adapter-style
 * `setBottomChooserState`.
 *
 * **`FormattableString` stays.** It looks like a workaround for a `View` being unable to resolve a
 * string resource, but that is not what it is for: these strings originate in **ViewModels**, which
 * still cannot hold a `Context` under Compose. `stringResource()` would only help if the choice of
 * string were made in the composable, and it is not — `SettingsViewModel` alone builds 123 of them.
 * Deferring the `Resources` lookup to render time is the right shape, and this resolves it here.
 *
 * `ModalBottomSheet` needs no `expandBottomSheetOnStart()`: Material's
 * `BottomSheetDialog` opened at a peek height shorter than its own title bar in landscape, and the
 * Compose sheet has no peek state to get stuck in. That helper does **not** retire yet — the three
 * `BottomSheetDialogFragment`s (bookmarks, the note editor, the speed chooser) still need it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomChooser(
  state: BottomChooserState,
  modifier: Modifier = Modifier,
) {
  if (!state.shouldShow) return

  val sheetState = rememberModalBottomSheetState()
  val resources = LocalContext.current.resources

  ModalBottomSheet(
    // A dismiss is a *cancellation*, which the listener distinguishes from a choice: the View
    // version reported `wasBackgroundClicked` so a caller could tell "chose nothing" from "chose
    // the first option", and three callers rely on that.
    onDismissRequest = { state.listener.onChooserClosed(wasBackgroundClicked = true) },
    sheetState = sheetState,
    modifier = modifier,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      val title = state.title.format(resources)
      if (title.isNotEmpty()) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(
                horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
                vertical = 12.dp,
              ),
        )
      }

      LazyColumn {
        // Keyed by index, not by the string: two options can legitimately have the same text (two
        // servers named "Plex", say), and a duplicate key crashes a `LazyColumn`.
        itemsIndexed(state.options) { index, option ->
          ChooserOption(
            label = option.format(resources),
            onClick = { state.listener.onItemClicked(option) },
            key = index,
          )
        }
      }
    }
  }
}

@Composable
private fun ChooserOption(
  label: String,
  onClick: () -> Unit,
  @Suppress("UNUSED_PARAMETER") key: Int,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurface,
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(
          horizontal = dimensionResource(R.dimen.screen_horizontal_padding),
          vertical = 16.dp,
        ),
  )
}

/** Convenience for a chooser whose options are plain strings. */
fun chooserOptions(vararg options: String): List<FormattableString> = options.map { FormattableString.from(it) }
