package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download engine's resume behaviour, which is where silent corruption would live.
 *
 * Downloads are the highest-risk area in this app: four separate tasks have failure modes that end
 * in *deleted audio*, and the household has real downloaded books. So these tests are about the
 * bytes on disk, not about the event stream — a download that reports success and leaves a corrupt
 * file is worse than one that reports failure.
 *
 * The case that matters most is **a server that ignores `Range`**. It answers `200` with the whole
 * file instead of `206` with the tail, and appending that to an existing partial splices the file's
 * beginning onto its own middle. The result is a track of entirely plausible length that plays as
 * garbage partway through — no error, no short file, nothing a size check would catch.
 */
class KtorDownloaderTest {
  /**
   * In-memory, not a temp directory.
   *
   * These tests are about what bytes end up in the file after a 200, a 206, a 416 and a server that
   * ignores `Range` — pure filesystem outcomes with no reason to touch disk. It also lets a partial
   * be staged at an exact length without writing one.
   */
  private val fs = FakeFileSystem()

  private val downloadDir = "/downloads".toPath().also { }

  private val requestedRanges = mutableListOf<String?>()

  /**
   * The downloader under test, on a **real** dispatcher.
   *
   * Not `runTest` + `advanceUntilIdle`, and this cost a round of debugging worth recording:
   * `runTest`'s virtual-time scheduler does not drive Ktor's `MockEngine`, so every test failed
   * with **zero** requests reaching the engine — which reads exactly like a broken downloader and
   * was entirely a broken harness. Reduced to a standalone probe to be sure: the same code under
   * `runBlocking` with a real dispatcher wrote its 10 bytes first time.
   *
   * So these tests use `runBlocking`, and wait by joining the downloader's own jobs via
   * [KtorDownloader.awaitIdle] rather than by advancing a clock. Downloads are I/O; a virtual
   * clock has nothing to advance.
   */
  private fun downloader(engine: MockEngine) =
    KtorDownloader(
      client = HttpClient(engine),
      dispatchers = RealDispatcherProvider,
      scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
      fileSystem = fs,
    )

  private fun trackPath(): Path {
    fs.createDirectories(downloadDir)
    return downloadDir / "track-1.mp3"
  }

  /** Records the `Range` header of every request, then answers with [handler]. */
  private fun engine(handler: (range: String?) -> Pair<HttpStatusCode, String>) =
    MockEngine { request ->
      val range = request.headers[HttpHeaders.Range]
      requestedRanges.add(range)
      val (status, body) = handler(range)
      if (status.value in 200..299) {
        respond(
          content = body,
          status = status,
          headers = headersOf(HttpHeaders.ContentLength, body.length.toString()),
        )
      } else {
        respondError(status)
      }
    }

  private fun request(dest: Path) =
    DownloadRequest(
      trackId = "track-1",
      bookId = "book-1",
      bookTitle = "A Book",
      url = "http://localhost/track-1.mp3",
      destinationPath = dest.toString(),
    )

  @Test
  fun `a fresh download writes the whole body and sends no Range`() =
    runBlocking {
      val dest = trackPath()
      val d = downloader(engine { HttpStatusCode.OK to WHOLE })

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()

      assertEquals(WHOLE, fs.read(dest) { readUtf8() })
      assertNull("a fresh download must not ask for a range", requestedRanges.single())
    }

  @Test
  fun `an existing partial is resumed with a Range header and appended to`() =
    runBlocking {
      val dest = trackPath()
      fs.write(dest) { writeUtf8(HEAD) }
      val d = downloader(engine { HttpStatusCode.PartialContent to TAIL })

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()

      assertEquals("bytes=${HEAD.length}-", requestedRanges.single())
      assertEquals(
        "a resumed download must append, not restart",
        WHOLE,
        fs.read(dest) { readUtf8() },
      )
    }

  /**
   * The truncate, isolated.
   *
   * The restart-not-splice test above passes with or without `resize(startAt)`, because there the
   * replacement body is *longer* than the partial and simply overwrites it — sabotaging the
   * truncate did not fail a single test, which is how this gap was found. The truncate only shows
   * when the new content is **shorter**: without it the tail of the old file survives past the end
   * of the new one, leaving a file that is too long and ends in stale bytes.
   */
  @Test
  fun `a restart shorter than the partial truncates the leftover tail`() =
    runBlocking {
      val dest = trackPath()
      // 10 bytes on disk; the server ignores the range and answers 200 with 5.
      fs.write(dest) { writeUtf8(WHOLE) }
      val d = downloader(engine { HttpStatusCode.OK to HEAD })

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()

      assertEquals(
        "the leftover tail of the longer partial must be truncated away",
        HEAD,
        fs.read(dest) { readUtf8() },
      )
      assertEquals(
        "and the file must be exactly the new length",
        HEAD.length.toLong(),
        fs.metadataOrNull(dest)?.size,
      )
    }

  @Test
  fun `a server that ignores Range causes a restart, not a splice`() =
    runBlocking {
      // The silent-corruption case. The partial is 5 bytes; the server ignores the range and
      // answers 200 with all 10. Appending would give 15 bytes of "HELLOHELLOWORLD" — right-ish
      // length, wrong contents, and nothing downstream would notice.
      val dest = trackPath()
      fs.write(dest) { writeUtf8(HEAD) }
      val d = downloader(engine { HttpStatusCode.OK to WHOLE })

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()

      assertEquals("bytes=${HEAD.length}-", requestedRanges.single())
      assertEquals(
        "a 200 answer to a ranged request must truncate and restart",
        WHOLE,
        fs.read(dest) { readUtf8() },
      )
      assertEquals(
        "the file must be exactly the body length, not partial + body",
        WHOLE.length.toLong(),
        (fs.metadataOrNull(dest)?.size ?: 0L),
      )
    }

  @Test
  fun `a 416 means the file is already complete`() =
    runBlocking {
      // The server says the requested range is past the end, so the local file is at or beyond the
      // full length. That is success, not an error — reporting it as failed would make a finished
      // book look broken and invite a re-download.
      val dest = trackPath()
      fs.write(dest) { writeUtf8(WHOLE) }
      val events = mutableListOf<DownloadEvent>()
      val d = downloader(engine { HttpStatusCode.RequestedRangeNotSatisfiable to "" })
      val collector = collectEvents(d, events)

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()
      awaitEvent(events, collector, "a 416 must report completion, not failure") {
        it is DownloadEvent.Completed
      }

      assertEquals("the complete file must be left untouched", WHOLE, fs.read(dest) { readUtf8() })
    }

  @Test
  fun `a failed download leaves its partial on disk so it can resume later`() =
    runBlocking {
      // Deliberate: deleting the partial on failure is how a flaky connection turns into an
      // infinite re-download of a 293 MB book. The bytes are the whole point of resuming.
      val dest = trackPath()
      fs.write(dest) { writeUtf8(HEAD) }
      val events = mutableListOf<DownloadEvent>()
      val d = downloader(engine { HttpStatusCode.InternalServerError to "" })
      val collector = collectEvents(d, events)

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()
      awaitEvent(events, collector, "the failure must be reported") { it is DownloadEvent.Failed }

      assertEquals("a failure must not discard the partial", HEAD, fs.read(dest) { readUtf8() })
    }

  @Test
  fun `enqueueing a track already in flight does not start a second writer`() =
    runBlocking {
      // Two coroutines appending to one file is how a download corrupts itself, and re-enqueueing
      // is easy to trigger — a retry on regained network over a queue that has not drained.
      val dest = trackPath()
      // The engine blocks until released, so the first download is *provably* still in flight when
      // the duplicate is enqueued. Without this the test raced its own subject: a job removes
      // itself from `jobs` when it finishes, so if the first completed first the map was empty, the
      // duplicate was not a duplicate, and two requests were made. It passed on a fast machine and
      // failed on a GitHub runner — the guard was fine; the test's premise was not.
      val inFlight = CompletableDeferred<Unit>()
      val d =
        downloader(
          MockEngine {
            requestedRanges += it.headers[HttpHeaders.Range]
            inFlight.await()
            respond(WHOLE, HttpStatusCode.OK)
          },
        )

      d.enqueue(listOf(request(dest)))
      // Both enqueues are done before anything can complete.
      d.enqueue(listOf(request(dest)))
      inFlight.complete(Unit)
      d.awaitIdle()

      assertEquals(
        "the duplicate enqueue must be ignored, so exactly one request is made",
        1,
        requestedRanges.size,
      )
      assertEquals(WHOLE, fs.read(dest) { readUtf8() })
    }

  @Test
  fun `a completed download reports the track and book it finished`() =
    runBlocking {
      val dest = trackPath()
      val events = mutableListOf<DownloadEvent>()
      val d = downloader(engine { HttpStatusCode.OK to WHOLE })
      val collector = collectEvents(d, events)

      d.enqueue(listOf(request(dest)))
      d.awaitIdle()
      awaitEvent(events, collector, "the completion must be reported") {
        it is DownloadEvent.Completed
      }

      val completed = events.filterIsInstance<DownloadEvent.Completed>().single()
      assertEquals("track-1", completed.trackId)
      // The *real* book id, not a hash. Fetch2's Int-only group API forced the id through a hash
      // and carried the real one in an extras map; nothing here needs that workaround.
      assertEquals("book-1", completed.bookId)
    }

  /** Subscribes before anything is enqueued; the event flow has no replay. */
  private fun CoroutineScope.collectEvents(
    d: Downloader,
    into: MutableList<DownloadEvent>,
  ) = launch { d.events.collect { into.add(it) } }

  /**
   * Waits for an event matching [predicate], then cancels [collector].
   *
   * **`awaitIdle()` alone is not enough, and CI proved it.** It joins the download *job*, but the
   * event reaches `into` on a separate collector coroutine — so on a loaded machine the job can
   * finish, the assertion can read an empty list, and the emission can arrive afterwards. Two tests
   * failed exactly that way on a GitHub runner while passing on a developer machine every time.
   *
   * Polling with a timeout rather than sleeping: it returns the moment the event lands, so the fast
   * path costs nothing, and it fails with the collected events rather than a bare assertion.
   */
  private suspend fun awaitEvent(
    events: List<DownloadEvent>,
    collector: Job,
    what: String,
    predicate: (DownloadEvent) -> Boolean,
  ) {
    withTimeoutOrNull(EVENT_TIMEOUT_MS) {
      while (events.none(predicate)) {
        yield()
      }
    }
    collector.cancel()
    assertTrue("$what — collected: $events", events.any(predicate))
  }

  /** A [DispatcherProvider] over real dispatchers, since this exercises real file I/O. */
  private object RealDispatcherProvider : DispatcherProvider {
    override val io = Dispatchers.IO
    override val main = Dispatchers.Default
    override val default = Dispatchers.Default
  }

  private companion object {
    /**
     * Generous on purpose. `awaitEvent` returns the instant the event lands, so a high ceiling
     * costs a fast machine nothing and stops a loaded CI runner from failing for being slow.
     */
    const val EVENT_TIMEOUT_MS = 5_000L
    const val HEAD = "HELLO"
    const val TAIL = "WORLD"
    const val WHOLE = "HELLOWORLD"
  }
}
