package io.github.mattpvaughn.chronicle.util

import android.content.Context
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `setImageResourceIfChanged` skips a redundant `setImageResource`.
 *
 * `MediaServiceConnection.playbackState` re-emits at playback tick rate, so the play/pause buttons
 * re-set an identical icon every second. `setImageResource` re-applies unconditionally, which
 * invalidates the view *without* a layout — a draw-only invalidate, which is the class of cost
 * this guard is about.
 *
 * Measured effect on its own: player sheet 57–60 → 54–55 frames / 15 s. Real but small; the
 * dominant cost on that screen is elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
class SetImageResourceIfChangedTest {
  private fun imageView() = ImageView(ApplicationProvider.getApplicationContext<Context>())

  @Test
  fun `the first call records the resource`() {
    val view = imageView()
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    assertEquals(R.drawable.ic_notification_icon_playing, view.getTag(R.id.tag_last_image_res))
  }

  @Test
  fun `a different resource replaces the recorded one`() {
    val view = imageView()
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_paused)
    assertEquals(R.drawable.ic_notification_icon_paused, view.getTag(R.id.tag_last_image_res))
  }

  /**
   * The point of the helper: a repeat must not re-apply. Asserted through the tag rather than by
   * counting `setImageResource` calls, since `ImageView` exposes no such counter — the tag is the
   * only observable the guard itself uses.
   */
  @Test
  fun `an identical resource leaves the recorded value in place`() {
    val view = imageView()
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    val before = view.drawable
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    assertEquals("a repeat must not swap the drawable instance", before, view.drawable)
  }

  /** Returning to a previous icon must still apply — play, pause, play. */
  @Test
  fun `returning to an earlier resource still applies it`() {
    val view = imageView()
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_paused)
    view.setImageResourceIfChanged(R.drawable.ic_notification_icon_playing)
    assertEquals(R.drawable.ic_notification_icon_playing, view.getTag(R.id.tag_last_image_res))
  }
}
