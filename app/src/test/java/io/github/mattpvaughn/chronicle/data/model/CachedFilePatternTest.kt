package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files in the download directory are treated as cached tracks.
 *
 * The pattern was `\d*\..+`, and `\d*` matches **zero** digits — so a dotfile or any
 * `.something` matched, and `getTrackIdFromFileName` then returned an empty id. The
 * scan runs over a user-writable directory (`MoveSyncLocationWorker` moves files between SD
 * card and internal storage), so a stray file is not hypothetical.
 *
 * It was then `\d+`, which was too *narrow*: cu-71 made ids `String` so a non-numeric backend can
 * be represented, and a digits-only pattern made this the de facto arbiter of id format — an
 * Audiobookshelf id would download and then be invisible to the scan (cu-111).
 *
 * The pattern's one job is **"is this filename one of ours?"**. It cannot distinguish `cover.jpg`
 * from a track whose id happens to be `cover`, and it does not need to: every consumer is safe
 * against a false positive, which the last two tests here pin. Rejecting an unsafe *id* is
 * [MediaId]'s job, applied where a server response becomes a model.
 */
class CachedFilePatternTest {
  @Test
  fun `a normal cached track matches`() {
    assertTrue(MediaItemTrack.cachedFilePattern.matches("3001.mp3"))
    assertTrue(MediaItemTrack.cachedFilePattern.matches("42.m4b"))
  }

  @Test
  fun `the track id is recovered from the name`() {
    assertEquals("3001", MediaItemTrack.getTrackIdFromFileName("3001.mp3"))
  }

  @Test
  fun `a file with no digits before the dot does not match`() {
    assertFalse(
      "`\\\\d*` allowed zero digits, so this matched and then threw on \"\".toInt()",
      MediaItemTrack.cachedFilePattern.matches(".nomedia"),
    )
    assertFalse(MediaItemTrack.cachedFilePattern.matches(".DS_Store"))
  }

  /**
   * A non-numeric id must match — this is the cu-111 case. Ids are `String` so an Audiobookshelf
   * or WebDAV backend can be represented (decision-11); a digits-only pattern meant such a track
   * downloaded fine and was then invisible to the cache scan, so it was deleted and re-downloaded
   * forever.
   */
  @Test
  fun `a non-numeric id matches`() {
    assertTrue(MediaItemTrack.cachedFilePattern.matches("li_8x2h9fk3.m4b"))
    assertTrue(MediaItemTrack.cachedFilePattern.matches("abc123.mp3"))
    assertTrue(MediaItemTrack.cachedFilePattern.matches("A1B2-C3.opus"))
  }

  /**
   * The consequence of the widening, stated plainly: a stray file whose name *looks* like an id
   * now matches. That is safe at every consumer, and these are the reasons —
   *
   * - the cache scan looks the id up in the DB and requires a size match, so an unknown id is
   *   dropped (`refreshTrackDownloadedStatus`);
   * - the delete path is gated on an exact match against DB-derived filenames, so a stray file is
   *   never deleted (`uncacheAllInLibrary`).
   *
   * If either of those guards is ever removed, this test is the note explaining why it mattered.
   */
  @Test
  fun `a stray file that looks like an id is tolerated, not dangerous`() {
    assertTrue(
      "the pattern cannot tell 'cover' from an id, and does not need to",
      MediaItemTrack.cachedFilePattern.matches("cover.jpg"),
    )
    assertEquals("cover", MediaItemTrack.getTrackIdFromFileName("cover.jpg"))
  }

  /** A path separator or traversal in a name must never match, whatever else changes. */
  @Test
  fun `a traversal or separator never matches`() {
    assertFalse(MediaItemTrack.cachedFilePattern.matches("../../etc/passwd.mp3"))
    assertFalse(MediaItemTrack.cachedFilePattern.matches("..%2Fx.mp3"))
    assertFalse(MediaItemTrack.cachedFilePattern.matches("a/b.mp3"))
    assertFalse(MediaItemTrack.cachedFilePattern.matches("..mp3"))
  }

  @Test
  fun `a name with no extension does not match`() {
    assertFalse(MediaItemTrack.cachedFilePattern.matches("3001"))
  }

  @Test
  fun `a partial download does not match`() {
    assertFalse(
      "a partial-download suffix must not be read as a finished track",
      MediaItemTrack.cachedFilePattern.matches("3001.mp3.part"),
    )
  }

  /**
   * Every name the pattern accepts must survive [MediaItemTrack.getTrackIdFromFileName],
   * since the scan calls one straight after the other.
   */
  @Test
  fun `every matching name yields a parseable id`() {
    listOf("1.mp3", "3001.m4b", "999999.opus", "0.mp3", "li_8x2h9fk3.m4b").forEach { name ->
      assertTrue("$name should match", MediaItemTrack.cachedFilePattern.matches(name))
      MediaItemTrack.getTrackIdFromFileName(name)
    }
  }
}
