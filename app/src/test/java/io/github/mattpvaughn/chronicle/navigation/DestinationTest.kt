package io.github.mattpvaughn.chronicle.navigation

import io.github.mattpvaughn.chronicle.data.model.FacetKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Route encoding (cu-206).
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
    assertEquals("book/{bookId}", Destination.BookDetails.ROUTE_PATTERN)
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
}
