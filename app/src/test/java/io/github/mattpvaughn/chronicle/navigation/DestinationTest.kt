package io.github.mattpvaughn.chronicle.navigation

import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsViewModel
import io.github.mattpvaughn.chronicle.features.browse.FacetBooksViewModel
import io.github.mattpvaughn.chronicle.features.collections.CollectionDetailsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Route encoding.
 *
 * These matter because a route that fails to match its pattern navigates **nowhere, silently** —
 * there is no crash and no error, the user simply taps and nothing happens. Book titles and facet
 * values are arbitrary server text, so the characters below genuinely occur.
 */
class DestinationTest {
  @Test
  fun `top level destinations are the four bottom nav tabs in bar order`() {
    assertEquals(
      listOf("home", "library", "collections", "settings"),
      Destination.topLevel.map { it.route },
    )
  }

  @Test
  fun `book route matches its own pattern shape`() {
    assertEquals("book/12345", Destination.BookDetails("12345").route)
    assertEquals(
      "book/{${AudiobookDetailsViewModel.ARG_AUDIOBOOK_ID}}",
      Destination.BookDetails.ROUTE_PATTERN,
    )
  }

  /**
   * The route argument names *are* the names the ViewModels read from `SavedStateHandle`.
   *
   * This is the one that would fail silently in production. Navigation Compose puts a route
   * argument into the same `SavedStateHandle` the ViewModel reads, so a route declaring
   * `{bookId}` while `AudiobookDetailsViewModel` reads `"audiobook_id"` compiles, navigates, and
   * renders an **empty screen** — the ViewModel reads null and falls back to its default. Asserting
   * the constants are shared is what stops the two drifting apart.
   */
  @Test
  fun `route argument names are the ones the ViewModels read`() {
    assertEquals(AudiobookDetailsViewModel.ARG_AUDIOBOOK_ID, Destination.BookDetails.ARG_BOOK_ID)
    assertEquals(
      CollectionDetailsViewModel.ARG_COLLECTION_ID,
      Destination.CollectionDetails.ARG_COLLECTION_ID,
    )
    assertEquals(FacetBooksViewModel.ARG_KIND, Destination.FacetBooks.ARG_KIND)
    assertEquals(FacetBooksViewModel.ARG_VALUE, Destination.FacetBooks.ARG_VALUE)
  }

  @Test
  fun `a slash in an argument is encoded so it cannot split the path`() {
    // The real hazard: an unencoded "/" makes "facet/Series/Hitchhiker's 1/2" a *four* segment
    // path against a three segment pattern, which matches nothing.
    val route = Destination.FacetBooks(FacetKind.Series, "Hitchhiker's 1/2").route

    assertEquals("facet/Series/Hitchhiker's 1%2F2", route)
    assertEquals(2, route.count { it == '/' })
  }

  @Test
  fun `spaces survive a round trip as spaces, not plus signs`() {
    // URLEncoder would produce "The+Hobbit" here, which renders literally in a toolbar.
    val title = "The Hobbit"

    assertEquals("The Hobbit", encodeArg(title))
    assertEquals(title, decodeArg(encodeArg(title)))
  }

  @Test
  fun `every reserved character round trips`() {
    val nasty = "a/b?c#d%e"

    assertEquals(nasty, decodeArg(encodeArg(nasty)))
  }

  @Test
  fun `a literal percent-encoded sequence is not decoded twice`() {
    // "%2F" typed by a user (or present in server metadata) must come back as "%2F", not "/".
    // This is why encodeArg escapes '%' and decodeArg unescapes it last.
    val literal = "%2F"

    assertEquals("%252F", encodeArg(literal))
    assertEquals(literal, decodeArg(encodeArg(literal)))
  }

  @Test
  fun `unicode is left intact`() {
    val title = "Les Misérables 日本語"

    assertEquals(title, encodeArg(title))
    assertEquals(title, decodeArg(encodeArg(title)))
  }

  @Test
  fun `collection and facet routes encode their arguments`() {
    assertEquals("collection/a%2Fb", Destination.CollectionDetails("a/b").route)
    assertEquals(
      "facet/Author/Tolkien, J.R.R.",
      Destination.FacetBooks(FacetKind.Author, "Tolkien, J.R.R.").route,
    )
  }

  /**
   * Login routing, which was the `when` inside `Navigator`'s init block.
   *
   * The two null branches are the interesting half: staying put is a decision, and a reader
   * skimming the `when` could easily "fix" them into navigation. The login screen reports both
   * states itself, so navigating away would discard the message.
   */
  @Test
  fun `each login state routes where the Navigator sent it`() {
    assertEquals(
      Destination.ChooseUser,
      destinationForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_USER_CHOSEN),
    )
    assertEquals(
      Destination.ChooseServer,
      destinationForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN),
    )
    assertEquals(
      Destination.ChooseLibrary,
      destinationForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN),
    )
    assertEquals(Destination.Home, destinationForLogin(IPlexLoginRepo.LoginState.LOGGED_IN_FULLY))
    assertEquals(Destination.Login, destinationForLogin(IPlexLoginRepo.LoginState.NOT_LOGGED_IN))
  }

  @Test
  fun `a failed or in-flight login does not navigate`() {
    assertNull(destinationForLogin(IPlexLoginRepo.LoginState.FAILED_TO_LOG_IN))
    assertNull(destinationForLogin(IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS))
  }

  /** Guards the guard: a state added later must be routed deliberately, not silently dropped. */
  @Test
  fun `every login state is accounted for`() {
    val routed = IPlexLoginRepo.LoginState.entries.map { it to destinationForLogin(it) }

    assertEquals(7, routed.size)
    assertEquals(2, routed.count { it.second == null })
  }
}
