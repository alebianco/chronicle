package io.github.mattpvaughn.chronicle.features.player

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.DrawableRes
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.CONTENT_STYLE_BROWSABLE_HINT
import io.github.mattpvaughn.chronicle.data.sources.plex.CONTENT_STYLE_LIST_ITEM_HINT_VALUE
import io.github.mattpvaughn.chronicle.data.sources.plex.CONTENT_STYLE_SUPPORTED

/**
 * Create a basic browsable item for Auto.
 *
 * @param mediaId the category's **stable** identifier, which `onLoadChildren` matches on.
 * @param title the localized label shown to the user. Never the identifier: this used to default to
 *   the title, so the browse tree was keyed on translated text and every category stopped resolving
 *   after a language change (cu-99).
 */
fun makeBrowsable(
  mediaId: String,
  title: String,
  @DrawableRes iconRes: Int,
  desc: String = "",
): MediaItem {
  val mediaDescription = MediaDescriptionCompat.Builder()
  mediaDescription.setTitle(title)
  mediaDescription.setSubtitle(desc)
  mediaDescription.setIconUri(
    Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/$iconRes"),
  )
  mediaDescription.setMediaId(mediaId)
  val extras = Bundle()
  extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
  extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
  mediaDescription.setExtras(extras)
  return MediaItem(mediaDescription.build(), FLAG_BROWSABLE)
}
