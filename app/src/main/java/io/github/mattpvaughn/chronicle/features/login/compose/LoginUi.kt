package io.github.mattpvaughn.chronicle.features.login.compose

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.FEATURE_FLAG_IS_AUTO_ENABLED
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.features.login.LoginViewModel
import io.github.mattpvaughn.chronicle.util.compose.EventEffect
import io.github.mattpvaughn.chronicle.util.compose.LifecycleResumeEffect
import io.github.mattpvaughn.chronicle.util.compose.ToastEffect
import timber.log.Timber

/**
 * The sign-in screen, as a Circuit screen.
 *
 * One of the three screens the earlier Compose migrations never reached — it was still pure Views,
 * so this is a real migration rather than a shell swap. Three `View.visibility` writes and a
 * `setOnCheckedChangeListener` become state; the Custom Tabs launch stays exactly as it was,
 * because it is an `Intent` and has
 * nothing to do with the view layer.
 *
 * `checkForAccess()` on resume is what completes the login: the user leaves for a browser, approves
 * a PIN and comes back, and only a resume tells us to look. [LifecycleResumeEffect] is the
 * equivalent of the Fragment's `onResume`.
 */
@Composable
fun LoginUi(
  prefsRepo: PrefsRepo,
  modifier: Modifier = Modifier,
  viewModel: LoginViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
  var allowAuto by remember { mutableStateOf(prefsRepo.allowAuto) }

  LifecycleResumeEffect { viewModel.checkForAccess() }

  ToastEffect(viewModel.errorEvent)
  EventEffect(viewModel.errorEvent) { error -> Timber.e("Login error: $error") }

  EventEffect(viewModel.authEvent) { oAuthPin ->
    if (oAuthPin != null) {
      // The close button is tinted black because the Custom Tab's toolbar is light; the drawable
      // is white for the app's own dark toolbars.
      val backButton =
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_arrow_back_white, context.theme)
          ?.apply { setTint(Color.BLACK) }
      val backButtonBitmap: Bitmap? = (backButton as? BitmapDrawable)?.bitmap

      val colorSchemeParams =
        CustomTabColorSchemeParams.Builder()
          .setToolbarColor(ContextCompat.getColor(context, R.color.colorPrimary))
          .build()

      val builder =
        CustomTabsIntent.Builder()
          .setDefaultColorSchemeParams(colorSchemeParams)
          .setShowTitle(true)
      if (backButtonBitmap != null) {
        builder.setCloseButtonIcon(backButtonBitmap)
      }

      val url = viewModel.makeOAuthLoginUrl(oAuthPin.clientIdentifier, oAuthPin.code)
      viewModel.setLaunched(true)
      builder.build().launchUrl(context, url)
    }
  }

  Surface(modifier = modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Button(onClick = viewModel::loginWithOAuth) {
        Text(stringResource(R.string.login_with_plex))
      }

      if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
      }

      if (FEATURE_FLAG_IS_AUTO_ENABLED) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = 16.dp),
        ) {
          Checkbox(
            checked = allowAuto,
            onCheckedChange = {
              allowAuto = it
              prefsRepo.allowAuto = it
            },
          )
          Text(stringResource(R.string.allow_auto))
        }
      }
    }
  }
}
