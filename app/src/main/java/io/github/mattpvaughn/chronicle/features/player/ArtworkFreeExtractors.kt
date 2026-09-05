package io.github.mattpvaughn.chronicle.features.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor

/**
 * Extractors that never decode embedded cover art.
 *
 * An audiobook is one very large file carrying a single APIC/`covr` frame, and ExoPlayer's default
 * is to copy that image into a heap byte array for every media item — upstream's
 * `OutOfMemoryError` in `MediaMetadata.maybeSetArtworkData` on multi-GB books
 * (mattttvaughn/chronicle#83, #16). Nothing here reads it: artwork comes from Plex via
 * `plexConfig.getBitmapFromServer(book.thumb)`, so the parse was pure cost on exactly the files
 * least able to afford it.
 *
 * A top-level function rather than a member of `ServiceModule` for two reasons: the provider that
 * uses it needs a live `Service` and so cannot be called from a unit test at all, and `ServiceModule`
 * is `@ExperimentalTime`, which would spread an unrelated opt-in to every caller. `EmbeddedArtworkTest`
 * runs a real MP3 through *this* function — a test that rebuilt the same configuration itself would
 * pass while the player was built with different flags.
 */
@UnstableApi
fun artworkFreeExtractorsFactory(): DefaultExtractorsFactory =
  DefaultExtractorsFactory()
    .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ARTWORK_METADATA)
    .setMp4ExtractorFlags(Mp4Extractor.FLAG_DISABLE_ARTWORK_METADATA)
