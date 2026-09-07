package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * The colour a missing cover is drawn in.
 *
 * The same literal as `@color/imagePlaceholderColor`, which `book_cover_missing_placeholder`
 * filled — kept identical so the Compose screens and a notification's artwork do not disagree
 * about what "no cover" looks like. Duplicated rather than read through `colorResource` for the
 * reason `ChronicleColors` gives: a `@Preview` and a Compose test render with no Android theme.
 */
private val PlaceholderColor = Color(0x33B998CC)

/**
 * Book artwork, with a placeholder for every way it can be absent (cu-207).
 *
 * ### Why this exists
 *
 * The Compose migration replaced `bindImageRounded` — which set
 * `placeholder(R.drawable.book_cover_missing_placeholder)` **and** the matching `error(...)` — with
 * six separate bare `AsyncImage` calls that set neither. So a cover that failed to load, or had no
 * `thumb` at all, rendered as a hole in the layout: not a missing image, just nothing. Every screen
 * had drifted the same way, which is the argument for one composable rather than six call sites.
 *
 * ### The three absences it covers
 *
 * 1. **Offline** — [serverConnected] false. The model is null so Coil never requests an
 *    unreachable host, and the placeholder stands in.
 * 2. **No artwork** — an empty [thumb]. Some books genuinely have none, and building a url from an
 *    empty path asks the server for `/`.
 * 3. **A failed or undecodable response.** The one that hid this bug for as long as it existed:
 *    the mock server answered thumb paths with an **empty 200**, and Coil's silence for an
 *    undecodable body is indistinguishable from not having been asked.
 *
 * ### The placeholder is drawn, not loaded
 *
 * `book_cover_missing_placeholder` is a `<shape>` drawable, and `painterResource` **throws** for
 * one — *"Only VectorDrawables and rasterized asset types are supported"*. It is a crash, not a
 * fallback, and it happens on the first frame that renders a coverless book. A `Box` with the same
 * colour behind the image is the Compose-native equivalent and cannot fail: the image simply draws
 * over it when it arrives.
 *
 * That crash is also the reason this file's tests assert on *source text* rather than on rendering
 * — `AsyncImage` is asynchronous and Robolectric has no network, so a rendering test passed while
 * the device crashed on the first frame. **Only a device run catches this class of bug.**
 */
@Composable
fun CoverImage(
  thumb: String,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
  modifier: Modifier = Modifier,
  // The title beside a cover is the accessible label; describing the image too makes TalkBack read
  // every item twice (cu-47). A caller with no such label passes one.
  contentDescription: String? = null,
  contentScale: ContentScale = ContentScale.Crop,
) {
  Box(modifier = modifier.background(PlaceholderColor)) {
    AsyncImage(
      model = coverModel(thumb, serverConnected, coverUrl),
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/**
 * The url to request, or null when there is nothing to ask for.
 *
 * Pulled out of the composable so the decision can be tested without a composition — `AsyncImage`
 * is asynchronous and Robolectric has no network, so asserting on what it *renders* cannot
 * distinguish "asked for the right thing" from "asked for nothing".
 *
 * Null is not a failure: it is the offline and no-artwork cases, and it is what stops Coil
 * retrying an unreachable host. [CoverImage] shows its placeholder ground for those.
 */
internal fun coverModel(
  thumb: String,
  serverConnected: Boolean,
  coverUrl: (String) -> String,
): String? = if (serverConnected && thumb.isNotEmpty()) coverUrl(thumb) else null
