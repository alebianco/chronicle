package io.github.mattpvaughn.chronicle.testing

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.jensklingenberg.ktorfit.Ktorfit
import io.github.mattpvaughn.chronicle.data.sources.plex.MoshiContentConverter
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.createPlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asTrackList
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Drives [FakePlexServer] through the real Ktorfit/Moshi stack the app uses.
 *
 * The contract tests prove the fixtures parse; this proves the *server* answers
 * the endpoints [PlexMediaService] actually calls, with bodies those calls can
 * deserialize. Together they mean a test can ask for a library and get a
 * coherent answer without a live Plex server or any credentials (D10).
 */
class FakePlexServerTest {
  @get:Rule
  val plex = FakePlexServer()

  private val service: PlexMediaService by lazy {
    Ktorfit.Builder()
      .baseUrl(plex.url, checkUrl = false)
      .httpClient(
        HttpClient(OkHttp) {
          // The fake returns real HTTP statuses and the tests read them, so a non-2xx must throw
          // exactly as it does in production.
          expectSuccess = true
          install(ContentNegotiation) {
            register(
              ContentType.Application.Json,
              MoshiContentConverter(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            )
          }
        },
      )
      .build()
      .createPlexMediaService()
  }

  @Test
  fun `retrieveAllAlbums returns the fixture library`() =
    runBlocking {
      val books = service.retrieveAllAlbums("1").plexMediaContainer.asAudiobooks()

      assertEquals(3, books.size)
      assertTrue(books.any { it.title == "The Hobbit" })
    }

  @Test
  fun `retrieveTracksForAlbum returns tracks for that album`() =
    runBlocking {
      val tracks = service.retrieveTracksForAlbum("1001").plexMediaContainer.asTrackList()

      assertEquals(3, tracks.size)
      assertEquals("An Unexpected Party", tracks.first().title)
    }

  @Test
  fun `album and track requests are routed to different fixtures`() =
    runBlocking {
      // The album route and the tracks route differ only by a /children suffix.
      // If the dispatcher got that ordering wrong, both would return the same
      // body and every sync test built on this would be meaningless.
      val books = service.retrieveAllAlbums("1").plexMediaContainer.asAudiobooks()
      val tracks = service.retrieveTracksForAlbum("1001").plexMediaContainer.asTrackList()

      assertTrue(books.isNotEmpty())
      assertTrue(tracks.isNotEmpty())
      assertEquals("The Hobbit", books.first { it.id == "1001" }.title)
      assertEquals("1001", tracks.first().parentKey)
    }

  @Test
  fun `a stubbed failure surfaces to the caller`() =
    runBlocking {
      plex.stubFailure("/library/sections")

      val threw =
        try {
          service.retrieveAllAlbums("1")
          false
        } catch (e: Exception) {
          true
        }

      assertTrue("a 500 from the server must not be swallowed", threw)
    }

  @Test
  fun `an unauthorized response is distinguishable`() =
    runBlocking {
      // Token re-auth has to tell "token expired" apart from "server down"; this is the
      // hook that lets it be tested without an expired real token.
      plex.stubUnauthorized("/library/sections")

      val code =
        try {
          service.retrieveAllAlbums("1")
          0
        } catch (e: io.ktor.client.plugins.ResponseException) {
          e.response.status.value
        }

      assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, code)
    }

  @Test
  fun `requests are recorded for assertions`() =
    runBlocking {
      service.retrieveAllAlbums("1")

      assertTrue(
        "the library request should be recorded",
        plex.requestedPaths.any { it.startsWith("/library/sections/1/all") },
      )
    }
}
