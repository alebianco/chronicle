package io.github.mattpvaughn.chronicle.features.library

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every key in [Audiobook.SORT_KEYS] must have a branch in the library's sort comparator.
 *
 * `SORT_KEYS` is not a documentation list — it is the **allowlist used in two places**:
 *
 * - `SharedPreferencesPrefsRepo.bookSortKey`'s setter throws for a value outside it, and
 * - `BACKUP_SETTING_VALUES` uses it to validate `KEY_BOOK_SORT_BY` on settings **import**
 *   (the allowlist gates keys, and for this key it gates values too).
 *
 * So a key listed there is, by construction, a value the app will accept and persist. The
 * comparator in `LibraryViewModel.books` ends in `throw NoWhenBranchMatchedException`, which makes
 * any listed-but-unhandled key a **crash on the library screen** rather than a silent fallback —
 * reachable by importing a settings file, with no way back through the UI because the library is
 * the screen that crashes.
 *
 * Four keys were in exactly that state when this test was written: `genre`, `release_date`,
 * `rating` and `critic_rating`. `SORT_KEY_GENRE` was additionally defined as the string `"title"`,
 * so it silently aliased the title sort rather than being distinct.
 *
 * A source scan rather than an execution test because the comparator lives inside a `combine`
 * lambda in a 701-line ViewModel: reaching it means standing up five flows and a dispatcher to
 * assert something that is really a statement about two lists agreeing. This compares them
 * directly, and fails the build when they drift.
 */
class LibrarySortKeyTest {
  private val viewModelSource =
    File(
      "src/main/java/io/github/mattpvaughn/chronicle/features/library/LibraryViewModel.kt",
    )

  @Test
  fun `the view model source is where this test expects it`() {
    assertTrue(
      "LibraryViewModel.kt not found at ${viewModelSource.absolutePath} — if it moved, update " +
        "this test rather than deleting it.",
      viewModelSource.isFile,
    )
  }

  /**
   * The constant *names* inside `Audiobook.SORT_KEYS`, read from source.
   *
   * Reflection cannot answer this: the list holds the constants' resolved `String` values, and two
   * of them shared a value, so a value-based comparison would silently collapse them into one.
   */
  private fun advertisedSortKeyNames(): Set<String> {
    val model =
      File("src/main/java/io/github/mattpvaughn/chronicle/data/model/Audiobook.kt").readText()
    val list =
      Regex("""val SORT_KEYS\s*=\s*listOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
        .find(model)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    return Regex("""SORT_KEY_[A-Z_]+""").findAll(list).map { it.value }.toSet()
  }

  @Test
  fun `every advertised sort key has a comparator branch`() {
    // Import lines name every key the file *mentions*, so match only `when` branch arms —
    // `SORT_KEY_X ->`. Counting mentions made this test pass while four keys were unhandled.
    val body =
      viewModelSource
        .readLines()
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")
    val handled =
      Regex("""SORT_KEY_[A-Z_]+(?=\s*->)""")
        .findAll(body)
        .map { it.value }
        .toSet()

    val advertised = advertisedSortKeyNames()

    val unhandled = advertised - handled
    assertTrue(
      "These keys are in Audiobook.SORT_KEYS — so the prefs setter and settings import both " +
        "accept them — but have no branch in LibraryViewModel's comparator, which ends in " +
        "`throw NoWhenBranchMatchedException`. Importing a settings file carrying one crashes " +
        "the library screen: $unhandled",
      unhandled.isEmpty(),
    )
  }

  /**
   * Two constants sharing a value makes one of them unreachable as a distinct choice, and hides
   * the duplication from the exhaustiveness check above — a key that aliases `"title"` "works"
   * only by accidentally selecting another key's branch.
   */
  @Test
  fun `no two sort keys share the same string value`() {
    val byValue = Audiobook.SORT_KEYS.groupBy { it }.filter { it.value.size > 1 }
    assertEquals(
      "Duplicate values in Audiobook.SORT_KEYS — a key that aliases another silently selects " +
        "the other's comparator branch: ${byValue.keys}",
      emptyMap<String, List<String>>(),
      byValue,
    )
  }
}
