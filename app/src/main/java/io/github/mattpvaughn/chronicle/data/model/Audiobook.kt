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
import io.github.mattpvaughn.chronicle.data.sources.plex.model.narrators
import io.github.mattpvaughn.chronicle.data.sources.plex.model.seriesName
import io.github.mattpvaughn.chronicle.features.player.*
import kotlin.time.Duration.Companion.minutes

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
  /**
   * This book's own playback speed, or [NO_SPEED_OVERRIDE] to follow the global preference.
   *
   * A sentinel rather than a nullable column: every other local-only field on this entity is
   * non-null, and `0f` sits outside the valid speed range, so it cannot be confused with a real
   * speed. Read it through [effectiveSpeed] — the only place the sentinel is interpreted.
   */
  val playbackSpeed: Float = NO_SPEED_OVERRIDE,
  /**
   * The narrator(s), by the Audnexus `Style` convention (cu-24).
   *
   * Comma-separated when a recording has several, because this is a display and grouping value
   * rather than a relation — a book with two narrators appears under both in the facet list, which
   * `BookFacets` splits on the way through.
   *
   * Empty means **not known**, not "has none": `Style` is only on the per-book detail response, so
   * a book that has never been synced has nothing here regardless of how it is tagged.
   */
  val narrator: String = "",
  /** The series, by the `Mood` convention, with any `Series:` prefix already stripped (cu-24). */
  val series: String = "",
  /**
   * This book's place in [series], or 0 when unknown.
   *
   * Plex's `index` is the album's track-ordering index and is 1 for most audiobooks, so it is
   * **not** usable as a series position. The Audnexus convention puts the position in `titleSort`
   * ("Mistborn, Book 2"), which is what this is parsed from.
   */
  val seriesIndex: Int = 0,
) {
  /**
   * The speed this book should play at: its own override when it has one, otherwise [globalSpeed].
   */
  fun effectiveSpeed(globalSpeed: Float): Float = if (hasSpeedOverride) playbackSpeed else globalSpeed

  /** Whether this book overrides the global speed preference. */
  val hasSpeedOverride: Boolean
    get() = playbackSpeed >= MIN_VALID_SPEED

  companion object {
    /**
     * [playbackSpeed] value meaning "no override — use the global preference".
     *
     * Zero is not a playable speed, so it is unambiguous as a sentinel.
     */
    const val NO_SPEED_OVERRIDE = 0f

    /**
     * The smallest value [playbackSpeed] may hold and still mean a speed.
     *
     * Kept here rather than referencing `CurrentlyPlayingViewModel.PLAYBACK_SPEED_MIN` so the data
     * layer does not depend on a feature package. `PerBookSpeedTest` pins the two equal.
     */
    const val MIN_VALID_SPEED = 0.5f

    /**
     * [seriesIndex] value meaning "no position known".
     *
     * Zero is not a series position — books are numbered from one — so it is unambiguous. Callers
     * sort it **last**: an unnumbered extra belongs at the end of a series, not before book one.
     */
    const val NO_SERIES_INDEX = 0

    /**
     * The unit [seriesIndex] is stored in: hundredths of a book (cu-146).
     *
     * So book 2 is `200` and the novella at 1.5 is `150`. Hundredths rather than whole numbers
     * because a fractional position is real — Audnexus writes `Book 1.5` — and rather than a
     * `Float` because [NO_SERIES_INDEX] is compared for equality, which floats do not do reliably,
     * and because the Room column stays `INTEGER` either way.
     */
    const val SERIES_INDEX_SCALE = 100

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
        // Audnexus tagging convention (cu-24). Both are empty on a library *listing* — Plex only
        // sends `Style`/`Mood` on the per-book detail response — so these fill in when a book is
        // synced, and `merge` below is what stops a later refresh from blanking them again.
        narrator = dir.narrators().joinToString(separator = ", "),
        series = dir.seriesName(),
        seriesIndex = seriesIndexFromTitleSort(dir.titleSort),
      )

    /**
     * The book's position in its series, in hundredths, or 0 when none can be read.
     *
     * **There is no numeric series field in Plex.** Album `index` is 1 on essentially every
     * audiobook (it is the album-ordering index), `parentIndex` is a *track's* disc number, and the
     * `Mood` tag carries the series name without a number. So this string, written by whichever
     * tagger the user ran, is the only carrier — and it is on the *listing* as well as the detail
     * response, unlike `Style`/`Mood` (cu-24), so it costs no extra request.
     *
     * The rules themselves live in `SeriesIndexPatterns.kt` as **data**, so a library tagged by
     * some other convention can be handled by adding a pattern rather than shipping a new build
     * (cu-147, modelled on tvnamer). This function is the thin adapter: it asks the configured set
     * and converts the answer to the stored unit.
     *
     * Values are **hundredths** ([SERIES_INDEX_SCALE]) because a novella genuinely sits at 1.5 —
     * Audnexus' own volume regex admits `1.5`, `1-3` and `4+` — and an `Int` truncating that to 1
     * would collide with book one. Hundredths keep the value exact while remaining an integer
     * compare, which matters because **0 is the "unknown" sentinel** and float equality against a
     * sentinel is not reliable. The Room column stays `INTEGER`, so this is a unit rather than a
     * type change.
     *
     * Returns 0 when nothing can be read, which callers sort **last** — an unnumbered extra belongs
     * at the end of a series, not in front of book one (see [inSeriesOrder]).
     */
    internal fun seriesIndexFromTitleSort(titleSort: String): Int = seriesIndexPatterns.match(titleSort)?.storedIndex ?: NO_SERIES_INDEX

    /**
     * The rule set in force, compiled once.
     *
     * A `var` with a private setter so a user-configured set can replace it at startup
     * ([installSeriesIndexPatterns]) without every caller having to thread it through.
     * `Audiobook.from` runs per book on a library refresh, so recompiling per call would rebuild
     * several thousand expressions on a 1000-book library (the cu-51 target).
     */
    var seriesIndexPatterns: SeriesIndexPatternSet = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS)
      private set

    /**
     * Replaces the rule set, keeping the built-ins as a fallback tier unless told otherwise.
     *
     * [PatternOrder.BEFORE] by default, because first-match-wins is the whole disambiguation
     * mechanism: a user adding a rule for their own convention must be able to pre-empt a built-in
     * that reads their titles wrongly, while a pattern that matches nothing then degrades to
     * today's behaviour rather than emptying the index.
     */
    fun installSeriesIndexPatterns(
      userPatterns: List<SeriesIndexPattern>,
      order: PatternOrder = PatternOrder.BEFORE,
    ) {
      seriesIndexPatterns = SeriesIndexPatternSet.of(userPatterns, order)
    }

    /** Restores the built-in rule set. Exists so a test cannot leak a pattern set into the next. */
    fun resetSeriesIndexPatterns() {
      seriesIndexPatterns = SeriesIndexPatternSet(DEFAULT_SERIES_INDEX_PATTERNS)
    }

    /**
     * Merges updated local fields with a network copy of the book. Respects network metadata
     * as the authoritative source of truth with the follow exceptions:
     *
     * Retains the following local fields only if the local copy is more recent: [lastViewedAt].
     * This is because even if the network copy is more up to date, retaining the most recent
     * [lastViewedAt] from the local copy is preferred.
     *
     * Always retain fields from local copy: [duration], [isCached], [favorited], [chapters],
     * [source], [playbackSpeed]. [playbackSpeed] is a local-only override the server knows nothing
     * about, so `network.playbackSpeed` is always the [NO_SPEED_OVERRIDE] default — adopting it
     * would silently drop the user's per-book speed on every library refresh.
     *
     * [narrator], [series] and [seriesIndex] are different again: the server *can* supply them, but
     * only on the per-book detail response (cu-24). A library refresh merges from the **listing**,
     * where they are always absent — so they are taken from the network copy when it has a value
     * and kept from the local one when it does not. Preferring the network unconditionally would
     * blank a narrator on every refresh; preferring the local one unconditionally would make a
     * re-tagged book impossible to correct. [chapters] and [duration] are retained because they can be calculated only when
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
          playbackSpeed = local.playbackSpeed,
          narrator = network.narrator.ifEmpty { local.narrator },
          series = network.series.ifEmpty { local.series },
          seriesIndex = if (network.seriesIndex != NO_SERIES_INDEX) network.seriesIndex else local.seriesIndex,
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
          playbackSpeed = local.playbackSpeed,
          narrator = network.narrator.ifEmpty { local.narrator },
          series = network.series.ifEmpty { local.series },
          seriesIndex = if (network.seriesIndex != NO_SERIES_INDEX) network.seriesIndex else local.seriesIndex,
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

/**
 * Whether the book has been finished.
 *
 * Completion is an **explicit fact, separate from position** (decision-16): a non-zero [viewCount]
 * means the user marked it played, and that is authoritative wherever the position happens to sit.
 * Otherwise a position within [BOOK_FINISHED_END_WINDOW] of the end counts, since nobody listens
 * through the closing credits.
 *
 * The first clause used to be `progress < 10.seconds`, which reported an **unstarted** book as
 * completed — that is the 0% case, not the finished one. It had no callers, so it was latent rather
 * than live, but it is the helper anyone would reach for when adding a finished state to the library
 * list.
 */
fun Audiobook.isCompleted(): Boolean {
  if (viewCount > 0L) {
    return true
  }
  // A book whose duration is not loaded yet has duration 0, which would make the window check
  // trivially true for any progress.
  if (duration <= 0L) {
    return false
  }
  return progress >= duration - BOOK_FINISHED_END_WINDOW
}

/** How close to the end counts as finished. */
val BOOK_FINISHED_END_WINDOW = 2.minutes.inWholeMilliseconds

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
