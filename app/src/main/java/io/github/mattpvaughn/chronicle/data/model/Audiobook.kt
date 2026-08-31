package io.github.mattpvaughn.chronicle.data.model

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.github.mattpvaughn.chronicle.data.sources.MediaSource.Companion.NO_SOURCE_FOUND
import io.github.mattpvaughn.chronicle.data.sources.SourceManager
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.features.player.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TypeConverters(ChapterListConverter::class)
@Entity
data class Audiobook(
  @PrimaryKey
  val id: String,
  /** Unique long representing a [MediaSource] in [SourceManager] */
  val source: Long,
  val title: String = "",
  val titleSort: String = "",
  val author: String = "",
  val thumb: String = "",
  val parentId: String = "-1",
  val genre: String = "",
  val summary: String = "",
  val year: Int = 0,
  val addedAt: Long = 0L,
  /** last Unix timestamp that some metadata was changed in server */
  val updatedAt: Long = 0L,
  /** last Unix timestamp that the book was listened to */
  val lastViewedAt: Long = 0L,
  /** duration of the entire audiobook in milliseconds */
  val duration: Long = 0L,
  /** Whether the book is cached by [ICachedFileManager]*/
  val isCached: Boolean = false,
  /** The current progress into the audiobook in milliseconds */
  val progress: Long = 0L,
  val favorited: Boolean = false,
  /** The number of time's individual tracks have been completed */
  val viewedLeafCount: Long = 0L,
  /** The number of tracks in the book */
  val leafCount: Long = 0L,
  /** The number of times the book has been listened to */
  val viewCount: Long = 0L,
  /** Chapter metadata corresponding to m4b chapter metadata in the m4b files */
  val chapters: List<Chapter> = emptyList(),
) {
  companion object {
    fun from(dir: PlexDirectory) =
      Audiobook(
        id = dir.ratingKey,
        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
        title = dir.title,
        titleSort = dir.titleSort.takeIf { it.isNotEmpty() } ?: dir.title,
        author = dir.parentTitle,
        thumb = dir.thumb,
        parentId = dir.parentRatingKey.toString(),
        // joinToString on the data class itself yields "PlexGenre(tag=Fantasy)";
        // this field reaches MediaMetadataCompat, so Android Auto and the media
        // notification would show that literal string (found via cu-16 fixtures).
        genre = dir.plexGenres.joinToString(separator = ", ") { it.tag },
        summary = dir.summary,
        year = dir.year.takeIf { it != 0 } ?: dir.parentYear,
        addedAt = dir.addedAt,
        updatedAt = dir.updatedAt,
        // Plex reports seconds; the local DB stores millis (cu-14).
        lastViewedAt = plexTimestampToMillis(dir.lastViewedAt),
        viewedLeafCount = dir.viewedLeafCount,
        leafCount = dir.leafCount,
        viewCount = dir.viewCount,
      )

    /**
     * Merges updated local fields with a network copy of the book. Respects network metadata
     * as the authoritative source of truth with the follow exceptions:
     *
     * Retains the following local fields only if the local copy is more recent: [lastViewedAt].
     * This is because even if the network copy is more up to date, retaining the most recent
     * [lastViewedAt] from the local copy is preferred.
     *
     * Always retain fields from local copy: [duration], [isCached], [favorited], [chapters],
     * [source]. [chapters] and [duration] are retained because they can be calculated only when
     * all child [MediaItemTrack]s are loaded; [duration], [source] and [isCached] because they are
     * local values that do not exist on the server.
     *
     * **[progress] is carried from the local copy and never from the network.** Plex stores no
     * position on an album — only per-track `viewOffset` — so `network.progress` carries no
     * information. The local value is the most recent derivation from the tracks (decision-16), and
     * keeping it matters because a library refresh merges *without* loading tracks: zeroing it here
     * would blank every book's progress in the library list. Recomputation happens where the tracks
     * are available, in `syncAudiobook`, which is the only writer of a fresh value.
     */
    fun merge(
      network: Audiobook,
      local: Audiobook,
      forceNetwork: Boolean = false,
    ): Audiobook {
      // progress always comes from the local copy, never the network. Plex stores no position on
      // an album, so `network.progress` is meaningless here — it is whatever `Audiobook.from`
      // defaulted. The local value is the last derivation from the tracks (decision-16); keeping it
      // means a library refresh, which merges without loading tracks, preserves what the user sees.
      return if (network.lastViewedAt > local.lastViewedAt || forceNetwork) {
        network.copy(
          progress = local.progress,
          duration = local.duration,
          isCached = local.isCached,
          favorited = local.favorited,
          chapters = local.chapters,
          source = local.source,
        )
      } else {
        network.copy(
          progress = local.progress,
          duration = local.duration,
          source = local.source,
          isCached = local.isCached,
          lastViewedAt = local.lastViewedAt,
          favorited = local.favorited,
          chapters = local.chapters,
        )
      }
    }

    const val SORT_KEY_TITLE = "title"
    const val SORT_KEY_AUTHOR = "author"
    const val SORT_KEY_GENRE = "title"
    const val SORT_KEY_RELEASE_DATE = "release_date"
    const val SORT_KEY_YEAR = "year"
    const val SORT_KEY_DURATION = "duration"
    const val SORT_KEY_RATING = "rating"
    const val SORT_KEY_CRITIC_RATING = "critic_rating"
    const val SORT_KEY_DATE_ADDED = "date_added"
    const val SORT_KEY_DATE_PLAYED = "date_played"
    const val SORT_KEY_PLAYS = "plays"

    val SORT_KEYS =
      listOf(
        SORT_KEY_TITLE,
        SORT_KEY_AUTHOR,
        SORT_KEY_GENRE,
        SORT_KEY_RELEASE_DATE,
        SORT_KEY_YEAR,
        SORT_KEY_RATING,
        SORT_KEY_CRITIC_RATING,
        SORT_KEY_DATE_ADDED,
        SORT_KEY_DATE_PLAYED,
        SORT_KEY_PLAYS,
        SORT_KEY_DURATION,
      )
  }
}

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
 * [androidx.media.MediaBrowserServiceCompat.onLoadChildren], and respective clients
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

fun Audiobook.isCompleted(): Boolean {
  return progress < 10.seconds.inWholeMilliseconds || progress > (duration - 2.minutes.inWholeMilliseconds)
}

/**
 * The id of "no book".
 *
 * The *textual* form of the old numeric sentinel, deliberately: a `Chapter.bookId` written before
 * the cu-71 retype migrates to the string "-22321", so changing this to "" would orphan every
 * chapter that had no book.
 */
const val NO_AUDIOBOOK_FOUND_ID = "-22321"
const val NO_AUDIOBOOK_FOUND_TITLE = "No audiobook found"
val EMPTY_AUDIOBOOK = Audiobook(NO_AUDIOBOOK_FOUND_ID, NO_SOURCE_FOUND, NO_AUDIOBOOK_FOUND_TITLE)
