package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Serves the cu-16 Plex fixtures over localhost so the app can be driven on a
 * device or emulator with **no Plex account and no credentials**.
 *
 * Lives in the `debug` source set only, so it cannot reach a release build. It
 * reads the same JSON files the unit tests use (wired in as debug assets), so a
 * screen state seen here is the one the tests assert on.
 *
 * Uses MockWebServer rather than a second embedded HTTP server: it is already a
 * dependency, and sharing it keeps the debug routing and `FakePlexServer`
 * routing honest about being the same thing.
 */
class MockPlexServer(private val context: Context) {
  private val server = MockWebServer()

  /**
   * When true, `/:/timeline` answers 401 instead of 200.
   *
   * Exists so the "position not synced" badge can actually be seen: it only appears on a
   * *terminal* report failure, and a mock that always returns 200 can never produce one.
   * 401 rather than 500 because a 5xx is retried, so the work would sit in ENQUEUED and
   * never reach FAILED.
   */
  var failProgressReports: Boolean = false

  /** Base url to point PlexConfig at, valid once [start] returns. */
  lateinit var baseUrl: String
    private set

  fun start() {
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.path.orEmpty()
          // Log before any early return. This line used to sit below the photo
          // and audio branches, so those requests were served but never logged —
          // which made playback look like it was never fetching audio and cost a
          // full diagnostic run to disprove (cu-64).
          Timber.i("MockPlexServer: ${request.method} $path range=${request.headers["Range"]}")
          // Cover art: serve a real (solid-colour) PNG so image loading is
          // actually exercised end-to-end rather than always falling back to the
          // placeholder.
          if (path.startsWith("/photo/")) {
            return imageResponse()
          }
          // Audio: serve a generated tone so playback can actually be decoded and
          // rendered, not merely wired up (cu-64).
          if (path.startsWith("/library/parts/")) {
            return audioResponse(request.headers["Range"])
          }
          if (failProgressReports && path.startsWith("/:/timeline")) {
            Timber.i("MockPlexServer: -> 401 (failProgressReports)")
            return MockResponse().setResponseCode(401)
          }
          val fixture = fixtureFor(path)
          Timber.i("MockPlexServer: -> ${fixture ?: "(empty 200)"}")
          if (fixture == null) {
            return MockResponse().setResponseCode(200).setBody("")
          }
          return try {
            val body =
              context.assets.open("plex-fixtures/$fixture")
                .bufferedReader()
                .use { it.readText() }
            MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(body)
          } catch (e: Exception) {
            Timber.e(e, "MockPlexServer: missing fixture $fixture")
            MockResponse().setResponseCode(404)
          }
        }
      }
    // MockWebServer binds a socket and MockWebServer.url() does a reverse DNS
    // lookup, both of which Android forbids on the main thread. Do the work on a
    // background thread and join, so callers still get a usable baseUrl on return.
    var port = 0
    val worker =
      thread(name = "MockPlexServer") {
        server.start()
        port = server.port
      }
    worker.join()
    // Build the url by hand rather than calling server.url(), which resolves the
    // canonical hostname and would hit the network again.
    baseUrl = "http://127.0.0.1:$port"
    Timber.i("MockPlexServer listening on $baseUrl")
  }

  fun shutdown() = server.shutdown()

  /**
   * Serves the generated tone, honouring a single-range `Range` header.
   *
   * ExoPlayer issues range requests when it seeks, and treats a server that
   * ignores `Range` as non-seekable — so without this the fixture would exercise
   * only sequential playback and quietly hide seek bugs.
   */
  private fun audioResponse(rangeHeader: String?): MockResponse =
    try {
      val bytes = context.assets.open("plex-fixtures/track.wav").use { it.readBytes() }
      val range = parseRange(rangeHeader, bytes.size)
      if (range == null) {
        MockResponse()
          .setResponseCode(200)
          .setHeader("Content-Type", "audio/wav")
          .setHeader("Accept-Ranges", "bytes")
          .setHeader("Content-Length", bytes.size.toString())
          .setBody(okio.Buffer().write(bytes))
      } else {
        val (start, endInclusive) = range
        val slice = bytes.copyOfRange(start, endInclusive + 1)
        MockResponse()
          .setResponseCode(206)
          .setHeader("Content-Type", "audio/wav")
          .setHeader("Accept-Ranges", "bytes")
          .setHeader("Content-Range", "bytes $start-$endInclusive/${bytes.size}")
          .setHeader("Content-Length", slice.size.toString())
          .setBody(okio.Buffer().write(slice))
      }
    } catch (e: Exception) {
      Timber.e(e, "MockPlexServer: missing track.wav")
      MockResponse().setResponseCode(404)
    }

  /** Parses `bytes=start-[end]`; returns null for absent or unusable headers. */
  private fun parseRange(
    header: String?,
    size: Int,
  ): Pair<Int, Int>? {
    val spec = header?.removePrefix("bytes=")?.trim() ?: return null
    if (!spec.contains('-') || spec.contains(',')) return null
    val start = spec.substringBefore('-').toIntOrNull() ?: return null
    val end = spec.substringAfter('-').toIntOrNull() ?: (size - 1)
    if (start !in 0 until size) return null
    return start to end.coerceAtMost(size - 1)
  }

  private fun imageResponse(): MockResponse =
    try {
      val bytes = context.assets.open("plex-fixtures/cover.png").use { it.readBytes() }
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "image/png")
        .setBody(okio.Buffer().write(bytes))
    } catch (e: Exception) {
      Timber.e(e, "MockPlexServer: missing cover.png")
      MockResponse().setResponseCode(404)
    }

  /**
   * Routes a request path to a fixture. Ordering matters: the tracks route is a
   * suffix of the metadata route. Mirrors `FakePlexServer.routeFor` in the test
   * source set.
   */
  private fun fixtureFor(path: String): String? =
    when {
      path.startsWith("/library/sections") && path.contains("/all") -> "albums.json"
      path.startsWith("/library/sections") -> "libraries.json"
      path.contains("/children") -> "tracks.json"
      path.startsWith("/library/metadata") -> "track-with-chapters.json"
      path.startsWith("/library/collections") -> "collections.json"
      path.contains("/resources") -> "resources.json"
      path.contains("/home/users") -> "home-users.json"
      path.contains("/pins") -> "oauth-pin-granted.json"
      path.contains("/identity") -> "identity.json"
      // Progress reports and scrobbles return an empty 200 from a real server.
      else -> null
    }
}
