package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/**
 * The note attached to one bookmark (cu-206).
 *
 * Replaces `ModalBottomSheetBookmarkNote`, the last of the three screens still written in Views.
 * That one had no ViewModel and no injection at all — arguments in, a `Listener` out — which
 * translates directly into parameters and callbacks.
 *
 * The caret starts at the end of an existing note, which is what `setSelection(note.length)` did:
 * editing a note should not require repositioning the cursor first. That needs a
 * [TextFieldValue] rather than a plain `String`, since the selection is part of the value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkNoteSheet(
  positionMillis: Long,
  existingNote: String,
  onSave: (String) -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var note by
    rememberSaveable(existingNote, stateSaver = TextFieldValue.Saver) {
      mutableStateOf(
        TextFieldValue(existingNote, TextRange(existingNote.length)),
      )
    }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = modifier,
    containerColor = ChronicleColors.PrimaryDark,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.bookmark_added, formatPrecisePosition(positionMillis)),
        style = MaterialTheme.typography.titleMedium,
        color = ChronicleColors.TextPrimary,
      )

      OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text(stringResource(R.string.bookmark_note_hint)) },
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(onClick = onDelete) {
          Text(stringResource(R.string.bookmark_delete), color = ChronicleColors.TextError)
        }
        TextButton(onClick = { onSave(note.text) }) {
          Text(stringResource(R.string.bookmark_save), color = ChronicleColors.Accent)
        }
      }
    }
  }
}
