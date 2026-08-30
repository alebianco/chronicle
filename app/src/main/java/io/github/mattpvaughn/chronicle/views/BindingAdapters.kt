package io.github.mattpvaughn.chronicle.views

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.Injector

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
) {
  val activity = imageView.context as? Activity
  if (activity?.isDestroyed == true) {
    return
  }

  val imageSize =
    imageView.resources.getDimension(R.dimen.currently_playing_artwork_max_size).toInt()
  val config = Injector.get().plexConfig()
  val url: Uri =
    config.toServerString("photo/:/transcode?width=$imageSize&height=$imageSize&url=$src")
      .toUri()

  imageView.load(url) {
    memoryCacheKey(url.query ?: url.toString())
    placeholder(R.drawable.book_cover_missing_placeholder)
    error(R.drawable.book_cover_missing_placeholder)
    crossfade(true)
  }
}

// NOTE: this will not work for Android versions HoneyComb and below, and DataBinding overrides the
// tag set on all outermost layouts in a data bound layout xml
@RequiresApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
fun bindTag(
  view: View,
  o: Any,
) {
  view.tag = o
}
