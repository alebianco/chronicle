package io.github.mattpvaughn.chronicle.views.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Artwork goes through [CoverImage], never a bare `AsyncImage`.
 *
 * ## Why a source scan
 *
 * The bug this closes was **invisible in every other kind of test**. Six screens called
 * `AsyncImage` with no `placeholder`, `error` or `fallback`, so a cover that failed to load
 * rendered as a hole in the layout — not a broken image, just nothing. The semantics tree was
 * correct, so a Compose test asserting on content description or text passed; the screens rendered;
 * the build was green. Only a screenshot showed it, and a screenshot is exactly what nobody takes
 * on a mock-mode run where *all* the covers are blank.
 *
 * A rendering assertion cannot replace this: `AsyncImage` is asynchronous and Robolectric has no
 * network, so a test that asserts "the placeholder is displayed" passes whether or not the
 * production call site declares one.
 *
 * ## What it does not claim
 *
 * That `CoverImage` renders correctly — [CoverImageContractTest] covers the states it can. Only
 * that no screen goes around it.
 */
class CoverImageTest {
  private val mainSources = File(MAIN_SOURCE_ROOT)

  /** Guards the guard: a wrong path would scan nothing and pass. */
  @Test
  fun `the scan reaches the compose sources`() {
    val composeFiles =
      mainSources.walkTopDown()
        .filter { it.extension == "kt" && it.path.contains("/compose/") }
        .count()

    assertTrue("expected to scan the app's composables, saw $composeFiles", composeFiles > 15)
  }

  @Test
  fun `only CoverImage calls AsyncImage`() {
    val offenders =
      mainSources.walkTopDown()
        .filter { it.extension == "kt" && it.name != "CoverImage.kt" }
        .filter { file ->
          file.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .any { it.contains("AsyncImage(") }
        }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals(
      "artwork must go through CoverImage, which carries the placeholder for the offline, " +
        "no-artwork and failed-load cases. A bare AsyncImage renders *nothing* when the load " +
        "fails — a hole in the layout that no semantics assertion can see, which is how six " +
        "screens shipped without one.",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * The placeholder is **drawn**, never loaded through `painterResource`.
   *
   * This is a crash guard, not a style rule. `book_cover_missing_placeholder` is a `<shape>`
   * drawable, and `painterResource` throws `IllegalArgumentException: Only VectorDrawables and
   * rasterized asset types are supported` for one — on the first frame that renders a coverless
   * book. The first cut of this fix did exactly that and killed the app on launch.
   *
   * A `Box` background cannot fail that way: the image draws over it when it arrives, and there is
   * nothing to decode if it never does.
   */
  @Test
  fun `the placeholder is a drawn background, not a loaded drawable`() {
    val source = File(MAIN_SOURCE_ROOT, "views/compose/CoverImage.kt").readText()

    assertTrue(
      "CoverImage must draw its placeholder as a background colour. `painterResource` throws for " +
        "a <shape> drawable, which is what the app's placeholder is — a crash on the first frame " +
        "showing a coverless book, not a fallback.",
      source.contains(".background(PlaceholderColor)"),
    )
    assertTrue(
      "the placeholder colour must match @color/imagePlaceholderColor, so a Compose screen and a " +
        "notification's artwork agree about what 'no cover' looks like.",
      source.contains("0x33B998CC"),
    )
  }

  private companion object {
    const val MAIN_SOURCE_ROOT = "src/main/java/io/github/mattpvaughn/chronicle"
  }
}
