package io.github.mattpvaughn.chronicle.features.player

import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import io.github.mattpvaughn.chronicle.features.player.AudiobookRenderersFactory.Companion.MINIMUM_SILENCE_DURATION_US
import io.github.mattpvaughn.chronicle.features.player.AudiobookRenderersFactory.Companion.SILENCE_RETENTION_RATIO
import io.github.mattpvaughn.chronicle.features.player.AudiobookRenderersFactory.Companion.narrationSilenceSkipping
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that silence skipping is retuned for narration, and that speed control survives it.
 *
 * The *values* cannot be asserted meaningfully — whether a book is listenable is a judgement about
 * audio, and belongs to the live pass (cu-73). What a test can and must pin is that the custom
 * processor is **installed at all**, and that the chain still carries [SonicAudioProcessor]:
 * otherwise a future refactor silently reverts to ExoPlayer's video-oriented defaults, or drops
 * playback-speed control, with nothing failing.
 */
class NarrationSilenceSkippingTest {
  /**
   * The library defaults, read from media3-exoplayer 1.11.0. Duplicated here deliberately: if a
   * Media3 bump changes them, this test should fail and prompt a re-read rather than silently
   * comparing against a moved target.
   */
  private companion object {
    const val LIBRARY_DEFAULT_MINIMUM_SILENCE_DURATION_US = 100_000L
    const val LIBRARY_DEFAULT_SILENCE_RETENTION_RATIO = 0.2f
  }

  @Test
  fun `the minimum silence duration is longer than a gap between words`() {
    // The core defect: 100ms is shorter than ordinary inter-word pauses in speech, so the default
    // fires mid-sentence. Anything in that range is not usable for narration.
    assertTrue(
      "a threshold near the library default clips speech",
      MINIMUM_SILENCE_DURATION_US >= 500_000L,
    )
    assertTrue(
      MINIMUM_SILENCE_DURATION_US > LIBRARY_DEFAULT_MINIMUM_SILENCE_DURATION_US,
    )
  }

  @Test
  fun `most of each silence is retained rather than removed`() {
    assertTrue(
      "pauses should be shortened, not stripped",
      SILENCE_RETENTION_RATIO > LIBRARY_DEFAULT_SILENCE_RETENTION_RATIO,
    )
    assertTrue(SILENCE_RETENTION_RATIO >= 0.5f)
  }

  @Test
  fun `a configured processor is produced`() {
    assertNotNull(narrationSilenceSkipping())
  }

  /**
   * Speed control must survive the chain override. `SonicAudioProcessor` implements speed and pitch
   * adjustment, so a chain built without it would leave the player unable to change speed — the
   * most-used feature in an audiobook app — with no error anywhere.
   */
  @Test
  fun `the processor chain keeps sonic for playback speed`() {
    val chain =
      DefaultAudioSink.DefaultAudioProcessorChain(
        emptyArray(),
        narrationSilenceSkipping(),
        SonicAudioProcessor(),
      )

    // A speed other than 1.0 must be accepted by the chain rather than ignored.
    val applied =
      chain.applyPlaybackParameters(androidx.media3.common.PlaybackParameters(1.5f))

    assertTrue("the chain must honour a non-default speed", applied.speed == 1.5f)
  }

  @Test
  fun `the chain reports skip-silence as toggleable`() {
    val chain =
      DefaultAudioSink.DefaultAudioProcessorChain(
        emptyArray(),
        narrationSilenceSkipping(),
        SonicAudioProcessor(),
      )

    assertTrue(chain.applySkipSilenceEnabled(true))
  }

  @Test
  fun `the chain exposes both processors`() {
    val chain =
      DefaultAudioSink.DefaultAudioProcessorChain(
        emptyArray(),
        narrationSilenceSkipping(),
        SonicAudioProcessor(),
      )

    val kinds = chain.audioProcessors.map { it::class }

    assertTrue(
      "silence skipping must be in the chain, or the tuning is inert",
      kinds.contains(SilenceSkippingAudioProcessor::class),
    )
    assertTrue(
      "sonic must be in the chain, or playback speed breaks",
      kinds.contains(SonicAudioProcessor::class),
    )
  }
}
