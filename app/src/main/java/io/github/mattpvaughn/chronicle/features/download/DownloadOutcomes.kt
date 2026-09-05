package io.github.mattpvaughn.chronicle.features.download

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Error
import com.tonyodev.fetch2.Status
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID

/**
 * What a finished batch of downloads means, per book (cu-179).
 *
 * A pure derivation over Fetch2's `Download` list, separated from the notifications that render it.
 * The rules are small and each exists because of a way the notification was wrong:
 *
 * - **A cancelled download is not news.** The user cancelled it; telling them so is noise. Fetch2
 *   reports `CANCELLED` per *track*, so the filter is per track rather than per book.
 * - **FAILED outranks COMPLETED.** A book whose tracks partly failed is not downloaded, and
 *   reporting it as complete is the shape of cu-81/cu-85/cu-153 — downloads that go missing while
 *   the app claims success.
 * - **A book with no name or no id is dropped**, not rendered with a blank title. `NO_AUDIOBOOK_FOUND_ID`
 *   means the group tag could not be parsed back to a book.
 * - **Only FAILED and COMPLETED are reported at all**: anything else is still in flight.
 */
data class DownloadOutcome(
  val bookName: String,
  val bookId: String,
  val status: Status,
  val errors: List<String>,
)

/**
 * Groups [downloads] by book and reduces each group to the one outcome worth telling the user
 * about. Books still in progress, cancelled, unnamed or unidentifiable are left out.
 */
fun List<Download>.toOutcomes(): List<DownloadOutcome> =
  groupByBookId()
    .map { (bookId, forBook) ->
      // Per track, not per book: cancelling one track of a book leaves the rest meaningful.
      val statuses = forBook.filterNot { it.status == Status.CANCELLED }.map { it.status }
      DownloadOutcome(
        bookName = forBook.firstOrNull()?.tag ?: "",
        bookId = bookId,
        status =
          when {
            Status.FAILED in statuses -> Status.FAILED
            Status.COMPLETED in statuses -> Status.COMPLETED
            else -> Status.NONE
          },
        errors = forBook.filter { it.error != Error.NONE }.map { it.error.name }.distinct(),
      )
    }.filter {
      it.bookName.isNotEmpty() &&
        it.bookId != NO_AUDIOBOOK_FOUND_ID &&
        it.status in listOf(Status.FAILED, Status.COMPLETED)
    }
