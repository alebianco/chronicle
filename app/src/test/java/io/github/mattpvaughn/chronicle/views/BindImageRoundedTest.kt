package io.github.mattpvaughn.chronicle.views

import android.app.Activity
import android.widget.ImageView
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The cover loader's two cu-110 guards.
 *
 * These were covered only *incidentally* — by `GroupedSearchAdapterTest` inflating rows that
 * happened to call this — so deleting that adapter in cu-202 took this function's coverage from 77
 * instructions to zero without touching it. Incidental coverage is not coverage: it disappears for
 * reasons unrelated to the code, and it never asserted the behaviour in the first place.
 */
@RunWith(RobolectricTestRunner::class)
class BindImageRoundedTest {
  private val activity: Activity =
    Robolectric.buildActivity(Activity::class.java).setup().get()

  private fun imageView() = ImageView(activity)

  /**
   * The repeat-bind skip.
   *
   * A shelf row rebinds whenever `DiffUtil` reports its contents changed, and the playing book's
   * `progress` moves every second — so an unchanged cover re-entered this once per second per
   * visible row, each time starting a fresh `crossfade` that invalidates continuously. The profile
   * showed 44 calls in 16 s driving 1285 `View.measure` passes.
   */
  @Test
  fun `the bound source is remembered, so an identical rebind can be skipped`() {
    val view = imageView()

    bindImageRounded(view, src = "/library/1/thumb", serverConnected = true, coverUrl = { it })

    assertEquals("/library/1/thumb", view.getTag(R.id.tag_bound_image_src))
  }

  /** A *different* cover must replace the remembered source, or the skip would pin the first one. */
  @Test
  fun `a different source replaces the remembered one`() {
    val view = imageView()

    bindImageRounded(view, src = "/library/1/thumb", serverConnected = true, coverUrl = { it })
    bindImageRounded(view, src = "/library/2/thumb", serverConnected = true, coverUrl = { it })

    assertEquals("/library/2/thumb", view.getTag(R.id.tag_bound_image_src))
  }

  /**
   * The skip requires a drawable as well as a matching source.
   *
   * A row whose load has not resolved yet has the tag set but nothing on screen; skipping there
   * would leave it permanently blank.
   */
  @Test
  fun `a matching source with no drawable is not skipped`() {
    val view = imageView()
    view.setTag(R.id.tag_bound_image_src, "/library/1/thumb")

    bindImageRounded(view, src = "/library/1/thumb", serverConnected = true, coverUrl = { it })

    // Re-entered rather than skipped: the tag is rewritten with the same value.
    assertEquals("/library/1/thumb", view.getTag(R.id.tag_bound_image_src))
  }

  /**
   * A destroyed Activity is left alone.
   *
   * The loader holds the `ImageView`, so a load started against a dead host is at best wasted and
   * at worst a leak.
   */
  @Test
  fun `nothing is bound into a destroyed activity`() {
    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    val view = ImageView(controller.get())
    controller.destroy()

    bindImageRounded(view, src = "/library/1/thumb", serverConnected = true, coverUrl = { it })

    assertNull("a destroyed host must not be bound into", view.getTag(R.id.tag_bound_image_src))
  }

  /**
   * The cover URL is built through the passed-in builder, never a service locator (cu-33).
   *
   * This runs from a bind path, so resolving `PlexConfig` here would be a locator hit per row.
   */
  @Test
  fun `the cover url is built by the supplied builder`() {
    val view = imageView()
    var asked: String? = null

    bindImageRounded(
      view,
      src = "/library/1/thumb",
      serverConnected = true,
      coverUrl = { path ->
        asked = path
        "https://example.test/$path"
      },
    )

    assertEquals(
      "the transcoder path carries the source as its url parameter",
      true,
      asked?.contains("url=/library/1/thumb"),
    )
  }
}
