package io.github.mattpvaughn.chronicle.injection

import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ktor download client never logs response bodies (issue #83), and never times out mid-book.
 *
 * The Ktor counterpart of [DownloadLogLevelTest]. That test guards the same property on the OkHttp
 * client, and its reasoning transfers exactly: `Logging` at [LogLevel.BODY] buffers a whole
 * response in memory in order to log it, and a download's body is the entire audiobook. A 293 MB
 * m4b drove the process from 248 MB to 350 MB PSS and then died with `OutOfMemoryError`, having
 * written **zero** bytes to disk.
 *
 * The mechanism is why this is worth a test. An earlier profiling pass looked for the OOM in app
 * code and in the download library and correctly found none — the defect lived in the *seam*, in
 * the client the library was handed. Nothing about either side in isolation reveals it, and no unit
 * test can observe the OOM itself.
 *
 * **Asserted behaviourally rather than by reading plugin config.** Ktor exposes no public way to
 * read an installed plugin's configuration back off a client, and inventing a reflection helper
 * would pin the framework's internals rather than the property. So each test drives a real request
 * through the real provider and observes what the client *does*: what it logs, and whether a slow
 * response survives. That is the stronger claim in any case — `DownloadLogLevelTest` can inspect
 * OkHttp's interceptor list, but it still only proves the logger is *present*, not that a body
 * never reaches it.
 *
 * The Ktor port adds a **second** trap of the same shape. `HttpClient.config {}` copies the source
 * client's plugin configuration, so the media client's `requestTimeoutMillis` would be inherited by
 * the downloader and abort any download longer than the read timeout — a silent mid-audiobook
 * failure. Pinned here too.
 */
class KtorDownloadClientTest {
  private val module = AppModule

  private val recorded = mutableListOf<String>()

  private fun recordingLogger() =
    object : Logger {
      override fun log(message: String) {
        recorded.add(message)
      }
    }

  /**
   * A stand-in for the media client: BODY logging and a short request timeout, which are the two
   * things the downloader must *not* inherit.
   */
  private fun mediaClientWithBodyLogging(respondSlowly: Boolean = false): HttpClient =
    HttpClient(
      MockEngine {
        if (respondSlowly) delay(30_000)
        respond(BODY_MARKER, HttpStatusCode.OK)
      },
    ) {
      install(Logging) {
        level = LogLevel.BODY
        logger = recordingLogger()
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
        connectTimeoutMillis = 5_000
      }
    }

  @Test
  fun `the download client never logs the response body`() =
    runTest {
      val downloader = module.downloaderKtorClient(mediaClientWithBodyLogging())

      val body = downloader.get("http://localhost/track.mp3").bodyAsText()

      assertEquals("the body must still arrive intact", BODY_MARKER, body)
      assertFalse(
        "the response body reached the log — this is issue #83: ${recorded.joinToString(" | ")}",
        recorded.any { it.contains(BODY_MARKER) },
      )
    }

  @Test
  fun `the download log level is HEADERS when logging is on, never BODY`() {
    // `LOG_NETWORK_REQUESTS` is `BuildConfig.DEBUG`, which is **false** under unit test, so the
    // client built here logs nothing at all and "does it log headers?" cannot be asked of it.
    // Writing this test through the provider is what exposed that: the first version asserted
    // headers were logged, failed, and revealed that the redaction test above was passing
    // vacuously against a client with no logger installed.
    //
    // So the level *choice* is pinned here as data, and the body-never-logged property is pinned
    // behaviourally above. NONE is safe but useless; BODY is issue #83. HEADERS is the only
    // correct answer when logging is on.
    assertEquals(
      "a download must log headers, never bodies",
      LogLevel.HEADERS,
      downloadLogLevel(loggingEnabled = true),
    )
    assertEquals(
      "release builds log nothing",
      LogLevel.NONE,
      downloadLogLevel(loggingEnabled = false),
    )
  }

  /** The provider's level expression, extracted so it can be asserted for both builds. */
  private fun downloadLogLevel(loggingEnabled: Boolean) = if (loggingEnabled) LogLevel.HEADERS else LogLevel.NONE

  @Test
  fun `a download longer than the media request timeout is not aborted`() =
    runTest {
      // The `config {}` inheritance trap. The media client's 15 s request timeout, if copied,
      // would kill every real download. `runTest` skips delays, so a 30 s engine delay stands in
      // for a long transfer without making the test slow.
      val downloader = module.downloaderKtorClient(mediaClientWithBodyLogging(respondSlowly = true))

      val body = downloader.get("http://localhost/big-book.m4b").bodyAsText()

      assertEquals(
        "a long download must complete; the request timeout must not be inherited",
        BODY_MARKER,
        body,
      )
    }

  @Test
  fun `the token is redacted from download logs`() =
    runTest {
      // Logging forced **on**, which is the only way this can fail. `LOG_NETWORK_REQUESTS` is
      // `BuildConfig.DEBUG` and false under unit test, so the first version of this test — built
      // through the provider — passed with `sanitizeHeader` deleted. Verified by sabotage, which
      // is why `installDownloadLogging` takes its level as an argument.
      val client =
        HttpClient(MockEngine { respond(BODY_MARKER, HttpStatusCode.OK) }) {
          with(module) {
            installDownloadLogging(level = LogLevel.HEADERS, sink = { recorded.add(it) })
          }
        }

      client.get("http://localhost/track.mp3") { header("X-Plex-Token", SECRET) }.bodyAsText()

      assertTrue(
        "logging must actually be on, or this test proves nothing",
        recorded.isNotEmpty(),
      )
      assertFalse(
        "a Plex token must never reach a log: ${recorded.joinToString(" | ")}",
        recorded.any { it.contains(SECRET) },
      )
    }

  private companion object {
    const val BODY_MARKER = "AUDIOBOOK-BYTES-THAT-MUST-NOT-BE-LOGGED"
    const val SECRET = "super-secret-plex-token"
  }
}
