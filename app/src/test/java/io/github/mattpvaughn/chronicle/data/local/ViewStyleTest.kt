package io.github.mattpvaughn.chronicle.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An unrecognised view style must degrade, never throw (cu-133).
 *
 * This mapping existed **seven times** and every copy ended in
 * `else -> throw IllegalStateException("Unknown view style")`. Because the value is persisted in
 * preferences, one bad value meant a crash on **every launch**, with the settings screen that
 * could undo it unreachable.
 *
 * Found by seeding `"x"` into preferences on a device: the unit tests were green and the app still
 * died, because the fix had been applied to `AudiobookAdapter` and not to the fragment copies.
 * Hence the scan test at the bottom — a per-site fix is exactly what failed here.
 */
class ViewStyleTest {
  @Test
  fun `each real style maps as before`() {
    assertTrue(viewStyleIsGrid(PrefsRepo.VIEW_STYLE_COVER_GRID))
    assertTrue(!viewStyleIsGrid(PrefsRepo.VIEW_STYLE_TEXT_LIST))
    assertTrue(!viewStyleIsGrid(PrefsRepo.VIEW_STYLE_DETAILS_LIST))

    assertEquals(ViewStyleKind.CoverGrid, ViewStyleKind.of(PrefsRepo.VIEW_STYLE_COVER_GRID))
    assertEquals(ViewStyleKind.TextOnly, ViewStyleKind.of(PrefsRepo.VIEW_STYLE_TEXT_LIST))
    assertEquals(ViewStyleKind.Details, ViewStyleKind.of(PrefsRepo.VIEW_STYLE_DETAILS_LIST))
  }

  /** The exact value that crashed the app on device. */
  @Test
  fun `an unknown style falls back to the grid instead of throwing`() {
    assertTrue(viewStyleIsGrid("x"))
    assertEquals(ViewStyleKind.CoverGrid, ViewStyleKind.of("x"))
  }

  @Test
  fun `an empty style falls back rather than throwing`() {
    assertTrue(viewStyleIsGrid(""))
    assertEquals(ViewStyleKind.CoverGrid, ViewStyleKind.of(""))
  }

  @Test
  fun `every declared style is handled without falling back`() {
    PrefsRepo.VIEW_STYLES.forEach { style ->
      // A declared style must not hit the fallback branch. CoverGrid *is* the fallback, so the
      // meaningful check is that the two list styles resolve to their own kinds.
      if (style != PrefsRepo.VIEW_STYLE_COVER_GRID) {
        assertTrue(
          "$style must not resolve to the fallback",
          ViewStyleKind.of(style) != ViewStyleKind.CoverGrid,
        )
      }
    }
  }

  /**
   * No source file may throw on an unknown view style again.
   *
   * The device crash happened because five of seven copies were missed. A scan is coarse, but it
   * makes the dangerous shape impossible to reintroduce by accident — which is what actually
   * failed.
   */
  @Test
  fun `no source file throws on an unknown view style`() {
    val offenders =
      File(MAIN_SOURCE_ROOT).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
          file.readLines().mapIndexedNotNull { index, line ->
            // Comment lines are skipped: the fix documents the shape it replaced by quoting it,
            // so a raw scan matches the explanation instead of live code (the cu-138 trap).
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("//") || trimmed.startsWith("*")
            val code = line.substringBefore("//")
            if (!isComment && code.contains("throw") && code.contains("Unknown view style")) {
              "${file.name}:${index + 1}"
            } else {
              null
            }
          }
        }.toList()

    assertEquals(
      "use ViewStyleKind.of / viewStyleIsGrid — a persisted bad value must not crash: $offenders",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: the scan above is vacuous if the path resolves to nothing. */
  @Test
  fun `the scan actually inspects sources`() {
    val scanned =
      File(MAIN_SOURCE_ROOT).walkTopDown().count { it.isFile && it.extension == "kt" }

    assertTrue("expected to scan the app's sources, saw $scanned", scanned >= 100)
  }

  private companion object {
    const val MAIN_SOURCE_ROOT = "src/main/java"
  }
}
