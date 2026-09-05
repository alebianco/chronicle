package io.github.mattpvaughn.chronicle.features.download

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Error
import com.tonyodev.fetch2.Status
import com.tonyodev.fetch2core.Extras
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished batch of downloads reports (cu-179).
 *
 * Extracted from `DownloadNotificationWorker`, which could not be constructed in a unit test at all
 * until this task — it resolved `Injector.get()` in a field initialiser, and `Injector.get()` is
 * `ChronicleApplication.get()`, whose `INSTANCE!!` throws. 1,420 instructions at 0%.
 *
 * Each rule here exists because of a way the notification was wrong, and every one of them is the
 * same family of defect as cu-81, cu-85 and cu-153: **downloads that go missing while the app
 * claims success**.
 */
class DownloadOutcomesTest {
  private fun download(
    bookId: String,
    trackId: String,
    status: Status,
    error: Error = Error.NONE,
    name: String = "Mistborn",
  ) = mockk<Download> {
    // `groupByBookId` reads the book id from `extras`, deliberately — Fetch2's `group` is a hash
    // that cannot be turned back into a book id, so a fixture keyed on it would group nothing.
    every { extras } returns Extras(mapOf(EXTRA_BOOK_ID to bookId))
    every { tag } returns name
    every { this@mockk.status } returns status
    every { this@mockk.error } returns error
    every { id } returns trackId.hashCode()
  }

  @Test
  fun `a fully completed book is reported as completed`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.COMPLETED),
        download("1001", "t2", Status.COMPLETED),
      ).toOutcomes()

    assertEquals(1, outcomes.size)
    assertEquals(Status.COMPLETED, outcomes.single().status)
    assertEquals("Mistborn", outcomes.single().bookName)
  }

  /**
   * The rule that matters most. A book whose tracks partly failed is **not downloaded**, and
   * reporting it complete is exactly the shape of cu-81/cu-85/cu-153 — the user believes they have
   * offline audio they do not have, and finds out on a train.
   */
  @Test
  fun `one failed track makes the whole book failed`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.COMPLETED),
        download("1001", "t2", Status.FAILED, error = Error.NO_NETWORK_CONNECTION),
        download("1001", "t3", Status.COMPLETED),
      ).toOutcomes()

    assertEquals(Status.FAILED, outcomes.single().status)
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
        download("1001", "t1", Status.CANCELLED),
        download("1001", "t2", Status.CANCELLED),
      ).toOutcomes()

    assertTrue("cancelling is not something to notify about", outcomes.isEmpty())
  }

  @Test
  fun `cancelling one track does not hide the rest of the book`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.CANCELLED),
        download("1001", "t2", Status.COMPLETED),
      ).toOutcomes()

    assertEquals(Status.COMPLETED, outcomes.single().status)
  }

  /** Still downloading is not an outcome; nothing should be said yet. */
  @Test
  fun `a book still in progress is not reported`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.DOWNLOADING),
        download("1001", "t2", Status.QUEUED),
      ).toOutcomes()

    assertTrue(outcomes.isEmpty())
  }

  @Test
  fun `a book with no name is dropped rather than rendered blank`() {
    val outcomes = listOf(download("1001", "t1", Status.COMPLETED, name = "")).toOutcomes()

    assertTrue("a nameless notification tells the user nothing", outcomes.isEmpty())
  }

  @Test
  fun `several books each get their own outcome`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.COMPLETED, name = "Mistborn"),
        download("1002", "t2", Status.FAILED, name = "Elantris", error = Error.UNKNOWN),
      ).toOutcomes()

    assertEquals(2, outcomes.size)
    assertEquals(
      mapOf("Mistborn" to Status.COMPLETED, "Elantris" to Status.FAILED),
      outcomes.associate { it.bookName to it.status },
    )
  }

  /** Repeated errors across a book's tracks are one line, not one per track. */
  @Test
  fun `errors are de-duplicated`() {
    val outcomes =
      listOf(
        download("1001", "t1", Status.FAILED, error = Error.NO_NETWORK_CONNECTION),
        download("1001", "t2", Status.FAILED, error = Error.NO_NETWORK_CONNECTION),
      ).toOutcomes()

    assertEquals(listOf("NO_NETWORK_CONNECTION"), outcomes.single().errors)
  }

  @Test
  fun `an empty batch reports nothing`() {
    assertTrue(emptyList<Download>().toOutcomes().isEmpty())
  }
}
