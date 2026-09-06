package io.github.mattpvaughn.chronicle.features.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.tonyodev.fetch2.Fetch
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [ChronicleWorkerFactory] — the seam that makes the app's workers constructible at all (cu-179).
 *
 * WorkManager builds a `CoroutineWorker` reflectively through a fixed
 * `(Context, WorkerParameters)` constructor, so both workers used to reach for `Injector.get()` in
 * **field initialisers**. That is not merely awkward to test: `Injector.get()` is
 * `ChronicleApplication.get()`, whose `INSTANCE!!` throws before the constructor finishes, so the
 * classes could not be instantiated in a unit test **at all**. They are 2,212 missed instructions,
 * `DownloadNotificationWorker` alone 1,420 of them.
 *
 * This suite asserts the factory's contract rather than the workers' behaviour: that it builds
 * each of ours with the dependencies it was given, and — the part that is easy to get wrong —
 * that it **returns null for anything else** so WorkManager falls back to its reflective
 * constructor. A factory that threw or returned a wrong worker there would break every future
 * worker added without touching this file.
 */
@RunWith(RobolectricTestRunner::class)
class ChronicleWorkerFactoryTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val fetch = mockk<Fetch>(relaxed = true)
  private val prefsRepo = mockk<PrefsRepo>(relaxed = true)
  private val dirs = listOf(File("/storage/emulated/0/Android/data/files"))

  private var fetchBuilds = 0

  private fun factory() =
    ChronicleWorkerFactory(
      fetch = {
        fetchBuilds++
        fetch
      },
      prefsRepo = prefsRepo,
      externalDeviceDirs = { dirs },
      trackRepository = { mockk(relaxed = true) },
      bookRepository = { mockk(relaxed = true) },
      plexPrefs = { mockk(relaxed = true) },
      plexMediaService = { mockk(relaxed = true) },
    )

  private fun params() = mockk<WorkerParameters>(relaxed = true)

  @Test
  fun `it builds the download notification worker`() {
    val worker =
      factory().createWorker(
        context,
        DownloadNotificationWorker::class.java.name,
        params(),
      )

    assertNotNull("the factory must build our own worker", worker)
    assertEquals(DownloadNotificationWorker::class.java, worker!!.javaClass)
  }

  @Test
  fun `it builds the sync-location worker`() {
    val worker =
      factory().createWorker(
        context,
        MoveSyncLocationWorker::class.java.name,
        params(),
      )

    assertNotNull(worker)
    assertEquals(MoveSyncLocationWorker::class.java, worker!!.javaClass)
  }

  /**
   * The contract that keeps this factory from becoming a bottleneck: a worker it does not know
   * about is **not its problem**. Returning null lets WorkManager use the reflective constructor,
   * so a worker added later without touching this file still runs.
   */
  @Test
  fun `it declines to build a worker it does not own`() {
    val worker =
      factory().createWorker(
        context,
        "com.example.SomeOtherWorker",
        params(),
      )

    assertNull("an unknown worker must fall through to WorkManager, not throw", worker)
  }

  /**
   * `fetch` is passed as a lambda, not a value, because resolving it eagerly would read the Dagger
   * graph at factory-construction time — which on a cold start is before the graph exists. This
   * asserts the laziness holds: building an *unrelated* worker must not touch it.
   */
  @Test
  fun `the fetch dependency is resolved only when a worker needs it`() {
    val factory = factory()
    assertEquals("constructing the factory must not resolve fetch", 0, fetchBuilds)

    factory.createWorker(context, MoveSyncLocationWorker::class.java.name, params())
    assertEquals("the sync worker does not use fetch", 0, fetchBuilds)

    factory.createWorker(context, DownloadNotificationWorker::class.java.name, params())
    assertEquals("the download worker does", 1, fetchBuilds)
  }

  /**
   * The third worker, registered in the cu-178 follow-up.
   *
   * cu-179 built this factory and wired **two** of the three workers through it;
   * `PlexSyncScrobbleWorker` kept three `Injector.get()` field initialisers and so stayed
   * unconstructable — a half-finished migration that the service-locator guard could not see,
   * because the file was still on its exemption list and the list was therefore still accurate.
   */
  @Test
  fun `it builds the plex scrobble worker`() {
    val worker =
      factory().createWorker(
        context,
        PlexSyncScrobbleWorker::class.java.name,
        params(),
      )

    assertNotNull("the factory must build the scrobble worker", worker)
    assertEquals(PlexSyncScrobbleWorker::class.java, worker!!.javaClass)
  }
}
