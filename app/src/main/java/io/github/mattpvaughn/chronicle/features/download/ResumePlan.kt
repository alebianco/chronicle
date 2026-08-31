package io.github.mattpvaughn.chronicle.features.download

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Status

/**
 * What to do with downloads that were interrupted, decided separately from doing it.
 *
 * Exists so the decision is testable. [io.github.mattpvaughn.chronicle.data.sources.plex.CachedFileManager]
 * resolves `Injector.get().externalDeviceDirs()` in a field initialiser, so constructing one needs
 * a live `ChronicleApplication` and the whole Dagger graph — the same reason `ProgressReporter` was
 * split out of `PlexSyncScrobbleWorker` (cu-9). The manager keeps the Fetch2 plumbing; the
 * judgement lives here.
 */
object ResumePlan {
  /**
   * The ids of downloads that should be retried, given everything Fetch2 currently knows about.
   *
   * `resumeAll()` covers [Status.PAUSED], but not [Status.FAILED]: a download abandoned by the old
   * single-retry limit ends up FAILED, and Fetch2 will not touch it again without an explicit
   * `retry`. Those are the ones that need naming.
   *
   * [Status.CANCELLED] is deliberately excluded. A cancel is a user decision, and resuming it on
   * next launch would override that — the user would find a download they stopped running again.
   * [Status.COMPLETED] is excluded for the obvious reason, and re-retrying it would reset a
   * finished file.
   */
  fun idsToRetry(all: List<Download>): List<Int> = all.filter { it.status == Status.FAILED }.map { it.id }
}
