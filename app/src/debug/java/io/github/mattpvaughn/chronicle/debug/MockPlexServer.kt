package io.github.mattpvaughn.chronicle.debug

import android.content.Context
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import timber.log.Timber
import java.net.InetAddress
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
    var failure: Throwable? = null
    val worker =
      thread(name = "MockPlexServer") {
        runCatching {
          // Bind an explicit loopback address rather than the no-arg start(), which resolves the
          // hostname "localhost" — an AOSP emulator image has no DNS entry for it and the lookup
          // throws UnknownHostException. On a background thread that killed the whole process with
          // an empty crash buffer, which is a miserable thing to debug (cu-54).
          server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
          port = server.port
        }.onFailure { failure = it }
      }
    worker.join()
    failure?.let {
      // Loud, not silent: every fixture-backed request fails without the server, and a null
      // baseUrl surfaces far away from the cause.
      throw IllegalStateException("MockPlexServer failed to start", it)
    }
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
   * The album fixture for a book id, the track fixture for anything else.
   *
   * Book ids in this fixture pack are `100x` and track ids `200x`, so the prefix is enough — and
   * an unknown id falls through to the track fixture, which is what `retrieveChapterInfo` asks
   * for and the only caller that passes an id this does not know.
   */
  private fun metadataFixtureFor(path: String): String {
    val id = path.removePrefix("/library/metadata/").substringBefore('/').substringBefore('?')
    return when {
      id in ALBUM_FIXTURE_IDS -> "album-$id.json"
      id in TRACK_FIXTURE_IDS -> "track-$id-chapters.json"
      else -> "track-with-chapters.json"
    }
  }

  /**
   * Routes a request path to a fixture. Ordering matters: the tracks route is a
   * suffix of the metadata route. Mirrors `FakePlexServer.routeFor` in the test
   * source set.
   */
  private fun fixtureFor(path: String): String? =
    when {
      // The tag-filter surface (cu-143), mirroring FakePlexServer. Must precede the `/all` and
      // bare-section rules: `/library/sections/1/style` contains neither, so it would otherwise
      // read as a library list.
      path.contains("style=") -> "albums-style-${path.substringAfter("style=").substringBefore("&")}.json"
      path.contains("mood=") -> "albums-mood-${path.substringAfter("mood=").substringBefore("&")}.json"
      path.startsWith("/library/sections") && path.contains("/style") -> "filter-style.json"
      path.startsWith("/library/sections") && path.contains("/mood") -> "filter-mood.json"
      path.startsWith("/library/sections") && path.contains("/all") -> "albums.json"
      path.startsWith("/library/sections") -> "libraries.json"
      path.contains("/children") -> "tracks.json"
      // `/library/metadata/<id>` is used by **two** endpoints with identical query parameters:
      // `retrieveAlbum` (which wants the album) and `retrieveChapterInfo` (which wants the track
      // and its chapters). Nothing in the request distinguishes them, so route on the id.
      //
      // This used to answer `track-with-chapters.json` for both, so `fetchBookAsync` received
      // tracks for an album request — and `bookDao.update` is `@Insert(REPLACE)`, so a track was
      // *inserted* into the Audiobook table and appeared on the home shelves as a phantom book
      // (cu-18, seen on a device).
      // A book id gets its album; a **track** id gets that track's own chapters. Both halves matter:
      // cu-18 fixed the album half, and the track half was still one file holding all three tracks
      // — and the app reads `metadata.firstOrNull()`, so every track received *track 2001's*
      // chapters. The player then read "Ch 1 of 9" for a 7-chapter book, each chapter tripled
      // (cu-19).
      // `/library/metadata/<id>` serves **two** endpoints with identical query parameters:
      // `retrieveAlbum` (which wants the album) and `retrieveChapterInfo` (which wants the track
      // and its chapters). Nothing in the request distinguishes them, so route on the id.
      //
      // Both halves of this were wrong. Answering `track-with-chapters.json` for an *album*
      // request meant `fetchBookAsync` received tracks, and `bookDao.update` is
      // `@Insert(REPLACE)`, so a track was inserted into the Audiobook table and showed on the
      // home shelves as a phantom book (cu-18). And one chapter fixture holding all three tracks
      // meant every track got *track 2001's* chapters, since the app reads
      // `metadata.firstOrNull()` — the player read "Ch 1 of 9" for a 7-chapter book (cu-19).
      path.startsWith("/library/metadata") -> metadataFixtureFor(path)
      path.startsWith("/library/collections") -> "collections.json"
      path.contains("/resources") -> "resources.json"
      path.contains("/home/users") -> "home-users.json"
      path.contains("/pins") -> "oauth-pin-granted.json"
      path.contains("/identity") -> "identity.json"
      // Progress reports and scrobbles return an empty 200 from a real server.
      else -> null
    }

  private companion object {
    /** The book ids in `albums.json`; each has an `album-<id>.json` detail fixture. */
    val ALBUM_FIXTURE_IDS = setOf("1001", "1002", "1003")

    /** The track ids in `tracks.json`; each has a `track-<id>-chapters.json` fixture. */
    val TRACK_FIXTURE_IDS = setOf("2001", "2002", "2003")
  }
}
