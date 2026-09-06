package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleColors

/**
 * The standard screen frame: a top app bar over a body (cu-206).
 *
 * Replaces the `AppBarLayout` + `Toolbar` + `ComposeView` sandwich that eight Fragment layouts
 * each spelled out, and the `applyTopSystemBarInset()` call every one of them made — `Scaffold`
 * and `TopAppBar` consume the status-bar inset themselves, so there is nothing to apply by hand.
 *
 * @param title the toolbar title; pass an empty string for a toolbar that shows only a back arrow.
 * @param onNavigateUp when non-null, a back arrow is shown and this is what it calls. A top-level
 *   screen (one of the four bottom-nav tabs) passes null, which is what makes the arrow absent
 *   there — the old code expressed the same thing by simply not calling
 *   `setNavigationOnClickListener`.
 * @param actions the toolbar's menu items, replacing the `MenuProvider` + menu XML pairs. Only
 *   `AudiobookDetailsScreen` needs a non-Compose item, and it uses [CastButton].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChronicleScaffold(
  title: String,
  modifier: Modifier = Modifier,
  onNavigateUp: (() -> Unit)? = null,
  actions: @Composable () -> Unit = {},
  scrollBehavior: TopAppBarScrollBehavior? = null,
  content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
    modifier =
      modifier
        .fillMaxSize()
        .let { if (scrollBehavior != null) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it },
    containerColor = ChronicleColors.Primary,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          if (onNavigateUp != null) {
            IconButton(onClick = onNavigateUp) {
              Icon(
                painter = painterResource(R.drawable.ic_arrow_back_white),
                contentDescription = stringResource(R.string.back),
              )
            }
          }
        },
        actions = { actions() },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = ChronicleColors.Primary,
            scrolledContainerColor = ChronicleColors.Primary,
            titleContentColor = ChronicleColors.TextPrimary,
            navigationIconContentColor = ChronicleColors.TextPrimary,
            actionIconContentColor = ChronicleColors.TextPrimary,
          ),
        scrollBehavior = scrollBehavior,
      )
    },
  ) { padding ->
    Box(modifier = Modifier.padding(padding)) {
      content(padding)
    }
  }
}
