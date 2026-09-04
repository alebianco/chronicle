package io.github.mattpvaughn.chronicle.util

import android.widget.ImageView
import androidx.annotation.DrawableRes
import io.github.mattpvaughn.chronicle.R

/**
 * Sets a drawable resource only when it differs from the one already showing (cu-140).
 *
 * `ImageView.setImageResource` re-resolves and re-applies the drawable unconditionally, which
 * invalidates the view *without* requesting a layout — a draw-only invalidate. That is exactly the
 * cost cu-140 exists to remove: `MediaServiceConnection.playbackState` re-emits at playback tick
 * rate, so the play/pause buttons were re-setting an identical icon every tick, each one costing a
 * frame that changes nothing on screen.
 *
 * Mirrors [setTextIfChanged], which solved the same problem for the text views (cu-117).
 *
 * The last resource is remembered in a view tag rather than read back from the view: an
 * `ImageView` does not expose which resource id its current drawable came from, and comparing
 * `Drawable` instances does not work — `getDrawable()` returns a new wrapper for the same resource.
 */
fun ImageView.setImageResourceIfChanged(
  @DrawableRes resId: Int,
) {
  if (getTag(R.id.tag_last_image_res) == resId) {
    return
  }
  setTag(R.id.tag_last_image_res, resId)
  setImageResource(resId)
}
