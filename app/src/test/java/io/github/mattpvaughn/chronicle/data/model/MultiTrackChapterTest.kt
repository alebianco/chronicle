package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook.MID_BOOK_POSITION
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook.MID_TRACK_OFFSET
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chapter lookup on a **multi-track** book (cu-115).
 *
 * `Chapter.bookStartTimeOffset` is absolute within the *book*. On a single-track book that is the
 * same number as the in-track offset, so passing the wrong one is invisible; here they differ by
 * up to 1,200,000 ms.
 *
 * These tests pin the frame at the boundary between the two lookups, which is where four separate
 * bugs have already lived (cu-13, cu-49, cu-93, cu-96).
 */
class MultiTrackChapterTest {
  private val chapters = MultiTrackBook.chapters()

  /** The fixture's own premise: the two frames genuinely differ at the position under test. */
  @Test
  fun `the in-track offset and the book offset differ at the test position`() {
    assertEquals(600_000L, MID_BOOK_POSITION - MID_TRACK_OFFSET)
  }

  /** The book-frame lookup is the one that takes a book position. */
  @Test
  fun `the book-frame lookup finds the chapter containing a book position`() {
    assertEquals("Chapter 3", chapters.chapterAtBookProgress(MID_BOOK_POSITION).title)
  }

  /**
   * The trap, stated as a test so it cannot be re-introduced quietly: handing the book-frame
   * lookup an **in-track** offset silently returns the wrong chapter rather than failing.
   *
   * 150_000 is 2m30s into track 2 — but as a *book* position it is inside chapter 1. This is what
   * `MainActivityViewModel.currentChapterTitle` was doing, and why the mini player showed the
   * wrong chapter (or none) on any track but the first.
   */
  @Test
  fun `an in-track offset passed as a book position finds the wrong chapter`() {
    assertEquals(
      "if this ever returns Chapter 3, the frames have been unified and this test should go",
      "Chapter 1",
      chapters.chapterAtBookProgress(MID_TRACK_OFFSET).title,
    )
  }

  /** Chapter boundaries that fall exactly on a track boundary are the off-by-one case. */
  @Test
  fun `a position exactly on a track boundary resolves to the later chapter`() {
    assertEquals("Chapter 3", chapters.chapterAtBookProgress(600_000L).title)
    assertEquals("Chapter 5", chapters.chapterAtBookProgress(1_200_000L).title)
  }

  /** And boundaries strictly inside a track must resolve on the half-open rule. */
  @Test
  fun `a position exactly on a chapter boundary inside a track resolves to the later chapter`() {
    assertEquals("Chapter 2", chapters.chapterAtBookProgress(300_000L).title)
    assertEquals("Chapter 4", chapters.chapterAtBookProgress(900_000L).title)
  }

  @Test
  fun `the first and last instants of the book resolve`() {
    assertEquals("Chapter 1", chapters.chapterAtBookProgress(0L).title)
    assertEquals(
      "Chapter 6",
      chapters.chapterAtBookProgress(MultiTrackBook.BOOK_DURATION - 1).title,
    )
  }

  /** Past the end clamps to the last chapter rather than returning EMPTY_CHAPTER. */
  @Test
  fun `a position past the end clamps to the last chapter`() {
    assertEquals("Chapter 6", chapters.chapterAtBookProgress(MultiTrackBook.BOOK_DURATION).title)
  }

  /**
   * The track-scoped lookup takes a **book** offset too, despite also being given a track id — the
   * `timeStamp` is compared against `bookStartTimeOffset`. Pinned because the parameter name
   * (`timeStamp`) says nothing about which frame it belongs to, and that ambiguity is the root of
   * the whole bug family.
   */
  @Test
  fun `the track-scoped lookup also expects a book offset`() {
    val found = chapters.getChapterAt(trackId = "2002", timeStamp = MID_BOOK_POSITION)

    assertEquals("Chapter 3", found.title)
  }

  /** And the same call with an in-track offset finds nothing, because no chapter covers it. */
  @Test
  fun `the track-scoped lookup finds nothing when given an in-track offset`() {
    val found = chapters.getChapterAt(trackId = "2002", timeStamp = MID_TRACK_OFFSET)

    assertEquals(EMPTY_CHAPTER, found)
  }
}
