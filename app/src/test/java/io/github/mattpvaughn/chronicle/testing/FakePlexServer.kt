package io.github.mattpvaughn.chronicle.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.ExternalResource

/**
 * A stand-in Plex server backed by the JSON fixtures in `plex-fixtures/`.
 *
 * Tests that touch sync, progress or downloads need a server that answers a
 * *coherent set* of endpoints, not one canned body — asking for an album and
 * then its tracks has to return matching data or the test proves nothing. So
 * this dispatches by request path, the way a real server does.
 *
 * No real tokens, hostnames or account identifiers appear in any fixture; the
 * data is invented. See task cu-16.
 */
class FakePlexServer : ExternalResource() {
  private lateinit var server: MockWebServer

  /** Paths this server has been asked for, in order. */
  private val requested = mutableListOf<String>()

  /** Per-path overrides, so a test can inject a failure or an empty result. */
  private val overrides = mutableMapOf<String, MockResponse>()

  /** Base url to point [io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig] at. */
  val url: String
    get() = server.url("/").toString().trimEnd('/')

  val requestedPaths: List<String>
    get() = requested.toList()

  override fun before() {
    requested.clear()
    overrides.clear()
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.path.orEmpty()
          requested += path
          overrides.entries.firstOrNull { path.startsWith(it.key) }?.let { return it.value }
          if (path.startsWith("/library/parts/")) {
            return audioResponse(request.headers["Range"])
          }
          return routeFor(path)
        }
      }
    server.start()
  }

  override fun after() {
    server.shutdown()
  }

  /**
   * Forces every request whose path starts with [pathPrefix] to return [response],
   * so a test can exercise the failure branch it cares about.
   */
  fun stub(
    pathPrefix: String,
    response: MockResponse,
  ) {
    overrides[pathPrefix] = response
  }

  /** Convenience for the common "this endpoint is down" case. */
  fun stubFailure(
    pathPrefix: String,
    code: Int = 500,
  ) {
    stub(pathPrefix, MockResponse().setResponseCode(code))
  }

  /** Convenience for an expired-token response — the case cu-10 has to handle. */
  fun stubUnauthorized(pathPrefix: String) {
    stub(pathPrefix, MockResponse().setResponseCode(401))
  }

  /** The album fixture for a known book id, the track fixture otherwise. See [routeFor]. */
  private fun metadataFixtureFor(path: String): String {
    val id = path.removePrefix("/library/metadata/").substringBefore('/').substringBefore('?')
    return if (id in ALBUM_FIXTURE_IDS) "album-$id.json" else "track-with-chapters.json"
  }

  private fun routeFor(path: String): MockResponse =
    when {
      path.startsWith("/library/sections") && path.contains("/all") -> json("albums.json")
      path.startsWith("/library/sections") -> json("libraries.json")
      // Tracks for an album; must be checked before the bare metadata route.
      path.contains("/children") -> json("tracks.json")
      // `/library/metadata/<id>` serves **two** endpoints with identical query parameters:
      // `retrieveAlbum` and `retrieveChapterInfo`. Nothing in the request distinguishes them, so
      // route on the id. Answering the track fixture for both made `fetchBookAsync` receive
      // tracks for an album request, and `bookDao.update` is `@Insert(REPLACE)` — so a track was
      // inserted into the Audiobook table as a phantom book (cu-18).
      path.startsWith("/library/metadata") -> json(metadataFixtureFor(path))
      path.startsWith("/library/collections") -> json("collections.json")
      path.contains("/resources") -> json("resources.json")
      path.contains("/home/users") -> json("home-users.json")
      path.contains("/pins") -> json("oauth-pin-granted.json")
      path.contains("/identity") -> json("identity.json")
      // Progress reporting and scrobbles return an empty 200 from a real server.
      path.startsWith("/:/") -> MockResponse().setResponseCode(200).setBody("")
      else -> MockResponse().setResponseCode(404).setBody("""{"error":"no fixture for $path"}""")
    }

  /**
   * Serves the generated tone fixture, honouring a single-range `Range` header.
   *
   * ExoPlayer range-requests when it seeks and treats a server that ignores
   * `Range` as non-seekable, so the fixture server has to support it or seek
   * behaviour cannot be tested at all (cu-64).
   */
  fun audioResponse(rangeHeader: String? = null): MockResponse {
    val bytes = fixtureBytes("track.wav")
    val range = parseRange(rangeHeader, bytes.size)
    return if (range == null) {
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "audio/wav")
        .setHeader("Accept-Ranges", "bytes")
        .setBody(okio.Buffer().write(bytes))
    } else {
      val (start, endInclusive) = range
      val slice = bytes.copyOfRange(start, endInclusive + 1)
      MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Type", "audio/wav")
        .setHeader("Accept-Ranges", "bytes")
        .setHeader("Content-Range", "bytes $start-$endInclusive/${bytes.size}")
        .setBody(okio.Buffer().write(slice))
    }
  }

  /** Parses `bytes=start-[end]`; null for absent or unusable headers. */
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

  private fun json(fixture: String): MockResponse =
    MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Type", "application/json")
      .setBody(fixture(fixture))

  companion object {
    /** The book ids in `albums.json`; each has an `album-<id>.json` detail fixture. */
    val ALBUM_FIXTURE_IDS = setOf("1001", "1002", "1003")

    /** Reads a binary fixture from the test classpath. */
    fun fixtureBytes(name: String): ByteArray =
      FakePlexServer::class.java.classLoader
        ?.getResourceAsStream("plex-fixtures/$name")
        ?.use { it.readBytes() }
        ?: error("Missing fixture: plex-fixtures/$name")

    /** Reads a fixture from the test classpath. */
    fun fixture(name: String): String =
      FakePlexServer::class.java.classLoader
        ?.getResourceAsStream("plex-fixtures/$name")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Missing fixture: plex-fixtures/$name")
  }
}
