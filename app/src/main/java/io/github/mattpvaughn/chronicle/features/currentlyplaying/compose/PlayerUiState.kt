package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import androidx.annotation.DrawableRes
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel.PlayerProgress

/**
 * Everything the player's body renders, as one value.
 *
 * The Fragment collected **24 separate flows** and pushed each into a view by hand, which is the
 * shape decision-22 was accepted to remove: five of the recorded bugs live on this screen
 * and every one of them is a state that was true in one
 * place and not in another.
 *
 * Grouped rather than flat, because the groups are what actually change together: the text block
 * moves once a second, the transport row only on a play/pause, the artwork only when the book
 * does. Compose skips a composable whose inputs are unchanged, so the grouping *is* the
 * `setTextIfChanged` / `boundTitle` / `valueTo != newMax` machinery the Fragment hand-rolled —
 * expressed once, in the type, rather than at eleven call sites.
 */
data class PlayerUiState(
  val artwork: ArtworkState = ArtworkState(),
  val text: TextState = TextState(),
  val slider: SliderState = SliderState(),
  val transport: TransportState = TransportState(),
  val utility: UtilityState = UtilityState(),
  val isLoadingTracks: Boolean = false,
  val hasFailedProgressSync: Boolean = false,
)

/**
 * The cover and the book title.
 *
 * Its own group deliberately: `audiobook` is Room-backed and `ProgressUpdater` rewrites
 * `Audiobook.progress` **every second**, so it re-emits at tick rate with identical title and
 * artwork. The Fragment guarded that with `boundTitle`/`boundThumb` fields; here an unchanged
 * `ArtworkState` skips the composable outright.
 */
data class ArtworkState(
  val title: String = "",
  val thumb: String? = null,
  val serverConnected: Boolean = true,
)

/** The two-level progress readout. Human-formatted, never a raw `h:mm:ss/h:mm:ss` pair. */
data class TextState(
  val progress: PlayerProgress? = null,
  val progressPercentage: String = "",
  val chapterTitle: String = "",
)

/**
 * The seek bar.
 *
 * [isSliding] is part of the state rather than a field the renderer consults — the change that
 * made this screen migratable at all, since Compose renders `state.value` and has no "write
 * time" at which to read a `var`.
 */
data class SliderState(
  val value: Float = 0f,
  val valueTo: Float = 1f,
  val isSliding: Boolean = false,
)

/**
 * Play/pause and the two jump buttons. Changes on a transport event, never on a tick.
 *
 * The jump icons default to a **real drawable**, not `0`. `painterResource(0)` throws
 * `Resources$NotFoundException`, so a zero default turns "state not resolved yet" into a crash the
 * moment anything composes before the first emission — which is exactly what the `stateIn` seed
 * does. Found by the screen's own tests before it reached a device.
 */
data class TransportState(
  val isPlaying: Boolean = false,
  val isAudioLoading: Boolean = false,
  @DrawableRes val jumpForwardsIcon: Int = R.drawable.ic_forward_30_white,
  @DrawableRes val jumpBackwardsIcon: Int = R.drawable.ic_replay_10_white,
)

/** Speed and sleep timer — the tray under the transport row. */
data class UtilityState(
  val speedLabel: String = "",
  val isSleepTimerActive: Boolean = false,
  val sleepTimerRemaining: String = "",
)
