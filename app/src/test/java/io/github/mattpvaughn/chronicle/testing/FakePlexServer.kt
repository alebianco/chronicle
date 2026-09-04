package io.github.mattpvaughn.chronicle.testing

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
          val path = request.target
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
    server.close()
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
    stub(pathPrefix, MockResponse(code = code))
  }

  /** Convenience for an expired-token response — the case cu-10 has to handle. */
  fun stubUnauthorized(pathPrefix: String) {
    stub(pathPrefix, MockResponse(code = 401))
  }

  /** The album fixture for a known book id, the track fixture otherwise. See [routeFor]. */
  private fun metadataFixtureFor(path: String): String {
    val id = path.removePrefix("/library/metadata/").substringBefore('/').substringBefore('?')
    return when {
      id in ALBUM_FIXTURE_IDS -> "album-$id.json"
      id in TRACK_FIXTURE_IDS -> "track-$id-chapters.json"
      else -> "track-with-chapters.json"
    }
  }

  /**
   * The multi-id metadata response, filtered to the ids actually asked for (cu-156).
   *
   * Filtering matters rather than being pedantry: a router that answers the whole captured
   * fixture whatever was requested makes Route B look like it *succeeded* for a library it knows
   * nothing about, so the Route A fallback never fires and a real fallback bug would pass every
   * test. That is the cu-18/cu-143 mis-routing trap in a third place, and it bit here: cu-143's
   * refresh tests went red until this filtered.
   *
   * Scanned as text rather than with `org.json`, which is an unimplemented stub in a plain JVM
   * unit test — it throws, the dispatcher never answers, and the failure surfaces as a socket
   * read timeout rather than as a parse error.
   */
  private fun multiIdResponse(path: String): MockResponse {
    val requestedIds =
      path.substringAfter("/library/metadata/").substringBefore('?').split(',')
        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val body = fixture("multi-id-real-shape.json")
    val items = splitTopLevelObjects(body.substringAfter("\"Metadata\"").substringAfter('['))
    val kept =
      items.filter { item ->
        val id = item.substringAfter("\"ratingKey\":").substringAfter('"').substringBefore('"')
        id in requestedIds
      }
    val json =
      "{\"MediaContainer\":{\"size\":" + kept.size +
        ",\"identifier\":\"com.plexapp.plugins.library\",\"Metadata\":[" +
        kept.joinToString(",") + "]}}"
    return MockResponse.Builder()
      .code(200)
      .setHeader("Content-Type", "application/json")
      .body(json)
      .build()
  }

  /** Splits a JSON array body into its top-level objects by brace depth. */
  private fun splitTopLevelObjects(arrayBody: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    for ((i, c) in arrayBody.withIndex()) {
      when {
        escaped -> escaped = false
        c == '\\' && inString -> escaped = true
        c == '"' -> inString = !inString
        inString -> Unit
        c == '{' -> {
          if (depth == 0) start = i
          depth++
        }
        c == '}' -> {
          depth--
          if (depth == 0 && start >= 0) {
            out += arrayBody.substring(start, i + 1)
            start = -1
          }
        }
        c == ']' && depth == 0 -> return out
      }
    }
    return out
  }

  private fun routeFor(path: String): MockResponse =
    when {
      // The tag-filter surface (cu-143). Order matters against the `/all` and bare-section rules
      // below: `/library/sections/1/style` contains neither "/all" nor a query, so without these
      // it would fall through to `libraries.json` and the seeder would read a library list as a
      // list of narrators.
      path.contains("style=") -> json("albums-style-${path.substringAfter("style=").substringBefore("&")}.json")
      path.contains("mood=") -> json("albums-mood-${path.substringAfter("mood=").substringBefore("&")}.json")
      path.startsWith("/library/sections") && path.contains("/style") -> json("filter-style.json")
      path.startsWith("/library/sections") && path.contains("/mood") -> json("filter-mood.json")
      path.startsWith("/library/sections") && path.contains("/all") -> json("albums.json")
      path.startsWith("/library/sections") -> json("libraries.json")
      // The multi-id metadata route (cu-156). Must precede the single-id rule below: a
      // comma-joined path would otherwise have its first id parsed out and answer one album, or
      // fall through to the track fixture — the same class of silent mis-routing that made a
      // track appear as a phantom book (cu-18) and made the tag seeder read a library list as a
      // list of narrators (cu-143). The fixture is captured from a real server.
      path.startsWith("/library/metadata/") && path.substringAfter("/library/metadata/").contains(',') ->
        multiIdResponse(path)
      // Tracks for an album; must be checked before the bare metadata route.
      path.contains("/children") -> json("tracks.json")
      // `/library/metadata/<id>` serves **two** endpoints with identical query parameters:
      // `retrieveAlbum` and `retrieveChapterInfo`. Nothing in the request distinguishes them, so
      // route on the id. Answering the track fixture for both made `fetchBookAsync` receive
      // tracks for an album request, and `bookDao.update` is `@Insert(REPLACE)` — so a track was
      // inserted into the Audiobook table as a phantom book (cu-18).
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
      path.startsWith("/library/metadata") -> json(metadataFixtureFor(path))
      path.startsWith("/library/collections") -> json("collections.json")
      path.contains("/resources") -> json("resources.json")
      path.contains("/home/users") -> json("home-users.json")
      path.contains("/pins") -> json("oauth-pin-granted.json")
      path.contains("/identity") -> json("identity.json")
      // Progress reporting and scrobbles return an empty 200 from a real server.
      path.startsWith("/:/") -> MockResponse(code = 200, body = "")
      else -> MockResponse(code = 404, body = """{"error":"no fixture for $path"}""")
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
      MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "audio/wav")
        .setHeader("Accept-Ranges", "bytes")
        .body(okio.Buffer().write(bytes))
        .build()
    } else {
      val (start, endInclusive) = range
      val slice = bytes.copyOfRange(start, endInclusive + 1)
      MockResponse.Builder()
        .code(206)
        .setHeader("Content-Type", "audio/wav")
        .setHeader("Accept-Ranges", "bytes")
        .setHeader("Content-Range", "bytes $start-$endInclusive/${bytes.size}")
        .body(okio.Buffer().write(slice))
        .build()
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
    MockResponse.Builder()
      .code(200)
      .setHeader("Content-Type", "application/json")
      .body(fixture(fixture))
      .build()

  companion object {
    /** The book ids in `albums.json`; each has an `album-<id>.json` detail fixture. */
    val ALBUM_FIXTURE_IDS = setOf("1001", "1002", "1003")

    /** The track ids in `tracks.json`; each has a `track-<id>-chapters.json` fixture. */
    val TRACK_FIXTURE_IDS = setOf("2001", "2002", "2003")

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
