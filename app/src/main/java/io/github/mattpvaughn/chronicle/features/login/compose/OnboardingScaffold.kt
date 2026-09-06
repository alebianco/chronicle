package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors

/**
 * The frame shared by the three onboarding pickers (cu-206).
 *
 * `onboarding_plex_choose_{server,library,user}.xml` were the same layout three times: a title, a
 * refresh icon and the list. Only the server picker actually wired its refresh icon up — the
 * library picker's had no click listener at all, so it was a button that did nothing. Making
 * [onRefresh] a required parameter is what stops that being expressible.
 *
 * These screens have no toolbar, so the frame takes the status-bar inset itself (cu-63).
 */
@Composable
fun OnboardingScaffold(
  title: String,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Surface(modifier = modifier.fillMaxSize(), color = ChronicleColors.Primary) {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          color = ChronicleColors.TextPrimary,
        )
        IconButton(onClick = onRefresh) {
          Icon(
            painter = painterResource(R.drawable.ic_refresh_white),
            contentDescription = stringResource(R.string.refresh_list),
            tint = ChronicleColors.TextPrimary,
          )
        }
      }
      content()
    }
  }
}
