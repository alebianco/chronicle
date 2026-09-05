package io.github.mattpvaughn.chronicle.injection

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.mp3.Mp3Extractor
import io.github.mattpvaughn.chronicle.features.player.artworkFreeExtractorsFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.min

/**
 * Embedded cover art must never be decoded (upstream mattttvaughn/chronicle#83 and #16:
 * `OutOfMemoryError` in `MediaMetadata.maybeSetArtworkData` on multi-GB books).
 *
 * An audiobook is one very large file carrying a single APIC/`covr` frame, and ExoPlayer's
 * default is to copy that image into a heap byte array for every media item. Chronicle never
 * reads it — artwork comes from Plex via `plexConfig.getBitmapFromServer(book.thumb)` — so the
 * parse was pure cost on exactly the files least able to afford it.
 *
 * This drives a real MP3 carrying a real PNG cover (`test/resources/tone-with-artwork.mp3`,
 * generated with ffmpeg) through the extractor `ServiceModule` installs, and asserts no
 * [ApicFrame] survives. Removing either flag makes it fail — verified by sabotage, which
 * matters because the weaker alternative (asserting the flag constants) cannot tell whether
 * the builder was ever handed them.
 *
 * The `ExtractorInput` is hand-rolled rather than Media3's `FakeExtractorInput`: that lives in
 * `media3-test-utils`, and a whole test artifact is not worth one seekable byte-array reader.
 *
 * It drives [artworkFreeExtractorsFactory] itself rather than rebuilding the same
 * configuration here — a test that restated the flags would pass even if the player were built
 * with different ones.
 * Robolectric is needed only because `DefaultExtractorsFactory.createExtractors` sniffs the
 * container from an `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class EmbeddedArtworkTest {
  private fun apicFramesFrom(factory: DefaultExtractorsFactory): List<ApicFrame> {
    val bytes =
      checkNotNull(javaClass.classLoader!!.getResourceAsStream("tone-with-artwork.mp3")) {
        "tone-with-artwork.mp3 is missing from test resources"
      }.use { it.readBytes() }

    // The real factory, asked the way ExoPlayer asks it: the container is sniffed from the Uri,
    // so the extension is what selects Mp3Extractor and applies the mp3 flags.
    val extractor =
      factory.createExtractors(Uri.parse("file:///tone-with-artwork.mp3"), emptyMap())
        .first { it is Mp3Extractor }
    val input = ByteArrayExtractorInput(bytes)
    val output = CapturingOutput()
    extractor.init(output)

    // The ID3 header is read before the first sample, so a bounded read is enough; the loop
    // exists only to get past it, not to decode the whole file.
    val position = PositionHolder()
    var reads = 0
    while (reads++ < 200 && output.formats.isEmpty()) {
      if (extractor.read(input, position) == Extractor.RESULT_END_OF_INPUT) break
    }

    return output.formats.flatMap { format ->
      val metadata = format.metadata ?: return@flatMap emptyList()
      (0 until metadata.length()).mapNotNull { metadata.get(it) as? ApicFrame }
    }
  }

  @Test
  fun `the fixture really does carry embedded artwork`() {
    // Guards the guard: were ffmpeg's APIC frame ever to stop being written, the real assertion
    // below would pass vacuously while proving nothing.
    assertTrue(
      "the fixture must contain an APIC frame under stock flags, or the test below is vacuous",
      apicFramesFrom(DefaultExtractorsFactory()).isNotEmpty(),
    )
  }

  @Test
  fun `Chronicle's extractors drop embedded artwork`() {
    val frames = apicFramesFrom(artworkFreeExtractorsFactory())

    assertTrue(
      "embedded cover art reached the metadata (${frames.size} APIC frame(s)); " +
        "ServiceModule must keep FLAG_DISABLE_ARTWORK_METADATA on both mp3 and mp4",
      frames.isEmpty(),
    )
  }

  /** Minimal seekable [ExtractorInput] over a byte array. */
  private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
    private var position = 0
    private var peekPosition = 0

    override fun read(
      target: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (position >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
      val count = min(length, data.size - position)
      System.arraycopy(data, position, target, offset, count)
      position += count
      peekPosition = position
      return count
    }

    override fun readFully(
      target: ByteArray,
      offset: Int,
      length: Int,
      allowEndOfInput: Boolean,
    ): Boolean {
      if (position + length > data.size) {
        if (allowEndOfInput) return false
        throw java.io.EOFException()
      }
      System.arraycopy(data, position, target, offset, length)
      position += length
      peekPosition = position
      return true
    }

    override fun readFully(
      target: ByteArray,
      offset: Int,
      length: Int,
    ) {
      readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
      if (position >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
      val count = min(length, data.size - position)
      position += count
      peekPosition = position
      return count
    }

    override fun skipFully(
      length: Int,
      allowEndOfInput: Boolean,
    ): Boolean {
      if (position + length > data.size) {
        if (allowEndOfInput) return false
        throw java.io.EOFException()
      }
      position += length
      peekPosition = position
      return true
    }

    override fun skipFully(length: Int) {
      skipFully(length, false)
    }

    override fun peek(
      target: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (peekPosition >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
      val count = min(length, data.size - peekPosition)
      System.arraycopy(data, peekPosition, target, offset, count)
      peekPosition += count
      return count
    }

    override fun peekFully(
      target: ByteArray,
      offset: Int,
      length: Int,
      allowEndOfInput: Boolean,
    ): Boolean {
      if (peekPosition + length > data.size) {
        if (allowEndOfInput) return false
        throw java.io.EOFException()
      }
      System.arraycopy(data, peekPosition, target, offset, length)
      peekPosition += length
      return true
    }

    override fun peekFully(
      target: ByteArray,
      offset: Int,
      length: Int,
    ) {
      peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(
      length: Int,
      allowEndOfInput: Boolean,
    ): Boolean {
      if (peekPosition + length > data.size) {
        if (allowEndOfInput) return false
        throw java.io.EOFException()
      }
      peekPosition += length
      return true
    }

    override fun advancePeekPosition(length: Int) {
      advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
      peekPosition = position
    }

    override fun getPeekPosition(): Long = peekPosition.toLong()

    override fun getPosition(): Long = position.toLong()

    override fun getLength(): Long = data.size.toLong()

    override fun <E : Throwable?> setRetryPosition(
      position: Long,
      e: E & Any,
    ) {
      throw e
    }
  }

  private class CapturingOutput : ExtractorOutput {
    val formats = mutableListOf<Format>()

    override fun track(
      id: Int,
      type: Int,
    ): TrackOutput = CapturingTrack(formats)

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) = Unit
  }

  private class CapturingTrack(private val formats: MutableList<Format>) : TrackOutput {
    override fun format(format: Format) {
      formats += format
    }

    override fun sampleData(
      input: DataReader,
      length: Int,
      allowEndOfInput: Boolean,
      sampleDataPart: Int,
    ): Int = input.read(ByteArray(length), 0, length)

    override fun sampleData(
      data: ParsableByteArray,
      length: Int,
      sampleDataPart: Int,
    ) {
      data.skipBytes(length)
    }

    override fun sampleMetadata(
      timeUs: Long,
      flags: Int,
      size: Int,
      offset: Int,
      cryptoData: TrackOutput.CryptoData?,
    ) = Unit
  }
}
