package io.github.mattpvaughn.chronicle.features.search.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors

/**
 * A top app bar whose title collapses into a search field (cu-206).
 *
 * Replaces the `SearchView` action view that Home, Library and Collections each wired up through
 * their own `MenuProvider` — three near-identical copies of an expand listener, a collapse
 * listener and an `OnQueryTextListener`, one of which (Library) also had to juggle the other menu
 * items' `showAsAction` on expand. The behaviour is one composable now, so the three screens can
 * no longer drift about what searching looks like.
 *
 * ### The query lives in the ViewModel, not here
 *
 * `SearchView` owned its own text and pushed changes out; that is why closing search needed an
 * explicit `setSearchActive(false)` to clear the results, and why the three screens each had to
 * remember to do it. Here `query` and `isActive` are hoisted, so the field renders whatever the
 * ViewModel says and there is only one copy of the state.
 *
 * @param actions extra items shown when search is **not** active. Library passes three; Home and
 *   Collections pass none. They are hidden while searching, which is what Library's
 *   `setShowAsAction(NEVER)` juggling achieved by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
  title: String,
  isActive: Boolean,
  query: String,
  onQueryChange: (String) -> Unit,
  onActiveChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  actions: @Composable () -> Unit = {},
) {
  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  // Opening search focuses the field and raises the keyboard — `SearchView.setIconified(false)`
  // did this for free, and without it the user must tap the field a second time to type.
  LaunchedEffect(isActive) {
    if (isActive) {
      focusRequester.requestFocus()
      keyboard?.show()
    }
  }

  TopAppBar(
    modifier = modifier,
    title = {
      if (isActive) {
        TextField(
          value = query,
          onValueChange = onQueryChange,
          modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
          placeholder = { Text(stringResource(R.string.search)) },
          singleLine = true,
          textStyle = LocalTextStyle.current,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          // Submitting only dismisses the keyboard: results already update per keystroke, which is
          // what `onQueryTextSubmit` returning true meant on the three SearchViews.
          keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
          colors =
            TextFieldDefaults.colors(
              focusedContainerColor = ChronicleColors.Primary,
              unfocusedContainerColor = ChronicleColors.Primary,
              focusedTextColor = ChronicleColors.TextPrimary,
              unfocusedTextColor = ChronicleColors.TextPrimary,
              cursorColor = ChronicleColors.Accent,
            ),
        )
      } else {
        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    },
    navigationIcon = {
      if (isActive) {
        IconButton(onClick = {
          onActiveChange(false)
          onQueryChange("")
        }) {
          Icon(
            painter = painterResource(R.drawable.ic_arrow_back_white),
            contentDescription = stringResource(R.string.back),
          )
        }
      }
    },
    actions = {
      if (isActive) {
        return@TopAppBar
      }
      IconButton(onClick = { onActiveChange(true) }) {
        Icon(
          painter = painterResource(R.drawable.ic_search_white),
          contentDescription = stringResource(R.string.search),
        )
      }
      actions()
    },
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = ChronicleColors.Primary,
        scrolledContainerColor = ChronicleColors.Primary,
        titleContentColor = ChronicleColors.TextPrimary,
        navigationIconContentColor = ChronicleColors.TextPrimary,
        actionIconContentColor = ChronicleColors.TextPrimary,
      ),
  )
}
