package io.github.mattpvaughn.chronicle.features.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import io.github.mattpvaughn.chronicle.features.player.CastAvailability
import io.github.mattpvaughn.chronicle.features.player.PlayServicesCastAvailability
import timber.log.Timber

/**
 * The Cast route button, for a Compose toolbar.
 *
 * ### Why this is an `AndroidView` and not a composable
 *
 * `MediaRouteButton` is a `View` from `androidx.mediarouter`, and the Cast SDK has no Compose
 * surface at all. The button is not decoration: it opens the framework's own route-chooser dialog
 * and reflects discovery state that only the SDK knows. Re-implementing it would mean
 * re-implementing route discovery, which is exactly the hand-rolling decision-19 admits the SDK to
 * avoid.
 *
 * This is the whole of the View island. `MediaRouteButtonFactory` has a public overload taking a
 * bare [MediaRouteButton], so no `Menu` is involved — which is what let the `Menu`-based `CastMenu`
 * and `audiobook_details_menu.xml` go. (Both actually survived as dead code for a while: this KDoc
 * claimed they were deleted a commit before anything deleted them. They are gone now.)
 *
 * ### It still degrades to absent
 *
 * decision-19 admits a proprietary SDK only if a device without it loses the one feature "with no
 * crash, error or nag". [CastAvailability] is therefore checked *before* anything touches the SDK,
 * because `setUpMediaRouteButton` throws rather than degrading when Play services is missing — and
 * a throw is caught besides. On a de-Googled device this composable renders nothing at all, which
 * is the same outcome the `android:visible="false"` menu item produced.
 *
 * Annotated `@UnstableApi` rather than `@OptIn(UnstableApi::class)`: lint's `UnsafeOptInUsageError`
 * recognises only the marker annotation, and `CastPlayerProvider` — the other file naming a Cast
 * type — uses the same form. The marker propagates to every caller, as far as
 * `MainActivity.onCreate`.
 */
@UnstableApi
@Composable
fun CastButton(
  modifier: Modifier = Modifier,
  availability: CastAvailability? = null,
) {
  val context = LocalContext.current
  val castAvailability = availability ?: remember(context) { PlayServicesCastAvailability(context) }

  if (!castAvailability.isCastSupported()) {
    return
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx -> MediaRouteButton(ctx) },
    update = { button ->
      try {
        MediaRouteButtonFactory.setUpMediaRouteButton(button.context, button)
      } catch (e: Throwable) {
        // A missing receiver app id or a stubbed Play services surfaces here. Casting being
        // unavailable is never a reason to fail opening a screen — the same swallow, and the same
        // reasoning, as the CastMenu this replaces.
        Timber.i(e, "Cast route button unavailable")
      }
    },
  )
}
