package io.github.mattpvaughn.chronicle.features.player

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.EXTRA_IS_DOWNLOADED
import io.github.mattpvaughn.chronicle.data.sources.plex.EXTRA_PLAY_COMPLETION_STATE
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.STATUS_NOT_PLAYED
import io.github.mattpvaughn.chronicle.data.sources.plex.STATUS_PARTIALLY_PLAYED
import java.io.File

/**
 * How an [Audiobook] is presented to the **media framework** — the session's metadata, and the
 * browse tree Android Auto renders.
 *
 * These lived on `Audiobook` itself and were its only reason to import `MediaBrowserCompat`,
 * `MediaDescriptionCompat`, `Bundle` and `MediaMetadataCompat` (cu-176). Two reasons they moved:
 *
 *  1. **They are a presentation concern of this package, not a property of a book.** `toMediaItem`
 *     exists so `MediaPlayerService.onLoadChildren` has something to hand the car; nothing about a
 *     book requires knowing what a `MediaBrowserCompat.MediaItem` is. `toMediaItem`'s only caller
 *     is that service, and `toAlbumMediaMetadata`'s only caller is `PlexMediaRepository`.
 *  2. **`data/model` is otherwise framework-free**, and that is worth protecting: the 86 files in
 *     the codebase with no `android.*` imports sit at 80.8% coverage against 35.7% for everything
 *     else, because they can be tested without a framework at all.
 *
 * `AudiobookMediaItemTest` covers both and moved with them.
 */
fun Audiobook.toAlbumMediaMetadata(): MediaMetadataCompat {
  val metadataBuilder = MediaMetadataCompat.Builder()
  metadataBuilder.id = this.id
  metadataBuilder.title = this.title
  metadataBuilder.displayTitle = this.title
  metadataBuilder.albumArtUri = this.thumb
  metadataBuilder.album = this.title
  metadataBuilder.artist = this.author
  metadataBuilder.genre = this.genre
  return metadataBuilder.build()
}

/**
 * Converts an audiobook to a [MediaBrowserCompat.MediaItem] for use in
 * [androidx.media.MediaBrowserServiceCompat.onSearch] and
 * [androidx.media.MediaBrowserServiceCompat.onLoadChildren], and respective clients.
 */
fun Audiobook.toMediaItem(plexConfig: PlexConfig): MediaBrowserCompat.MediaItem {
  val mediaDescription = MediaDescriptionCompat.Builder()
  mediaDescription.setTitle(title)
  mediaDescription.setMediaId(id)
  mediaDescription.setSubtitle(author)
  mediaDescription.setIconUri(plexConfig.makeThumbUri(this.thumb))
  val extras = Bundle()
  extras.putBoolean(EXTRA_IS_DOWNLOADED, isCached)
  extras.putInt(
    EXTRA_PLAY_COMPLETION_STATE,
    if (progress == 0L) {
      STATUS_NOT_PLAYED
    } else {
      STATUS_PARTIALLY_PLAYED
    },
  )
  mediaDescription.setExtras(extras)

  return MediaBrowserCompat.MediaItem(mediaDescription.build(), FLAG_PLAYABLE)
}

/** Converts the metadata of a [MediaItemTrack] to a [MediaMetadataCompat]. */
fun MediaItemTrack.toMediaMetadata(
  plexConfig: PlexConfig,
  cachedMediaDir: File,
): MediaMetadataCompat {
  val metadataBuilder = MediaMetadataCompat.Builder()
  metadataBuilder.id = this.id
  metadataBuilder.title = this.title
  metadataBuilder.displayTitle = this.album
  metadataBuilder.displaySubtitle = this.artist
  metadataBuilder.trackNumber = this.playQueueItemID
  metadataBuilder.mediaUri = getTrackSource(cachedMediaDir, plexConfig)
  metadataBuilder.albumArtUri = plexConfig.makeThumbUri(this.thumb ?: "").toString()
  metadataBuilder.trackNumber = this.index.toLong()
  metadataBuilder.duration = this.duration
  metadataBuilder.album = this.album
  metadataBuilder.artist = this.artist
  metadataBuilder.genre = this.genre
  return metadataBuilder.build()
}
