package io.github.mattpvaughn.chronicle.testing

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the audio fixture is served as a real, seekable stream (cu-64).
 *
 * Playback is the app's core behaviour and was previously unverifiable: the mock
 * served JSON and cover art only, so a player could be constructed but never fed.
 * These tests pin the transport contract ExoPlayer depends on — a correct WAV
 * body, `Accept-Ranges`, and honest 206 responses — so a regression in the
 * fixture server fails here rather than silently making every playback check
 * vacuous.
 */
class AudioFixtureTest {
  @get:Rule
  val plex = FakePlexServer()

  private val client = OkHttpClient()

  private fun get(
    path: String,
    range: String? = null,
  ) = client.newCall(
    Request.Builder()
      .url("${plex.url}$path")
      .apply { range?.let { header("Range", it) } }
      .build(),
  ).execute()

  @Test
  fun `a track part is served as a complete wav stream`() {
    get("/library/parts/3001/1600000000/file.wav").use { response ->
      assertEquals(200, response.code)
      assertEquals("audio/wav", response.header("Content-Type"))
      val body = response.body!!.bytes()
      assertTrue("body should be a non-trivial audio payload", body.size > 100_000)
      // RIFF/WAVE magic: proves a decodable container, not an error page that
      // happens to have the right content type.
      assertEquals("RIFF", String(body, 0, 4))
      assertEquals("WAVE", String(body, 8, 4))
    }
  }

  @Test
  fun `the server advertises range support`() {
    // ExoPlayer treats a server without Accept-Ranges as non-seekable, which
    // would silently disable seek testing rather than failing.
    get("/library/parts/3001/1600000000/file.wav").use { response ->
      assertEquals("bytes", response.header("Accept-Ranges"))
    }
  }

  @Test
  fun `a range request returns exactly the requested slice`() {
    get("/library/parts/3001/1600000000/file.wav", range = "bytes=100-199").use { response ->
      assertEquals(206, response.code)
      val body = response.body!!.bytes()
      assertEquals("a 100-199 range is 100 bytes inclusive", 100, body.size)
      assertTrue(
        "Content-Range must report the slice and total size",
        response.header("Content-Range")!!.startsWith("bytes 100-199/"),
      )
    }
  }

  @Test
  fun `an open-ended range runs to the end of the file`() {
    val full = get("/library/parts/3001/1600000000/file.wav").use { it.body!!.bytes().size }
    get("/library/parts/3001/1600000000/file.wav", range = "bytes=1000-").use { response ->
      assertEquals(206, response.code)
      assertEquals(full - 1000, response.body!!.bytes().size)
    }
  }

  @Test
  fun `an out-of-bounds range falls back to the whole file rather than erroring`() {
    val full = get("/library/parts/3001/1600000000/file.wav").use { it.body!!.bytes().size }
    get("/library/parts/3001/1600000000/file.wav", range = "bytes=999999999-").use { response ->
      assertEquals(200, response.code)
      assertEquals(full, response.body!!.bytes().size)
    }
  }

  @Test
  fun `every track part path serves audio, not just the first`() {
    // The fixture book has three tracks; track transitions are where position
    // loss historically occurred, so all of them must be playable.
    for (part in listOf("3001", "3002", "3003")) {
      get("/library/parts/$part/1600000000/file.wav").use { response ->
        assertEquals("part $part should serve audio", 200, response.code)
        assertEquals("RIFF", String(response.body!!.bytes(), 0, 4))
      }
    }
  }
}
