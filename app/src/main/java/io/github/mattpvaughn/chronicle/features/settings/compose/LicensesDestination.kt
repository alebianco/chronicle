package io.github.mattpvaughn.chronicle.features.settings.compose

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensesViewModel
import io.github.mattpvaughn.chronicle.views.compose.ChronicleScaffold
import timber.log.Timber

/**
 * The third-party licences list, as a navigation destination.
 *
 * Replaces `OssLicensesMenuActivity`, which came from `play-services-oss-licenses` — a **Google
 * Play Services** dependency, and so a distribution blocker under decision-1, which puts
 * sideload/F-Droid/homelab first. F-Droid does not accept a GMS dependency, so the tool nominally
 * doing this job could not ship where the app is meant to ship.
 *
 * That also removes an `Activity` from the middle of a Compose app: the old row started an activity
 * from a `LaunchedEffect` guarded by a flag the ViewModel had to clear again, because starting one
 * straight from composition fires on every recomposition. A back-stack entry has none of that
 * machinery.
 *
 * A licence link opens in the browser rather than in-app. A `CustomTabsIntent` was considered and
 * left alone: the settings screen already opens its GitHub link with a plain `ACTION_VIEW`, and
 * two link-opening conventions in one feature is worse than either.
 */
@Composable
fun LicensesDestination(
  onNavigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LicensesViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  ChronicleScaffold(
    title = stringResource(R.string.licenses_screen_title),
    onNavigateUp = onNavigateUp,
    modifier = modifier,
  ) {
    LicensesScreen(
      state = state,
      onLicenseClick = { license ->
        val url = license.url ?: return@LicensesScreen
        try {
          context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
          // A device with no browser at all. Logged rather than swallowed, and deliberately silent
          // to the user: the licence name is still on screen and still readable, so there is
          // nothing actionable to say.
          Timber.w(e, "No browser available to open a license URL")
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
  }
}
