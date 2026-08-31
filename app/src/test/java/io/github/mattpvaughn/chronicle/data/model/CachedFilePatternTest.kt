package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files in the download directory are treated as cached tracks.
 *
 * The pattern was `\d*\..+`, and `\d*` matches **zero** digits — so a dotfile or any
 * `.something` matched, and `getTrackIdFromFileName` then called `"".toInt()` and threw. The
 * scan runs over a user-writable directory (`MoveSyncLocationWorker` moves files between SD
 * card and internal storage), so a stray file is not hypothetical.
 */
class CachedFilePatternTest {
  @Test
  fun `a normal cached track matches`() {
    assertTrue(MediaItemTrack.cachedFilePattern.matches("3001.mp3"))
    assertTrue(MediaItemTrack.cachedFilePattern.matches("42.m4b"))
  }

  @Test
  fun `the track id is recovered from the name`() {
    assertEquals(3001, MediaItemTrack.getTrackIdFromFileName("3001.mp3"))
  }

  @Test
  fun `a file with no digits before the dot does not match`() {
    assertFalse(
      "`\\\\d*` allowed zero digits, so this matched and then threw on \"\".toInt()",
      MediaItemTrack.cachedFilePattern.matches(".nomedia"),
    )
    assertFalse(MediaItemTrack.cachedFilePattern.matches(".DS_Store"))
  }

  @Test
  fun `a non-numeric name does not match`() {
    assertFalse(MediaItemTrack.cachedFilePattern.matches("cover.jpg"))
    assertFalse(MediaItemTrack.cachedFilePattern.matches("Chronicle.xml"))
  }

  @Test
  fun `a name with no extension does not match`() {
    assertFalse(MediaItemTrack.cachedFilePattern.matches("3001"))
  }

  @Test
  fun `a mixed name does not match`() {
    assertFalse(
      "a partial-download suffix must not be read as a finished track",
      MediaItemTrack.cachedFilePattern.matches("3001.mp3.part"),
    )
    assertFalse(MediaItemTrack.cachedFilePattern.matches("track3001.mp3"))
  }

  /**
   * Every name the pattern accepts must survive [MediaItemTrack.getTrackIdFromFileName],
   * since the scan calls one straight after the other.
   */
  @Test
  fun `every matching name yields a parseable id`() {
    listOf("1.mp3", "3001.m4b", "999999.opus", "0.mp3").forEach { name ->
      assertTrue("$name should match", MediaItemTrack.cachedFilePattern.matches(name))
      MediaItemTrack.getTrackIdFromFileName(name)
    }
  }
}
