package io.github.mattpvaughn.chronicle.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android Auto browse tree's identifiers (cu-99).
 *
 * The tree used to be keyed on `getString(R.string.auto_category_*)` — the localized label was the
 * media id *and* the value matched in `onLoadChildren`. Browsing therefore worked only in the locale
 * the tree was built in; after a language change nothing matched and Auto showed empty lists.
 *
 * These tests deliberately assert on the *ids alone*, with no `Context` and no resources in sight.
 * That is the property being fixed: if resolving a category ever needs a localized string again,
 * this file stops compiling.
 */
class AutoBrowseCategoryTest {
  @Test
  fun `every category resolves from its own id`() {
    AutoBrowseCategory.entries.forEach { category ->
      assertEquals(
        "a category must resolve from the id it publishes",
        category,
        AutoBrowseCategory.fromId(category.id),
      )
    }
  }

  /**
   * The regression itself: a localized label must not resolve a category. English is the value the
   * old code would have matched, so if labels ever creep back into identity this fails.
   */
  @Test
  fun `a localized label does not resolve a category`() {
    listOf("Recently listened", "Library", "Offline", "Recently added").forEach { label ->
      assertNull(
        "matching on display text is what broke browsing after a language change",
        AutoBrowseCategory.fromId(label),
      )
    }
  }

  /** A book id reaching `onLoadChildren` must not be mistaken for a category. */
  @Test
  fun `a book id does not resolve a category`() {
    assertNull(AutoBrowseCategory.fromId("1001"))
    assertNull(AutoBrowseCategory.fromId(""))
  }

  @Test
  fun `ids are unique`() {
    val ids = AutoBrowseCategory.entries.map { it.id }
    assertEquals("a duplicate id would make one category unreachable", ids.size, ids.distinct().size)
  }

  /**
   * Ids are wire format — Auto can hold one across a process restart — so they are pinned here.
   * A rename must be a deliberate act that breaks a test, not a silent side effect of a refactor.
   */
  @Test
  fun `ids are stable values`() {
    assertEquals("chronicle.auto.recently_listened", AutoBrowseCategory.RecentlyListened.id)
    assertEquals("chronicle.auto.offline", AutoBrowseCategory.Offline.id)
    assertEquals("chronicle.auto.recently_added", AutoBrowseCategory.RecentlyAdded.id)
    assertEquals("chronicle.auto.library", AutoBrowseCategory.Library.id)
  }

  /**
   * Guards the separation itself: an id must not *be* a label. A resource id is an int, so the only
   * way identity could regress to display text is an id that reads like prose.
   */
  @Test
  fun `an id is not display text`() {
    AutoBrowseCategory.entries.forEach { category ->
      assertTrue(
        "${category.id} looks like a label, not an identifier",
        category.id.startsWith("chronicle.auto.") && !category.id.contains(' '),
      )
      assertNotEquals(0, category.labelRes)
    }
  }
}
