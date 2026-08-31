package io.github.mattpvaughn.chronicle.data.model

import android.support.v4.media.MediaMetadataCompat
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack.Companion.EMPTY_TRACK
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.player.*
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * A model for an audio track (i.e. a song)
 */
@Entity
data class MediaItemTrack(
  @PrimaryKey
  val id: String = TRACK_NOT_FOUND,
  val parentKey: String = "-1",
  val title: String = "",
  val playQueueItemID: Long = -1,
  val thumb: String? = null,
  val index: Int = 0,
  val discNumber: Int = 1,
  /** The duration of the track in milliseconds */
  val duration: Long = 0L,
  /** Path to the media file in the form "/library/parts/[id]/SOME_NUMBER/file.mp3" */
  val media: String = "",
  val album: String = "",
  val artist: String = "",
  val genre: String = "",
  val cached: Boolean = false,
  val artwork: String? = "",
  val viewCount: Long = 0L,
  val progress: Long = 0L,
  val lastViewedAt: Long = 0L,
  val updatedAt: Long = 0L,
  val size: Long = 0L,
) : Comparable<MediaItemTrack> {
  companion object {
    fun from(metadata: MediaMetadataCompat): MediaItemTrack {
      return MediaItemTrack(
        id = metadata.id ?: "-1",
        title = metadata.title ?: "",
        playQueueItemID = metadata.trackNumber,
        thumb = metadata.artUri.toString(),
        media = metadata.mediaUri.toString(),
        index = metadata.trackNumber.toInt(),
        duration = metadata.duration,
        album = metadata.album ?: "",
        artist = metadata.artist ?: "",
        genre = metadata.genre ?: "",
        artwork = metadata.artUri.toString(),
      )
    }

    val EMPTY_TRACK = MediaItemTrack(TRACK_NOT_FOUND)

    /**
     * A downloaded track on the file system: `<trackId>.<extension>`, nothing else.
     *
     * Was `\d*\..+`, where `\d*` matches **zero** digits — so `.nomedia` and `.DS_Store`
     * matched and [getTrackIdFromFileName] then threw on `"".toInt()`. `.+` also accepted a
     * second dot, so `3001.mp3.part` read as a finished track. The scan runs over a
     * user-writable directory that MoveSyncLocationWorker shuffles files through, so stray
     * names are ordinary (cu-76).
     */
    val cachedFilePattern = Regex("""\d+\.[^.]+""")

    fun getTrackIdFromFileName(fileName: String): String = fileName.substringBefore('.')

    /**
     * Merges updated local fields with a network copy of the book. Prefers network metadata,
     * but retains the following local fields if the local copy is more up to date:
     * [lastViewedAt], [progress]
     *
     * Always retains [cached] field from local copy
     */
    fun merge(
      network: MediaItemTrack,
      local: MediaItemTrack,
      forceUseNetwork: Boolean = false,
    ) = if (forceUseNetwork || network.lastViewedAt > local.lastViewedAt) {
      Timber.i("Integrating network track: $network")
      network.copy(cached = local.cached)
    } else {
      network.copy(
        cached = local.cached,
        lastViewedAt = local.lastViewedAt,
        progress = local.progress,
      )
    }

    /** Create a [MediaItemTrack] from a Plex model and an index */
    fun fromPlexModel(networkTrack: PlexDirectory): MediaItemTrack {
      return MediaItemTrack(
        id = networkTrack.ratingKey,
        parentKey = networkTrack.parentRatingKey.toString(),
        title = networkTrack.title,
        artist = networkTrack.grandparentTitle,
        thumb = networkTrack.thumb,
        index = networkTrack.index,
        discNumber = networkTrack.parentIndex,
        duration = networkTrack.duration,
        progress = networkTrack.viewOffset,
        media = networkTrack.media[0].part[0].key,
        album = networkTrack.parentTitle,
        // Plex reports seconds; the local DB stores millis (cu-14).
        lastViewedAt = plexTimestampToMillis(networkTrack.lastViewedAt),
        updatedAt = networkTrack.updatedAt,
        size = networkTrack.media[0].part[0].size,
      )
    }

    const val PARENT_KEY_PREFIX = "/library/metadata/"
  }

  /** The name of the track when it is written to the file system */
  fun getCachedFileName(): String {
    return "$id.${File(media).extension}"
  }

  fun getTrackSource(): String {
    return if (cached) {
      File(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName()).absolutePath
    } else {
      Injector.get().plexConfig().toServerString(media)
    }
  }

  /** A string representing the index but padded to [length] characters with zeroes */
  fun paddedIndex(length: Int): String {
    return index.toString().padStart(length, '0')
  }

  override fun compareTo(other: MediaItemTrack): Int {
    val discCompare = discNumber.compareTo(other.discNumber)
    if (discCompare != 0) {
      return discCompare
    }
    return index.compareTo(other.index)
  }
}

/**
 * Returns the timestamp (in ms) corresponding to the start of [track] with respect to the
 * entire playlist
 *
 * IMPORTANT: [MediaItemTrack.duration] is not guaranteed to perfectly match the duration of the
 * track represented, as we don't trust the server and are unable to verify this ourselves, so
 * use with caution
 */
fun List<MediaItemTrack>.getTrackStartTime(track: MediaItemTrack): Long {
  if (isEmpty()) {
    return 0
  }
  // There's a possibility [track] has been edited and [this] has not, so find it again
  val trackInList = find { it.id == track.id } ?: return 0
  val previousTracks = this.subList(0, indexOf(trackInList))
  return previousTracks.map { it.duration }.sum()
}

/**
 * Returns the timestamp (in ms) corresponding to the progress of [track] with respect to the
 * entire playlist
 */
fun List<MediaItemTrack>.getTrackProgressInAudiobook(track: MediaItemTrack): Long {
  if (isEmpty()) {
    return 0
  }
  val previousTracks = this.subList(0, indexOf(track))
  return previousTracks.map { it.duration }.sum() + track.progress
}

/** Returns the track containing the timestamp (as offset from the start of the [List] provided */
fun List<MediaItemTrack>?.getTrackContainingOffset(offset: Long): MediaItemTrack {
  if (isNullOrEmpty()) {
    return EMPTY_TRACK
  }
  this.fold(offset) { acc: Long, track: MediaItemTrack ->
    val tempAcc: Long = acc - track.duration
    if (tempAcc <= 0) {
      return track
    }
    return@fold tempAcc
  }
  return EMPTY_TRACK
}

/**
 * @return the progress of the current track plus the duration of all previous tracks
 */
fun List<MediaItemTrack>.getProgress(): Long {
  if (isEmpty()) {
    return 0
  }
  val currentTrackProgress = getActiveTrack().progress
  val previousTracksDuration = getTrackStartTime(getActiveTrack())
  return currentTrackProgress + previousTracksDuration
}

/**
 * @return progress as percent
 */
fun List<MediaItemTrack>.getProgressPercentage(): Int {
  if (isEmpty() || getDuration() == 0L) {
    return 0
  }
  return ((getProgress() / getDuration().toDouble()) * 100).roundToInt()
}

/**
 * Find the next song in the [List] which has not been completed. Returns the first element
 */
fun List<MediaItemTrack>.getActiveTrack(): MediaItemTrack {
  check(this.isNotEmpty()) { "Cannot get active track of empty list!" }
  return maxByOrNull { it.lastViewedAt } ?: get(0)
}

/** Converts the metadata of a [MediaItemTrack] to a [MediaMetadataCompat]. */
fun MediaItemTrack.toMediaMetadata(plexConfig: PlexConfig): MediaMetadataCompat {
  val metadataBuilder = MediaMetadataCompat.Builder()
  metadataBuilder.id = this.id
  metadataBuilder.title = this.title
  metadataBuilder.displayTitle = this.album
  metadataBuilder.displaySubtitle = this.artist
  metadataBuilder.trackNumber = this.playQueueItemID
  metadataBuilder.mediaUri = getTrackSource()
  metadataBuilder.albumArtUri = plexConfig.makeThumbUri(this.thumb ?: "").toString()
  metadataBuilder.trackNumber = this.index.toLong()
  metadataBuilder.duration = this.duration
  metadataBuilder.album = this.album
  metadataBuilder.artist = this.artist
  metadataBuilder.genre = this.genre
  return metadataBuilder.build()
}

fun List<MediaItemTrack>.asChapterList(): List<Chapter> {
  val outList = mutableListOf<Chapter>()
  var cumStartOffset = 0L
  for (track in this) {
    track.asChapter(cumStartOffset)
    cumStartOffset += track.duration
  }
  return outList
}

fun MediaItemTrack.asChapter(startOffset: Long): Chapter {
  return Chapter(
    title = title,
    id = id,
    index = index.toLong(),
    discNumber = discNumber,
    startTimeOffset = startOffset,
    endTimeOffset = duration,
    downloaded = cached,
    trackId = id,
  )
}

val EMPTY_TRACK = MediaItemTrack(id = TRACK_NOT_FOUND)

/**
 * Converts a Plex `lastViewedAt` to the millisecond epoch the local database uses.
 *
 * Plex reports Unix **seconds**; `ProgressUpdater` writes `System.currentTimeMillis()`. Both
 * `MediaItemTrack.merge` and `Audiobook.merge` decide which side is newer with
 * `network.lastViewedAt > local.lastViewedAt`, so mixing the units meant the server value was
 * ~1000x smaller and could never win — a position set on a second device was silently
 * discarded on every refresh (cu-14).
 *
 * Values already large enough to be milliseconds are passed through unchanged: converting
 * twice would push the timestamp tens of thousands of years out and make the server always
 * win, which is the same bug with the sign flipped.
 *
 * Zero means "never viewed" and stays zero rather than becoming a real 1970 ordering.
 */
fun plexTimestampToMillis(plexLastViewedAt: Long): Long =
  when {
    plexLastViewedAt <= 0L -> 0L
    plexLastViewedAt >= SECONDS_MILLIS_THRESHOLD -> plexLastViewedAt
    else -> plexLastViewedAt * 1_000L
  }

/**
 * Above this, a value cannot plausibly be Unix seconds — 10^11 seconds is the year 5138, while
 * 10^11 millis is 1973. Anything larger is therefore already milliseconds.
 */
private const val SECONDS_MILLIS_THRESHOLD = 100_000_000_000L

/**
 * Whether [file] holds a *complete* download of a track whose expected size is [expectedSize].
 *
 * Exists because the cached-file scan marked every file matching `<id>.<ext>` as downloaded
 * with no size check, while [MediaItemTrack.size] — populated from Plex and persisted in Room
 * — was read nowhere. A Wi-Fi drop mid-download therefore left a partial file that the next
 * launch promoted to "available offline", and the book played truncated (cu-76).
 *
 * A size mismatch in *either* direction is rejected: a longer file means the metadata and the
 * bytes disagree, and trusting it would hide whichever is wrong.
 *
 * @param expectedSize 0 when Plex reported no size. The check then falls back to "non-empty",
 *   which preserves the old behaviour for those tracks rather than making them permanently
 *   un-cacheable — an empty file is still rejected.
 */
fun isCompleteDownload(
  file: File,
  expectedSize: Long,
): Boolean {
  if (!file.exists()) return false
  val actual = file.length()
  return if (expectedSize > 0L) actual == expectedSize else actual > 0L
}
