package io.github.mattpvaughn.chronicle.features.bookdetails.compose

import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.data.model.BookProgressState
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig

/**
 * Everything the book-details header renders, as one value.
 *
 * The Fragment collected 17 flows and made twelve independent `isVisible` decisions from them,
 * each anchored on its own boolean or enum comparison. Nothing stopped two being true at once —
 * the same shape the collections screen's Compose migration found rendering "empty" and
 * "offline" together.
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
 * The progress readout, as **numbers rather than a rendered string**.
 *
 * It used to carry a pre-formatted `text`, ported verbatim through the Compose migration because
 * the screen showed the raw `h:mm:ss/h:mm:ss` pair §3.1 rule 3 bans and the replacement wording was
 * still an open product question. That is now settled — length when unstarted, `6h 12m left` once
 * started, `Finished` at the end — so the formatting moved to `DetailsProgressText` and this holds
 * only what it needs.
 *
 * Carrying millis instead of a string is what makes the rule enforceable: the screen has no
 * duration to print raw, and the wording is resolved from `strings.xml` at the point of render
 * rather than assembled in a ViewModel that cannot see the locale.
 */
data class ProgressLine(
  val state: BookProgressState = BookProgressState.Unstarted,
  val progressMillis: Long = 0L,
  val durationMillis: Long = 0L,
  val percentage: String = "",
)

/**
 * The download control, as one exhaustive state.
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
