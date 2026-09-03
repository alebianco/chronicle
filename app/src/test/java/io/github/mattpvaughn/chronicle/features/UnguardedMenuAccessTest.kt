package io.github.mattpvaughn.chronicle.features

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * No fragment may dereference a menu item without a null check (cu-102).
 *
 * This crashed the app fourteen times in one listening session, and because the crash killed the
 * process it also killed playback — the two symptoms the owner reported ("stops every 10-15
 * minutes", "crashes when I unlock") were one bug. Audio was released 0.4s after each crash.
 *
 * The mechanism is a lifecycle mismatch that is easy to reintroduce and invisible on inspection:
 *
 * - `setSupportActionBar(toolbar)` hands the toolbar's menu to the **activity's** `MenuHost`, so the
 *   menu inflated by `app:menu` in XML no longer belongs to the toolbar;
 * - the `MenuProvider` that repopulates it is registered at `Lifecycle.State.RESUMED`;
 * - a `LiveData` observer becomes active at **STARTED** and immediately replays its cached value.
 *
 * Between STARTED and RESUMED the menu is therefore empty. Every screen-on replayed
 * `isWatchedIcon` into a `findItem(...)` that returned null. It needed a book-details screen to be
 * open, which is why it looked periodic rather than constant.
 *
 * A guard test rather than a Fragment test because `fragment-testing` is not on the classpath (see
 * cu-33 notes on the coverage ceiling). It is a text scan, so it is coarse — it cannot see *when*
 * an observer fires — but it makes the dangerous shape impossible to write by accident, which is
 * what actually failed here. Replace it with a `FragmentScenario` test that drives
 * STARTED -> RESUMED once that dependency lands.
 */
class UnguardedMenuAccessTest {
  private val fragmentSources: List<File>
    get() =
      File("src/main/java/io/github/mattpvaughn/chronicle")
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith("Fragment.kt") }
        .toList()

  /**
   * `findItem(...)` must be followed by `?.`, never `.` — and must never be wrapped in `!!`.
   *
   * Matching the call and the very next character is enough: `findItem(x).setIcon(y)` is the exact
   * expression that threw, and `findItem(x)!!` is the same bug written more confidently.
   */
  @Test
  fun `no fragment dereferences a toolbar menu item without a null check`() {
    val unguarded =
      fragmentSources.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          val match = FIND_ITEM.find(line) ?: return@mapIndexedNotNull null
          val after = line.substring(match.range.last + 1)
          val isGuarded = after.startsWith("?.") || after.isBlank() || after.startsWith(")")
          if (isGuarded && !after.startsWith("!!")) {
            null
          } else {
            "${file.name}:${index + 1}  ${line.trim()}"
          }
        }
      }

    assertEquals(
      "a menu item is null between STARTED and RESUMED; dereference it with `?.` " +
        "and re-apply the state in onPrepareMenu:\n" + unguarded.joinToString("\n"),
      emptyList<String>(),
      unguarded,
    )
  }

  /**
   * The guard alone is not the fix.
   *
   * Silently skipping the write leaves the icon showing the wrong state — a book marked read would
   * still show the unread icon until something else touched the menu. Any fragment that guards a
   * menu lookup must also re-apply it once the menu exists, which is what `onPrepareMenu` is for.
   */
  @Test
  fun `a fragment that reads a toolbar menu item also implements onPrepareMenu`() {
    val missing =
      fragmentSources.filter { file ->
        val text = file.readText()
        FIND_ITEM.containsMatchIn(text) && !text.contains("onPrepareMenu")
      }.map { it.name }

    assertEquals(
      "these read a menu item but never re-apply it once the menu is populated, so the icon " +
        "keeps whatever state it had: " + missing.joinToString(),
      emptyList<String>(),
      missing,
    )
  }

  /**
   * Guards the guard: both scans above assert `emptyList()`, so a `fragmentSources` that resolves
   * to nothing passes them trivially. Sabotaging the path to a non-existent directory used to
   * leave the build green (cu-102 review, 2026-09-02).
   */
  @Test
  fun `the scan actually inspects fragments`() {
    val scanned = fragmentSources.size

    assert(scanned >= 8) { "expected to scan the app's fragments, saw $scanned" }
  }

  private companion object {
    /**
     * A lookup against a **toolbar's own** menu, e.g. `binding.detailsToolbar.menu.findItem(x)`.
     *
     * Deliberately not every `findItem`. Inside `MenuProvider.onCreateMenu(menu, inflater)` the
     * menu is the parameter, handed over already inflated, and `HomeFragment`, `LibraryFragment`
     * and `CollectionsFragment` all read it there safely. The hazard is specifically reaching for a
     * toolbar's menu from a callback that can run before the provider has populated it.
     */
    val FIND_ITEM = Regex("""\.menu\.findItem\([^)]*\)""")
  }
}
