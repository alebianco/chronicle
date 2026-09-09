package io.github.mattpvaughn.chronicle.navigation.circuit

import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.navigation.BookDetailsScreenKey
import io.github.mattpvaughn.chronicle.navigation.ChronicleScreen
import io.github.mattpvaughn.chronicle.navigation.FacetBooksScreenKey
import io.github.mattpvaughn.chronicle.navigation.HomeScreenKey
import io.github.mattpvaughn.chronicle.navigation.SettingsScreenKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The back stack has to survive process death, and this is what makes it.
 *
 * Android evicts a backgrounded app freely. Under Navigation Compose the back stack was route
 * strings in a `Bundle` and came back for free; Circuit keys are objects, so something has to
 * convert them. If that conversion is wrong the user comes back to **Home** instead of the screen
 * they left — a quiet regression no crash reports and no green test suite would show.
 *
 * The round trip is asserted per shape rather than in one loop, because the three that carry
 * arguments are the ones that can lose something.
 */
class ScreenKeySaverTest {
  private fun roundTrip(screen: ChronicleScreen): ChronicleScreen? {
    val encoded = ScreenKeySaver.encode(screen)
    assertNotNull("a Chronicle screen must encode", encoded)
    return ScreenKeySaver.decode(encoded!!)
  }

  @Test
  fun `an argument-free key survives the round trip`() {
    assertEquals(HomeScreenKey, roundTrip(HomeScreenKey))
    assertEquals(SettingsScreenKey, roundTrip(SettingsScreenKey))
  }

  /**
   * A key's argument comes back **as itself**, punctuation included.
   *
   * These are the characters that broke route matching under Navigation Compose and needed
   * `encodeArg`/`decodeArg` on the way through. JSON has no such problem, and this pins that it
   * does not acquire one.
   */
  @Test
  fun `an argument carrying punctuation survives the round trip`() {
    val key = BookDetailsScreenKey("The Hobbit: There/Back? 100%")
    assertEquals(key, roundTrip(key))
  }

  /** Both halves of a two-argument key, including the enum. */
  @Test
  fun `a facet key survives with its kind and value`() {
    val key = FacetBooksScreenKey(FacetKind.Narrator, "Whitfield, June/Nunn")
    val restored = roundTrip(key)
    assertEquals(key, restored)
    assertEquals(FacetKind.Narrator, (restored as FacetBooksScreenKey).kind)
  }

  @Test
  fun `only chronicle screens are saved, and only strings restored`() {
    assertNotNull(ScreenKeySaver.encode(HomeScreenKey))
    // Anything that is not a string this saver wrote comes back null rather than throwing.
    assertNull(ScreenKeySaver.decode(42))
  }

  /**
   * A key written by an older install decodes to null rather than throwing.
   *
   * A saved back stack outlives an app update: the user backgrounds the app, it updates, and comes
   * back to a bundle naming a screen that has since been renamed. Crashing on resume would be the
   * worst possible response — Circuit drops an unrestorable record instead.
   */
  @Test
  fun `a key from a previous version is dropped rather than thrown`() {
    assertNull(ScreenKeySaver.decode("""{"type":"ScreenThatNoLongerExists"}"""))
    assertNull(ScreenKeySaver.decode("not json at all"))
  }
}
