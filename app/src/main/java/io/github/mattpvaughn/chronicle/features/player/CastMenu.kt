package io.github.mattpvaughn.chronicle.features.player

import android.content.Context
import android.view.Menu
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.util.UnstableApi
import io.github.mattpvaughn.chronicle.R
import timber.log.Timber

/**
 * Attaches the Cast route button to a toolbar menu, or leaves it hidden.
 *
 * The menu item ships `android:visible="false"`, so doing nothing is the correct outcome on a
 * device without Play services — the button appears only once the Cast SDK has resolved, and the
 * framework then hides it again whenever no receiver is on the network. That is the behaviour the
 * task asks for, and it falls out of `MediaRouteActionProvider` rather than being hand-rolled.
 *
 * The second file that names a Cast type, after [CastPlayerProvider]. It is kept apart from the
 * Fragments so a UI edit cannot accidentally introduce a Play-services class reference into a
 * widely-loaded class.
 */
object CastMenu {
  /**
   * Shows the route button when Cast is usable here.
   *
   * [castAvailability] is checked first because `setUpMediaRouteButton` reaches into the Cast SDK,
   * which throws rather than degrading when Play services is absent.
   */
  @UnstableApi
  fun setUp(
    context: Context,
    menu: Menu,
    castAvailability: CastAvailability,
  ) {
    if (!castAvailability.isCastSupported()) {
      return
    }
    try {
      MediaRouteButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item)
    } catch (e: Throwable) {
      // A missing receiver app id or a stubbed Play services surfaces here. The button simply stays
      // hidden; casting being unavailable is never a reason to fail opening a screen.
      Timber.i(e, "Cast route button unavailable")
    }
  }
}
