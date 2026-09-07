package io.github.mattpvaughn.chronicle.views

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every clickable control is at least [MIN_TOUCH_TARGET_DP] on both axes.
 *
 * Android's accessibility guidance puts the minimum at 48dp — roughly a fingertip. Below that a
 * control is hard to hit for anyone and disproportionately so with a motor impairment or a moving
 * vehicle, which is squarely this app's listening context.
 *
 * **Measured on the tablet before this existed** (`uiautomator dump`, density 240 = 1.5x, so
 * dp = px / 1.5): the player's six secondary controls came back 48px = **32dp** — skip-to-previous,
 * rewind, skip-forward, skip-to-next, sleep timer and bookmark. Play/pause was 64dp and the track
 * rows and nav items 48dp and 56dp, so the defect was specific rather than systemic.
 *
 * A **source** guard rather than a rendering test, for the same reason as [FirstFrameFlashTest]:
 * the size is a static property of the layout, and a test that inflates a view would need a device
 * and would only cover the screens it happened to visit.
 *
 * A view may satisfy the minimum by its declared size **or** by padding around a smaller icon,
 * which is the usual way to keep an icon visually small while leaving it comfortably tappable —
 * that is how the six above were fixed, so the icons did not change size on screen.
 */
class TouchTargetSizeTest {
  private val layoutDir = File("src/main/res/layout")

  private companion object {
    const val MIN_TOUCH_TARGET_DP = 48

    /**
     * Ids exempt from the rule, each with the reason.
     *
     * Deliberately tiny: an exemption is a control someone cannot reliably hit, so it needs a
     * standing justification rather than a shrug.
     */
    val EXEMPT =
      mapOf(
        // Not a control: a decorative corner marker with no click handler of its own. It is
        // inside `grid_item_root`, which is the tappable surface and is full-size.
        "not_played_dog_ear" to "decorative; the row is the touch target",
      )
  }

  /** Resolves `@dimen/foo` against `values/dimens.xml`, or a literal `24dp`. */
  private fun resolveDp(
    raw: String,
    dimens: Map<String, Int>,
  ): Int? =
    when {
      raw.startsWith("@dimen/") -> dimens[raw.removePrefix("@dimen/")]
      raw.endsWith("dp") -> raw.removeSuffix("dp").toIntOrNull()
      else -> null
    }

  private fun readDimens(): Map<String, Int> {
    val file = File("src/main/res/values/dimens.xml")
    if (!file.exists()) return emptyMap()
    return Regex("""<dimen name="([^"]+)">(-?\d+)dp</dimen>""")
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2].toInt() }
  }

  @Test
  fun `every clickable control meets the minimum touch target`() {
    val dimens = readDimens()
    val offenders = mutableListOf<String>()

    layoutDir.walkTopDown().filter { it.extension == "xml" }.forEach { file ->
      val text = file.readText()
      Regex("""<(ImageView|ImageButton|Button)\b([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .forEach { match ->
          val tag = match.groupValues[2]
          // Only views that actually take a tap. A plain decorative ImageView is not a control.
          val clickable = "android:onClick" in tag || """android:clickable="true"""" in tag
          if (!clickable) return@forEach

          val id = Regex("""android:id="@\+id/([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@forEach
          if (id in EXEMPT) return@forEach

          val padding =
            Regex("""android:padding="([^"]+)"""").find(tag)?.groupValues?.get(1)
              ?.let { resolveDp(it, dimens) } ?: 0

          listOf("layout_width", "layout_height").forEach { axis ->
            val raw = Regex("""android:$axis="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@forEach
            // `wrap_content`/`0dp` are sized by content or constraints; this cannot judge them.
            val declared = resolveDp(raw, dimens) ?: return@forEach
            val effective = declared + 2 * padding
            if (effective < MIN_TOUCH_TARGET_DP) {
              offenders += "${file.name}: $id $axis is ${effective}dp (icon ${declared}dp + padding ${padding}dp)"
            }
          }
        }
    }

    assertTrue(
      "clickable controls below ${MIN_TOUCH_TARGET_DP}dp:\n" + offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }
}
