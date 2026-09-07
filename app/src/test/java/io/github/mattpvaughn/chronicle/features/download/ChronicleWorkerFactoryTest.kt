package io.github.mattpvaughn.chronicle.features.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import io.mockk.mockk
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The workers are constructible in a unit test — the actual goal.
 *
 * **The factory-contract half of this suite is retired, not lost**. It asserted that
 * `ChronicleWorkerFactory` built each of ours and *returned null for anything else*, so WorkManager
 * would fall back to its reflective constructor for a worker added later. That factory is deleted:
 * each worker is `@HiltWorker` with an ordinary `@Inject` constructor, and `HiltWorkerFactory`
 * does the dispatch — including the fallback, which is now the framework's behaviour rather than
 * ours to get right. A guard kept past the code it guards is noise.
 *
 * What is still worth pinning is the property the Hilt migration was really after. Before it,
 * both workers reached `Injector.get()` in **field initialisers**, and `Injector.get()` is
 * `ChronicleApplication.get()`, whose `INSTANCE!!` throws before the constructor finishes — so the
 * classes could not be instantiated in a test **at all**. They are 2,212 missed instructions,
 * `DownloadNotificationWorker` alone 1,420. If a worker ever regains a graph-reading field
 * initialiser, these constructions throw again.
 */
@RunWith(RobolectricTestRunner::class)
class ChronicleWorkerFactoryTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val params = mockk<WorkerParameters>(relaxed = true)

  @Test
  fun `the download notification worker constructs with its dependencies passed in`() {
    val worker =
      DownloadNotificationWorker(
        context = context,
        parameters = params,
        downloader = mockk<Downloader>(relaxed = true),
      )

    assertNotNull(worker)
  }

  @Test
  fun `the sync-location worker constructs with its dependencies passed in`() {
    val worker =
      MoveSyncLocationWorker(
        context = context,
        parameters = params,
        prefsRepo = mockk<PrefsRepo>(relaxed = true),
        externalDeviceDirs = listOf(File("/storage/emulated/0/Android/data/files")),
        fileSystem = FakeFileSystem(),
      )

    assertNotNull(worker)
  }

  @Test
  fun `the scrobble worker constructs with its dependencies passed in`() {
    val worker =
      PlexSyncScrobbleWorker(
        context = context,
        workerParameters = params,
        trackRepository = mockk<ITrackRepository>(relaxed = true),
        bookRepository = mockk<IBookRepository>(relaxed = true),
        plexPrefs = mockk<PlexPrefsRepo>(relaxed = true),
        plexMediaService = mockk<PlexMediaService>(relaxed = true),
      )

    assertNotNull(worker)
  }
}
