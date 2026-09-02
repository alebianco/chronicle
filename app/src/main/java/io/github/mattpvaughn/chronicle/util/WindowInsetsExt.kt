package io.github.mattpvaughn.chronicle.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads [this] by the top system-bar inset, so a toolbar sits below the status bar
 * rather than under it.
 *
 * `targetSdk 36` enforces edge-to-edge and stops Android insetting app content,
 * so any view that reaches the top of the window has to do this itself (cu-63).
 *
 * The original padding is captured on first call and used as the base, so
 * repeated calls — which happen whenever insets change, e.g. on rotation — add
 * the inset once rather than accumulating it.
 */
fun View.applyTopSystemBarInset() {
  val initialTopPadding = paddingTop
  ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
    val top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
    view.updatePadding(top = initialTopPadding + top)
    windowInsets
  }
  // A view already attached will not receive a fresh dispatch on its own.
  ViewCompat.requestApplyInsets(this)
}

/**
 * Insets a **pinned** app-bar child so it both clears and *paints* the status-bar strip
 * (cu-105).
 *
 * The obvious approach — padding the `AppBarLayout` or the `CollapsingToolbarLayout` — is wrong for
 * a `CollapsingToolbarLayout`, and wrong in a way that measures as correct. Padding the collapsing
 * container pushes the pinned toolbar down to y=inset, so the strip above it belongs to the
 * *scrolling* content: the cover art slides up through it and is visible above the toolbar before
 * leaving the screen. `uiautomator` bounds look right (toolbar at y=48, list below the bar) because
 * the offending view is the collapsing artwork, not the list.
 *
 * The pinned view instead starts at y=0 and carries the inset as its own top padding. It therefore
 * occupies the strip, paints it with its own background, and its content still sits below the
 * status bar. Nothing scrolling can appear above it.
 *
 * Also grows `minHeight` by the same inset: `minHeight` is measured *excluding* padding, so a bar
 * with `exitUntilCollapsed` would otherwise collapse to `actionBarSize` and hide its content under
 * the status bar.
 *
 * @receiver the view carrying `app:layout_collapseMode="pin"` — the toolbar itself, or the wrapper
 *   pinning it. It must have a non-transparent background, or it will clear the strip without
 *   painting it.
 */
fun View.applyTopSystemBarInsetAsPinnedBar() {
  val initialTopPadding = paddingTop
  val initialMinHeight = minimumHeight
  ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
    val top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
    view.updatePadding(top = initialTopPadding + top)
    view.minimumHeight = initialMinHeight + top
    windowInsets
  }
  ViewCompat.requestApplyInsets(this)
}
