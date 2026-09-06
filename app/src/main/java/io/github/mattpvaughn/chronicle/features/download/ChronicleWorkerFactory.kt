package io.github.mattpvaughn.chronicle.features.download

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.tonyodev.fetch2.Fetch
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import java.io.File

/**
 * Builds the app's workers with their dependencies **passed in** rather than fetched (cu-179).
 *
 * WorkManager instantiates a `CoroutineWorker` reflectively through a fixed
 * `(Context, WorkerParameters)` constructor, so a worker that needs anything else has historically
 * reached for the service locator in a **field initialiser**:
 *
 * ```kotlin
 * private val fetch: Fetch = Injector.get().fetch()          // DownloadNotificationWorker
 * private val prefsRepo = Injector.get().prefsRepo()         // MoveSyncLocationWorker
 * ```
 *
 * That makes the worker unconstructable in a unit test at all — `Injector.get()` is
 * `ChronicleApplication.get()`, whose `INSTANCE!!` throws before the constructor finishes. The two
 * workers are **2,212 missed instructions**, `DownloadNotificationWorker` alone 1,420 of them at 0%.
 *
 * ## This revisits cu-152 deliberately
 *
 * cu-152 exempted workers from `DispatcherProvider` injection, reasoning that this plumbing "would
 * buy nothing while no worker is unit-tested and `TestListenableWorkerBuilder` supplies its own
 * executor anyway." That was correct when written. **The premise has since changed**:
 * `androidx.work:work-testing` is already declared in the build and entirely unused, and the
 * workers are now among the largest untested bodies left. The exemption is not being overturned as
 * a mistake — it is being re-decided on new facts, and `WorkerDispatcherTest` still pins the
 * dispatcher rule.
 *
 * A worker not listed here falls through to WorkManager's default behaviour (returning null lets
 * the framework use the reflective constructor), so adding a worker without touching this factory
 * still works.
 */
class ChronicleWorkerFactory(
  private val fetch: () -> Fetch,
  private val prefsRepo: PrefsRepo,
  private val externalDeviceDirs: () -> List<File>,
  private val trackRepository: () -> ITrackRepository,
  private val bookRepository: () -> IBookRepository,
  private val plexPrefs: () -> PlexPrefsRepo,
  private val plexMediaService: () -> PlexMediaService,
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? =
    when (workerClassName) {
      DownloadNotificationWorker::class.java.name ->
        DownloadNotificationWorker(appContext, workerParameters, fetch())

      MoveSyncLocationWorker::class.java.name ->
        MoveSyncLocationWorker(appContext, workerParameters, prefsRepo, externalDeviceDirs())

      PlexSyncScrobbleWorker::class.java.name ->
        PlexSyncScrobbleWorker(
          appContext,
          workerParameters,
          trackRepository(),
          bookRepository(),
          plexPrefs(),
          plexMediaService(),
        )

      // Not ours to build: let WorkManager use the reflective constructor.
      else -> null
    }
}
