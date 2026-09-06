package io.github.mattpvaughn.chronicle.navigation

import io.github.mattpvaughn.chronicle.data.model.FacetKind

/**
 * Every screen the app can navigate to, and the route strings Navigation Compose addresses them by
 * (cu-206).
 *
 * This is deliberately a **framework-free** type: building a route and parsing one back are pure
 * string operations, and keeping them here means the encoding is unit-testable without a NavHost,
 * an Activity or Robolectric. `NavGraph.kt` is the only thing that turns these into `composable {}`
 * entries.
 *
 * ### Why arguments are encoded rather than passed as objects
 *
 * Navigation Compose 2.9 addresses destinations by string route, so every argument makes a round
 * trip through a URL path segment. Two of ours can contain arbitrary user/server text — a book
 * title, a facet value like `"Tolkien, J.R.R."` — and a raw `/` or `?` in one of those silently
 * fails to match the route pattern, landing the user nowhere with no error. [encodeArg] and
 * [decodeArg] are the single place that is handled.
 *
 * Note the *book* destination carries only the id. Title and cached-state were passed as Fragment
 * arguments (`ARG_AUDIOBOOK_TITLE`, `ARG_IS_AUDIOBOOK_CACHED`) purely so the toolbar could render
 * before the DB read returned; the screen already reads all three from the repository keyed on id,
 * so carrying them in the route would be duplicating state that can go stale.
 */
sealed interface Destination {
  /** The route string this destination navigates to. */
  val route: String

  data object Home : Destination {
    const val ROUTE = "home"

    override val route = ROUTE
  }

  data object Library : Destination {
    const val ROUTE = "library"

    override val route = ROUTE
  }

  data object Collections : Destination {
    const val ROUTE = "collections"

    override val route = ROUTE
  }

  data object Settings : Destination {
    const val ROUTE = "settings"

    override val route = ROUTE
  }

  data object Browse : Destination {
    const val ROUTE = "browse"

    override val route = ROUTE
  }

  data object SeriesIndexTester : Destination {
    const val ROUTE = "series-index-tester"

    override val route = ROUTE
  }

  data object Login : Destination {
    const val ROUTE = "login"

    override val route = ROUTE
  }

  data object ChooseUser : Destination {
    const val ROUTE = "choose-user"

    override val route = ROUTE
  }

  data object ChooseServer : Destination {
    const val ROUTE = "choose-server"

    override val route = ROUTE
  }

  data object ChooseLibrary : Destination {
    const val ROUTE = "choose-library"

    override val route = ROUTE
  }

  data class BookDetails(val bookId: String) : Destination {
    override val route = "$PREFIX/${encodeArg(bookId)}"

    companion object {
      const val PREFIX = "book"
      const val ARG_BOOK_ID = "bookId"
      const val ROUTE_PATTERN = "$PREFIX/{$ARG_BOOK_ID}"
    }
  }

  data class CollectionDetails(val collectionId: String) : Destination {
    override val route = "$PREFIX/${encodeArg(collectionId)}"

    companion object {
      const val PREFIX = "collection"
      const val ARG_COLLECTION_ID = "collectionId"
      const val ROUTE_PATTERN = "$PREFIX/{$ARG_COLLECTION_ID}"
    }
  }

  data class FacetBooks(val kind: FacetKind, val value: String) : Destination {
    override val route = "$PREFIX/${kind.name}/${encodeArg(value)}"

    companion object {
      const val PREFIX = "facet"
      const val ARG_KIND = "kind"
      const val ARG_VALUE = "value"
      const val ROUTE_PATTERN = "$PREFIX/{$ARG_KIND}/{$ARG_VALUE}"
    }
  }

  companion object {
    /**
     * The four destinations reachable from the bottom navigation bar, in bar order.
     *
     * These are the *top-level* destinations: navigating to one clears the back stack to the start
     * destination, which is what `clearBackStack()` did by hand in the old [Navigator].
     */
    val topLevel = listOf(Home, Library, Collections, Settings)
  }
}

/**
 * Percent-encodes one route argument.
 *
 * `URLEncoder` is deliberately **not** used: it is form encoding, so it turns a space into `+`,
 * which a path segment renders literally — a book titled "The Hobbit" would show as "The+Hobbit"
 * in the toolbar of any screen that read the argument back. This encodes exactly the characters
 * that break path matching and leaves the rest, including spaces and Unicode, intact.
 */
fun encodeArg(raw: String): String =
  buildString {
    for (char in raw) {
      when (char) {
        '/' -> append("%2F")
        '?' -> append("%3F")
        '#' -> append("%23")
        '%' -> append("%25")
        else -> append(char)
      }
    }
  }

/** Reverses [encodeArg]. */
fun decodeArg(encoded: String): String =
  encoded
    .replace("%2F", "/")
    .replace("%3F", "?")
    .replace("%23", "#")
    // `%25` last: decoding it first would let a literal "%252F" decode twice into "/".
    .replace("%25", "%")
