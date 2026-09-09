package io.github.mattpvaughn.chronicle.navigation

import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen keys, and where a login state sends the user.
 *
 * ### What this no longer has to test, and why that is the point
 *
 * `DestinationTest` was 208 lines, and most of it guarded **route-string encoding**: a book title
 * or a facet value containing `/`, `?`, `#` or `%` had to survive a round trip through a URL path
 * segment, because Navigation Compose addressed every destination by string. Getting it wrong
 * navigated **nowhere, silently** — no crash, no error, the user simply tapped and nothing
 * happened — so `encodeArg`/`decodeArg` and their round trip were pinned character by character.
 *
 * A Circuit screen key carries the value as a value. There is no encoding, so there is nothing to
 * test; the defect class is gone rather than guarded. What is left is what a key still has to get
 * right: identity, and the login routing table.
 */
class ScreensTest {
  @Test
  fun `top level screens are the four bottom nav tabs in bar order`() {
    assertEquals(
      listOf(HomeScreenKey, LibraryScreenKey, CollectionsScreenKey, SettingsScreenKey),
      topLevelScreens,
    )
  }

  /**
   * Two keys carrying the same argument are equal, and two carrying different ones are not.
   *
   * This is what makes the back stack work: Circuit compares records by key, so a `class` rather
   * than a `data class` here would make every navigation to the same book push a fresh entry, and
   * `resetRoot` would never recognise the tab it was already on. It is one word in the source and
   * invisible until the back button behaves oddly.
   */
  @Test
  fun `a screen key with the same argument is equal, and with a different one is not`() {
    assertEquals(BookDetailsScreenKey("1001"), BookDetailsScreenKey("1001"))
    assertNotEquals(BookDetailsScreenKey("1001"), BookDetailsScreenKey("1002"))
    assertEquals(CollectionDetailsScreenKey("c1"), CollectionDetailsScreenKey("c1"))
  }

  /**
   * The facet kind is a real enum on the key, so two facets with the same *value* under different
   * kinds are different screens.
   *
   * Under route strings the kind travelled as its `name` and was matched back with a
   * `?: FacetKind.Author` fallback — so a mismatch silently showed the wrong facet rather than
   * failing. There is no parse here to get wrong.
   */
  @Test
  fun `a facet key distinguishes the kind as well as the value`() {
    assertEquals(
      FacetBooksScreenKey(FacetKind.Series, "Dune"),
      FacetBooksScreenKey(FacetKind.Series, "Dune"),
    )
    assertNotEquals(
      FacetBooksScreenKey(FacetKind.Series, "Dune"),
      FacetBooksScreenKey(FacetKind.Author, "Dune"),
    )
  }

  /**
   * An argument reaches the key untouched, punctuation and all.
   *
   * The values that used to break route matching — a `/` in a title, a `?` in a narrator's name —
   * are ordinary strings here.
   */
  @Test
  fun `an argument carrying punctuation is stored verbatim`() {
    assertEquals("The Hobbit: There/Back?", BookDetailsScreenKey("The Hobbit: There/Back?").bookId)
    assertEquals(
      "Whitfield, June/Nunn",
      FacetBooksScreenKey(FacetKind.Narrator, "Whitfield, June/Nunn").value,
    )
  }

  /** Every login state routes somewhere specific, or deliberately nowhere. */
  @Test
  fun `each login state routes to its onboarding step`() {
    assertEquals(
      ChooseUserScreenKey,
      screenForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_USER_CHOSEN),
    )
    assertEquals(
      ChooseServerScreenKey,
      screenForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN),
    )
    assertEquals(
      ChooseLibraryScreenKey,
      screenForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN),
    )
    assertEquals(HomeScreenKey, screenForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY))
    assertEquals(LoginScreenKey, screenForLogin(IPlexLoginRepo.LoginState.NOT_LOGGED_IN))
  }

  /**
   * The two states that must **not** navigate.
   *
   * Both are reported by the login screen itself, and navigating away from it would discard the
   * message the user needs to read. Stated here rather than left for a reader to infer from a
   * `when` with two `null` branches.
   */
  @Test
  fun `a failed or pending login stays where it is`() {
    assertNull(screenForLogin(IPlexLoginRepo.LoginState.FAILED_TO_LOG_IN))
    assertNull(screenForLogin(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS))
  }
}
