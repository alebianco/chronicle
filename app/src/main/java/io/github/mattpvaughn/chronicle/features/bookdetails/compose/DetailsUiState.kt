package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig

/**
 * Everything the book-details header renders, as one value (cu-200).
 *
 * The Fragment collected 17 flows and made twelve independent `isVisible` decisions from them,
 * each anchored on its own boolean or enum comparison. Nothing stopped two being true at once —
 * the same shape cu-187 found rendering "empty" and "offline" together on the collections screen.
 */
data class DetailsUiState(
  val book: BookHeader = BookHeader(),
  val progress: ProgressLine = ProgressLine(),
  val download: DownloadState = DownloadState.Unknown,
  val playback: PlaybackState = PlaybackState(),
  val summary: SummaryState = SummaryState(),
  val connection: PlexConfig.ConnectionState = PlexConfig.ConnectionState.NOT_CONNECTED,
  val isLoadingTracks: Boolean = false,
)

/** Title, author and the metadata lines. Changes only when the book does. */
data class BookHeader(
  val title: String = "",
  val author: String = "",
  val thumb: String? = null,
  val narrator: String? = null,
  val series: String? = null,
  val serverConnected: Boolean = true,
)

/**
 * The progress readout.
 *
 * **Ported verbatim, not reworded.** [[cu-191]] records that this screen still renders the raw
 * `h:mm:ss/h:mm:ss` pair cu-19 removed from the player — and that the replacement wording is a
 * product choice the owner has not made ("a book you have not started may want its total length
 * shown plainly"). Rewording it inside a rendering migration would turn a mechanical change into
 * an unreviewed product decision, so the string arrives already formatted and cu-191 stays open.
 */
data class ProgressLine(
  val text: String = "",
  val percentage: String = "",
)

/**
 * The download control, as one exhaustive state (cu-200).
 *
 * Was **four** flows — `cacheStatus`, `cacheIconDrawable`, `cacheContentDescription` and
 * `cacheIconTint` — each a `map` over the same source and each carrying its own `null ->` branch
 * meaning "not resolved yet". A sealed type makes the icon and its spoken label impossible to
 * disagree, which is what `CacheLabelPairingTest` was checking by parsing `when` branches out of
 * the ViewModel's source text. The compiler does it better.
 */
sealed interface DownloadState {
  /** Status not resolved yet. The control is visible but inert — pressing it does nothing. */
  data object Unknown : DownloadState

  data object NotCached : DownloadState

  /** In flight. A spinner shows *over* the icon, which keeps its slot rather than going away. */
  data object Caching : DownloadState

  data object Cached : DownloadState
}

/** Play/pause and the buffering spinner. */
data class PlaybackState(
  val isPlaying: Boolean = false,
  val isAudioLoading: Boolean = false,
  @StringRes val watchedIcon: Int = 0,
  val isForceSyncing: Boolean = false,
)

/** The collapsible summary. */
data class SummaryState(
  val text: String = "",
  val isShown: Boolean = false,
  val isExpanded: Boolean = false,
  val linesShown: Int = 0,
)
