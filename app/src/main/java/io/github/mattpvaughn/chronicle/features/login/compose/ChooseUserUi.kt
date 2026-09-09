package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.features.login.ChooseUserViewModel
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect

/** A PIN is complete at this length, and submits itself. */
private const val PIN_LENGTH = 4

/**
 * The user picker and its PIN entry screen, as a Circuit screen.
 *
 * ### Back handling moves here, and stops being the Activity's business
 *
 * `Navigator.onBackPressed` reached into this screen — it looked the fragment up by tag, cast it to
 * `ChooseUserFragment`, called `isPinEntryScreenVisible()` and then `hidePinEntryScreen()`. Those
 * two public methods existed **only** for that call, which is why they were public on a Fragment at
 * all. A `BackHandler` keyed on the same condition puts the decision in the screen that owns it,
 * and both methods retire with the Fragment.
 *
 * The PIN text is `rememberSaveable`, so it survives a rotation the way the `EditText` did. It is
 * deliberately *not* in the ViewModel: `setPinData` already pushes each change there, and holding
 * it in both places is how a text field and its state get out of step.
 */
@Composable
fun ChooseUserUi(
  modifier: Modifier = Modifier,
  viewModel: ChooseUserViewModel = hiltViewModel(),
) {
  val users by viewModel.users.collectAsStateWithLifecycle()
  val status by viewModel.usersLoadingStatus.collectAsStateWithLifecycle()
  val showPin by viewModel.showPin.collectAsStateWithLifecycle()
  val pinLoading by viewModel.pinLoadingStatus.collectAsStateWithLifecycle()
  val pinError by viewModel.pinErrorMessage.collectAsStateWithLifecycle()

  ToastEffect(viewModel.userMessage)

  if (showPin) {
    BackHandler { viewModel.hidePinScreen() }
    PinEntry(
      isLoading = pinLoading == LoadingStatus.LOADING,
      errorMessage = pinError,
      onPinChanged = viewModel::setPinData,
      onSubmit = viewModel::submitPin,
      onBack = viewModel::hidePinScreen,
      modifier = modifier,
    )
    return
  }

  OnboardingScaffold(
    title = stringResource(R.string.choose_user),
    onRefresh = viewModel::refresh,
    modifier = modifier,
  ) {
    PickerScreen(
      status = status,
      items =
        users.map {
          PickerItem(
            id = it.uuid.ifEmpty { it.id.toString() },
            title = it.title,
            subtitle = it.username?.takeIf { name -> name.isNotEmpty() && name != it.title },
            value = it,
          )
        },
      errorMessage = stringResource(R.string.no_user_found),
      onItemClick = viewModel::pickUser,
    )
  }
}

@Composable
private fun PinEntry(
  isLoading: Boolean,
  errorMessage: String?,
  onPinChanged: (CharSequence) -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var pin by rememberSaveable { mutableStateOf("") }
  val keyboard = LocalSoftwareKeyboardController.current
  val submit =
    remember(onSubmit) {
      {
        keyboard?.hide()
        onSubmit()
      }
    }

  Surface(modifier = modifier.fillMaxSize(), color = ChronicleColors.Primary) {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
      IconButton(onClick = onBack) {
        Icon(
          painter = painterResource(R.drawable.ic_arrow_back_white),
          contentDescription = stringResource(R.string.back),
          tint = ChronicleColors.TextPrimary,
        )
      }
      Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        OutlinedTextField(
          value = pin,
          onValueChange = { entered ->
            pin = entered
            onPinChanged(entered)
            // The EditText auto-submitted at four characters; keep that, since a PIN has a known
            // length and asking for a second tap is friction with no purpose.
            if (entered.length >= PIN_LENGTH) {
              submit()
            }
          },
          label = { Text(stringResource(R.string.pin)) },
          singleLine = true,
          isError = errorMessage != null,
          supportingText = errorMessage?.let { { Text(it, color = ChronicleColors.TextError) } },
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          modifier = Modifier.fillMaxWidth(),
        )

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          if (isLoading) {
            CircularProgressIndicator(color = ChronicleColors.Accent)
          }
        }
      }
    }
  }
}
