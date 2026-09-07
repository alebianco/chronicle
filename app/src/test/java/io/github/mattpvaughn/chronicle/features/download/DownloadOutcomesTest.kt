package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.features.download.TrackDownloadResult.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished batch of downloads reports.
 *
 * Extracted from `DownloadNotificationWorker`, which could not be constructed in a unit test at all
 * before it — it resolved `Injector.get()` in a field initialiser, and `Injector.get()` is
 * `ChronicleApplication.get()`, whose `INSTANCE!!` throws. 1,420 instructions at 0%.
 *
 * Each rule here exists because of a way the notification was wrong, and every one of them is the
 * same family of defect, hit three separate times: **downloads that go missing while the app
 * claims success**. Every rule survived the move off Fetch2 unchanged; only the fixture changed.
 *
 * **No mocks any more.** The reduction used to run over Fetch2's `Download`, whose book id lived in
 * an `Extras` map because the `Int` group was a one-way hash — so a fixture needed `mockk` and had
 * to know that detail. `TrackDownloadResult` is the app's own data class with the id in a field, so
 * the fixtures below are plain constructor calls. That is the seam paying for itself in test
 * legibility, not just in swappability.
 */
class DownloadOutcomesTest {
  private fun result(
    bookId: String,
    trackId: String,
    status: TrackStatus,
    error: String? = null,
    name: String = "Mistborn",
  ) = TrackDownloadResult(
    trackId = trackId,
    bookId = bookId,
    bookTitle = name,
    status = status,
    error = error,
  )

  @Test
  fun `a fully completed book is reported as completed`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Completed),
        result("1001", "t2", TrackStatus.Completed),
      ).toOutcomes()

    assertEquals(1, outcomes.size)
    assertEquals(BookDownloadStatus.Completed, outcomes.single().status)
    assertEquals("Mistborn", outcomes.single().bookName)
  }

  /**
   * The rule that matters most. A book whose tracks partly failed is **not downloaded**, and
   * reporting it complete is exactly the shape hit three separate times — the user believes they
   * have offline audio they do not have, and finds out on a train.
   */
  @Test
  fun `one failed track makes the whole book failed`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Completed),
        result("1001", "t2", TrackStatus.Failed, error = "NO_NETWORK_CONNECTION"),
        result("1001", "t3", TrackStatus.Completed),
      ).toOutcomes()

    assertEquals(BookDownloadStatus.Failed, outcomes.single().status)
    assertEquals(listOf("NO_NETWORK_CONNECTION"), outcomes.single().errors)
  }

  /**
   * A cancelled download is not news — the user cancelled it. Filtering is **per track**, because
   * cancelling one track of a book leaves the rest meaningful.
   */
  @Test
  fun `a wholly cancelled book is not reported`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Cancelled),
        result("1001", "t2", TrackStatus.Cancelled),
      ).toOutcomes()

    assertTrue("cancelling is not something to notify about", outcomes.isEmpty())
  }

  @Test
  fun `cancelling one track does not hide the rest of the book`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Cancelled),
        result("1001", "t2", TrackStatus.Completed),
      ).toOutcomes()

    assertEquals(BookDownloadStatus.Completed, outcomes.single().status)
  }

  @Test
  fun `a book with no name is dropped rather than rendered blank`() {
    val outcomes = listOf(result("1001", "t1", TrackStatus.Completed, name = "")).toOutcomes()

    assertTrue("a nameless notification tells the user nothing", outcomes.isEmpty())
  }

  @Test
  fun `several books each get their own outcome`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Completed, name = "Mistborn"),
        result("1002", "t2", TrackStatus.Failed, name = "Elantris", error = "UNKNOWN"),
      ).toOutcomes()

    assertEquals(2, outcomes.size)
    assertEquals(
      mapOf("Mistborn" to BookDownloadStatus.Completed, "Elantris" to BookDownloadStatus.Failed),
      outcomes.associate { it.bookName to it.status },
    )
  }

  /** Repeated errors across a book's tracks are one line, not one per track. */
  @Test
  fun `errors are de-duplicated`() {
    val outcomes =
      listOf(
        result("1001", "t1", TrackStatus.Failed, error = "NO_NETWORK_CONNECTION"),
        result("1001", "t2", TrackStatus.Failed, error = "NO_NETWORK_CONNECTION"),
      ).toOutcomes()

    assertEquals(listOf("NO_NETWORK_CONNECTION"), outcomes.single().errors)
  }

  @Test
  fun `an empty batch reports nothing`() {
    assertTrue(emptyList<TrackDownloadResult>().toOutcomes().isEmpty())
  }

  /**
   * The "still in progress" case, which the port changed the *shape* of rather than the rule.
   *
   * Fetch2 had `DOWNLOADING` and `QUEUED` statuses, so a fixture could describe a book mid-flight
   * and assert nothing was reported. `TrackDownloadResult` only exists for tracks that have
   * *finished* — the worker accumulates it on Completed/Failed/Cancelled and keeps in-flight
   * tracks in a separate map — so an in-progress book is now represented by its tracks simply not
   * being in the list. Asserting that an empty list reports nothing is the same guarantee, and is
   * covered above.
   *
   * This test remains to record that the missing status values were deliberate, not an oversight:
   * six of Fetch2's nine collapsed to "not our business" at this boundary every time they were
   * read.
   */
  @Test
  fun `a book with no finished tracks is not reported`() {
    assertTrue(emptyList<TrackDownloadResult>().toOutcomes().isEmpty())
  }
}
