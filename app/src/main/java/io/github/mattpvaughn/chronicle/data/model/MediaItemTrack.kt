package io.github.mattpvaughn.chronicle.data.model

import android.net.Uri
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
        // Plex's per-track `thumb`. For audiobooks this usually points at the *album's* art
        // (the fixture's track thumb is `/library/metadata/1001/thumb/...`, the book's
        // ratingKey), which is what the player wants. Where a server does give a track its own
        // art, the player would show chapter art instead of the cover — issue #119. That case
        // cannot be reproduced from the fixture pack, so it is a live-server check in cu-73
        // rather than a speculative `parentThumb` field here.
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

    /**
     * The `file://` URI for a downloaded track, as a string.
     *
     * Must carry the scheme. This used to be `File(...).absolutePath`, a bare path, and
     * `MediaMetadataCompat.mediaUri` parses whatever it is given with `toUri()` — which yields
     * `scheme = null`. ExoPlayer's `DefaultDataSource` then does not resolve it as a local file,
     * and the user gets an unsupported-format error on **downloaded books only** (cu-83). The
     * server branch was never affected, because `toServerString` produces an `https://` URL.
     *
     * `Uri.fromFile` rather than `"file://" + path`: it percent-encodes spaces and non-ASCII
     * characters, which appear in real sync directories on removable storage.
     */
    fun cachedTrackUri(
      cachedMediaDir: File,
      cachedFileName: String,
    ): String = Uri.fromFile(File(cachedMediaDir, cachedFileName)).toString()
  }

  /** The name of the track when it is written to the file system */
  fun getCachedFileName(): String {
    return "$id.${File(media).extension}"
  }

  fun getTrackSource(): String {
    return if (cached) {
      cachedTrackUri(Injector.get().prefsRepo().cachedMediaDir, getCachedFileName())
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
 * The track the book is currently at: the **furthest started** one, in playback order.
 *
 * This used to be `maxByOrNull { it.lastViewedAt }` — the most recently *touched* track — which
 * makes book position non-monotonic across devices. With device A listening in track 3 and device B
 * in track 7, the reported position jumped between two unrelated points depending on which
 * `lastViewedAt` was larger, and a second device opening an earlier chapter dragged the position
 * backwards. That is the "wildly different positions across devices" report; decision-16 replaced
 * the rule.
 *
 * A track counts if it has a non-zero offset — see [hasProgress] for why a timestamp or a view
 * count must not qualify. The result is the *last* such track in playback order, so an earlier track
 * that was recently opened does not pull the position backwards.
 *
 * Falls back to the first track in playback order for an untouched book. Ordering comes from
 * [MediaItemTrack.compareTo] (disc, then index), never from the list's own order, which arrives
 * from the database and from the network in no guaranteed sequence.
 */
fun List<MediaItemTrack>.getActiveTrack(): MediaItemTrack {
  check(this.isNotEmpty()) { "Cannot get active track of empty list!" }
  val inPlaybackOrder = sorted()
  return inPlaybackOrder.lastOrNull { it.hasProgress() } ?: inPlaybackOrder.first()
}

/**
 * Whether the listener is *positioned* in this track — i.e. it has a real offset to resume from.
 *
 * Deliberately only `progress > 0`. Two rejected alternatives, both of which produce a wrong
 * position:
 *
 * - **`lastViewedAt > 0` as well.** `markTracksInBookAsWatched` sets *every* track in a book to
 *   `progress = 0, lastViewedAt = now`, so every track would count as started, [getActiveTrack]
 *   would return the **last** one, and a book just marked as read would report a position part way
 *   through it — 50% of the way, for a three-track book. That is the owner's "sometimes it brings to
 *   0%, sometimes at a different position".
 * - **A track's `viewCount`.** Same problem: marking a book played sets it on every track, so it
 *   says nothing about where the listener is.
 *
 * A track played to the end and reset to `progress = 0` is therefore *not* a position, which is
 * correct — the position is in whatever later track has a real offset, and if none does the book is
 * either finished (see [Audiobook.isCompleted]) or back at its start.
 */
private fun MediaItemTrack.hasProgress(): Boolean = progress > 0L

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

/**
 * One chapter per track, for a book with no embedded chapter data.
 *
 * Every consumer of chapters falls back to this when `Audiobook.chapters` is empty
 * (`CurrentlyPlayingSingleton`, `CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel`,
 * `MainActivityViewModel`). It used to build each chapter and **throw it away** — nothing was
 * ever added to the returned list — so such a book showed no chapters at all rather than one
 * per file (cu-13).
 *
 * Offsets are cumulative across the whole book, because that is the coordinate space
 * [getChapterAt] and the seek bar work in.
 */
fun List<MediaItemTrack>.asChapterList(): List<Chapter> {
  val outList = mutableListOf<Chapter>()
  var cumStartOffset = 0L
  for (track in this) {
    outList.add(track.asChapter(cumStartOffset))
    cumStartOffset += track.duration
  }
  return outList
}

/**
 * Represents this track as a single chapter starting at [startOffset].
 *
 * [Chapter.bookEndTimeOffset] is `startOffset + duration`, not `duration`: offsets are absolute
 * within the book, so using the raw duration made every chapter after the first report an end
 * earlier than its own start, and [getChapterAt] then matched nothing.
 */
fun MediaItemTrack.asChapter(startOffset: Long): Chapter {
  return Chapter(
    title = title,
    id = id,
    index = index.toLong(),
    discNumber = discNumber,
    bookStartTimeOffset = startOffset,
    bookEndTimeOffset = startOffset + duration,
    downloaded = cached,
    trackId = id,
    // parentKey is this track's book. Required because chapters share one table keyed partly
    // on bookId (cu-49); an unset one collides with every other chapter in the library.
    bookId = parentKey,
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
