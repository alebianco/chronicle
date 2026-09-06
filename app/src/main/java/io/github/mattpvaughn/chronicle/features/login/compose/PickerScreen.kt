package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus

/** One row of a login picker: a label, an optional second line, and the value to report back. */
data class PickerItem<T>(
  val id: String,
  val title: String,
  val subtitle: String? = null,
  val value: T,
)

/**
 * The list-picker shared by the three login steps (cu-201).
 *
 * `ChooseServerFragment`, `ChooseLibraryFragment` and `ChooseUserFragment` are the same screen
 * three times: a list gated on `LoadingStatus`, with a spinner and an error message. Each wrote
 * the same three `isVisible` assignments — and each of those triads is a state machine spelled as
 * booleans, so nothing stopped two being true at once.
 *
 * `LoadingStatus` is already an enum, so the `when` here is exhaustive and the contradiction is
 * unrepresentable. That is why these three screens migrate as one composable rather than three.
 */
@Composable
fun <T> PickerScreen(
  status: LoadingStatus,
  items: List<PickerItem<T>>,
  errorMessage: String,
  onItemClick: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (status) {
      LoadingStatus.LOADING ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      LoadingStatus.ERROR -> CenteredMessage(errorMessage)
      LoadingStatus.DONE ->
        LazyColumn(Modifier.fillMaxSize()) {
          items(items, key = { it.id }) { item ->
            PickerRow(item, onItemClick)
          }
        }
    }
  }
}

@Composable
private fun <T> PickerRow(
  item: PickerItem<T>,
  onItemClick: (T) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = item.title) { onItemClick(item.value) }
      .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    Text(
      text = item.title,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onBackground,
    )
    item.subtitle?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

@Composable
private fun CenteredMessage(text: String) {
  Column(
    Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = text },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
