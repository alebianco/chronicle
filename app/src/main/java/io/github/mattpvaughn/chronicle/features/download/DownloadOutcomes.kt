package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID

/**
 * How a book's download batch ended.
 *
 * Replaces Fetch2's `Status` enum at this boundary. Only the three outcomes this app acts on are
 * representable: Fetch2 had nine, and six of them (`ADDED`, `QUEUED`, `DOWNLOADING`, `PAUSED`,
 * `REMOVED`, `DELETED`, `NONE`) collapsed to "still in flight or not our business" every time they
 * were read here.
 */
enum class BookDownloadStatus {
  /** Every track arrived. */
  Completed,

  /** At least one track failed, so the book is not downloaded. */
  Failed,

  /** Still going, or nothing to say. Never reported to the user. */
  InFlight,
}

/**
 * What a finished batch of downloads means, per book.
 *
 * A pure derivation, separated from the notifications that render it. The rules are small and each
 * exists because of a way the notification was wrong:
 *
 * - **A cancelled download is not news.** The user cancelled it; telling them so is noise. Cancels
 *   are tracked per *track*, so the filter is per track rather than per book.
 * - **Failed outranks completed.** A book whose tracks partly failed is not downloaded, and
 *   reporting it as complete is the shape of three separate bugs where downloads went missing
 *   while the app claimed success.
 * - **A book with no name or no id is dropped**, not rendered with a blank title.
 *   `NO_AUDIOBOOK_FOUND_ID` means the id could not be resolved to a book.
 * - **Only Failed and Completed are reported at all**: anything else is still in flight.
 */
data class DownloadOutcome(
  val bookName: String,
  val bookId: String,
  val status: BookDownloadStatus,
  val errors: List<String>,
)

/**
 * One track's final state, as the notification worker accumulates it.
 *
 * Replaces reading Fetch2's `Download` records. The worker observes [DownloadEvent]s and builds
 * these, so the reduction below is over the app's own model rather than an engine's — which is what
 * lets it be tested without an engine at all.
 */
data class TrackDownloadResult(
  val trackId: String,
  val bookId: String,
  val bookTitle: String,
  val status: TrackStatus,
  val error: String? = null,
) {
  enum class TrackStatus { Completed, Failed, Cancelled }
}

/**
 * Groups [this] by book and reduces each group to the one outcome worth telling the user about.
 *
 * Books still in progress, cancelled, unnamed or unidentifiable are left out.
 */
fun List<TrackDownloadResult>.toOutcomes(): List<DownloadOutcome> =
  groupBy { it.bookId }
    .map { (bookId, forBook) ->
      // Per track, not per book: cancelling one track of a book leaves the rest meaningful.
      val statuses = forBook.filterNot { it.status == TrackDownloadResult.TrackStatus.Cancelled }.map { it.status }
      DownloadOutcome(
        bookName = forBook.firstOrNull()?.bookTitle ?: "",
        bookId = bookId,
        status =
          when {
            TrackDownloadResult.TrackStatus.Failed in statuses -> BookDownloadStatus.Failed
            TrackDownloadResult.TrackStatus.Completed in statuses -> BookDownloadStatus.Completed
            else -> BookDownloadStatus.InFlight
          },
        errors = forBook.mapNotNull { it.error }.distinct(),
      )
    }.filter {
      it.bookName.isNotEmpty() &&
        it.bookId != NO_AUDIOBOOK_FOUND_ID &&
        it.status in listOf(BookDownloadStatus.Failed, BookDownloadStatus.Completed)
    }
