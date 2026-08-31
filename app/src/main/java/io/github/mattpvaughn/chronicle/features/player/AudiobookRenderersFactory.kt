package io.github.mattpvaughn.chronicle.features.player

import android.content.Context
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * ExoPlayer renderers with silence skipping retuned for narration.
 *
 * `ExoPlayer.skipSilenceEnabled` uses `SilenceSkippingAudioProcessor`'s defaults, which are tuned
 * for stripping dead air from video. Read out of media3-exoplayer 1.11.0:
 *
 * | Default | Value | Effect on a narrated book |
 * |---|---|---|
 * | `MINIMUM_SILENCE_DURATION_US` | 100 ms | Ordinary gaps *between words* exceed this, so it fires mid-sentence |
 * | `SILENCE_RETENTION_RATIO` | 0.2 | Keeps a fifth of each pause, flattening sentence and paragraph structure |
 * | `PADDING_SILENCE_US` | 20 ms | Too little breathing room, so words run together |
 *
 * The result is the owner's report that the setting *"makes most tracks un-listenable"*. The setting
 * was never mis-wired; the defaults are simply wrong for speech (cu-88).
 *
 * There is precedent for overriding ExoPlayer's video-oriented defaults in this codebase:
 * `ServiceModule.exoPlayer()` already enlarges the load-control buffers for the same reason.
 *
 * **[SonicAudioProcessor] must be passed through.** It implements playback-speed and pitch
 * adjustment, so dropping it from the chain would silently break the speed control — the most-used
 * feature in an audiobook player.
 */
@UnstableApi
class AudiobookRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink =
    DefaultAudioSink.Builder(context)
      .setEnableFloatOutput(enableFloatOutput)
      .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
      .setAudioProcessorChain(
        DefaultAudioSink.DefaultAudioProcessorChain(
          // No extra processors; the two below are the chain's own named slots.
          emptyArray(),
          narrationSilenceSkipping(),
          // Speed and pitch. Omitting this breaks playback-speed control.
          SonicAudioProcessor(),
        ),
      )
      .build()

  companion object {
    /**
     * Only collapse a pause that is clearly a sentence or paragraph break, and shorten it rather
     * than remove it.
     *
     * These are **starting points chosen against the defaults' known failure modes**, not measured
     * values — "listenable" is a judgement about audio that cannot be made from a unit test. They
     * are expected to be revised after the live pass ([[cu-73]]), including against a quiet-voiced
     * narrator.
     */
    const val MINIMUM_SILENCE_DURATION_US = 800_000L

    /** Keep over half of each detected silence, so pacing survives. */
    const val SILENCE_RETENTION_RATIO = 0.55f

    /** Cap on a single retained silence; the default 2 s is already reasonable for speech. */
    const val MAX_SILENCE_TO_KEEP_DURATION_US = 2_000_000L

    /** Percentage of the original volume kept during a retained silence. */
    const val MIN_VOLUME_TO_KEEP_PERCENTAGE = 10

    /**
     * Left at the library default. Raising it would treat quiet narration as silence; lowering it
     * only helps if room noise is being *kept*, which is the opposite of the reported problem.
     */
    const val SILENCE_THRESHOLD_LEVEL: Short = 1024

    fun narrationSilenceSkipping(): SilenceSkippingAudioProcessor =
      SilenceSkippingAudioProcessor(
        MINIMUM_SILENCE_DURATION_US,
        SILENCE_RETENTION_RATIO,
        MAX_SILENCE_TO_KEEP_DURATION_US,
        MIN_VOLUME_TO_KEEP_PERCENTAGE,
        SILENCE_THRESHOLD_LEVEL,
      )
  }
}
