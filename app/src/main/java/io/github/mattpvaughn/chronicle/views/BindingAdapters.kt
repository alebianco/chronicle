package io.github.mattpvaughn.chronicle.views

import android.app.Activity
import android.net.Uri
import android.widget.ImageView
import androidx.core.net.toUri
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import io.github.mattpvaughn.chronicle.R

/**
 * Builds the server-side URL for a cover, given the artwork's `src` path.
 *
 * Passed in rather than resolved here (cu-33): this function is called from a `RecyclerView`
 * binder, so it used to reach `Injector.get().plexConfig()` **on every bind** — in a hot render
 * path the whole of cu-110 was about. A function rather than the `PlexConfig` itself keeps the
 * adapters that call it from knowing there is a Plex.
 */
typealias CoverUrlBuilder = (String) -> String

/**
 * Loads a book cover into [imageView] via Plex's photo transcoder.
 *
 * The cache key is deliberately the URL *query* rather than the whole URL: the
 * same artwork is reachable over LAN, WAN or relay at different hostnames, and
 * keying on the full URL would re-download every cover whenever the connection
 * route changed. This mirrors the `UrlQueryCacheKey` behaviour the previous
 * Fresco implementation configured through its cache-key factory.
 */
fun bindImageRounded(
  imageView: ImageView,
  src: String?,
  serverConnected: Boolean,
  coverUrl: CoverUrlBuilder,
) {
  val activity = imageView.context as? Activity
  if (activity?.isDestroyed == true) {
    return
  }

  // Skip a load that would produce the image already showing.
  //
  // A list row rebinds whenever DiffUtil reports its contents changed, and
  // `AudiobookAdapter.areContentsTheSame` includes `progress` — which moves every second during
  // playback. So a shelf of unchanged covers re-entered this function once per second per visible
  // row, and each call built a URL, asked Coil for a fresh load and started a `crossfade`
  // animation. The animation is the expensive part: it invalidates continuously, and the profile
  // showed 44 calls in 16 s driving 1285 `View.measure` passes (cu-110).
  //
  // Keyed on the *source* string rather than the built URL, so a connection-route change (LAN ->
  // relay, which rewrites the host) does not count as a different image — the same reasoning as
  // the query-only cache key below.
  val previousSrc = imageView.getTag(R.id.tag_bound_image_src) as? String
  if (previousSrc != null && previousSrc == src && imageView.drawable != null) {
    return
  }
  imageView.setTag(R.id.tag_bound_image_src, src)

  val imageSize =
    imageView.resources.getDimension(R.dimen.currently_playing_artwork_max_size).toInt()
  val url: Uri =
    coverUrl("photo/:/transcode?width=$imageSize&height=$imageSize&url=$src").toUri()

  imageView.load(url) {
    memoryCacheKey(url.query ?: url.toString())
    placeholder(R.drawable.book_cover_missing_placeholder)
    error(R.drawable.book_cover_missing_placeholder)
    crossfade(true)
  }
}
