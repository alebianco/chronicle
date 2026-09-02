package io.github.mattpvaughn.chronicle.injection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The download client never logs response bodies (cu-109 / issue #83).
 *
 * `HttpLoggingInterceptor` at `BODY` buffers a whole response in memory so it can log it. Since
 * cu-76 routed downloads through the app's OkHttp client, that meant every download tried to hold
 * an entire audiobook in RAM: a 293 MB m4b drove the process from 248 MB to 350 MB PSS and then
 * died with `OutOfMemoryError` on Fetch2's own thread, having written **zero** bytes to disk.
 *
 * The mechanism is why this is worth a test at all. cu-12 looked for an OOM in app code and in
 * Fetch2 and correctly found none — the defect lived in the *seam*, in the client Fetch2 was
 * handed. Nothing about either side in isolation reveals it, and no unit test can observe the OOM
 * itself, so the property has to be pinned structurally instead.
 *
 * Unlike [ConnectionTimeoutTest], which can only pin constants, these build the **real** client
 * from the real module and inspect what it actually carries.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadLogLevelTest {
  private val module = AppModule(ApplicationProvider.getApplicationContext<Application>())

  /** A stand-in for the media client: what matters is that it carries a BODY logger. */
  private fun mediaClientWithBodyLogging(): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
      .build()

  private fun loggersOf(client: OkHttpClient) = client.interceptors.filterIsInstance<HttpLoggingInterceptor>()

  @Test
  fun `the download client never logs bodies`() {
    val downloader = module.downloaderOkHttpClient(mediaClientWithBodyLogging())

    val levels = loggersOf(downloader).map { it.level }
    assertTrue(
      "a download body is a whole audiobook; logging it buffers the file in memory and OOMs " +
        "the process (cu-109). Found levels: $levels",
      levels.none { it == HttpLoggingInterceptor.Level.BODY },
    )
  }

  @Test
  fun `the download client still logs headers in debug`() {
    // Not NONE: a download's status line and Content-Range are how you tell a resume from a
    // restart, which is a live cu-73 checklist item. Diagnosability is the reason the
    // interceptor is kept at all rather than dropped.
    assertEquals(
      HttpLoggingInterceptor.Level.HEADERS,
      module.downloadLogLevel(),
    )
  }

  @Test
  fun `the media client's body logger is not carried over`() {
    // The bug was inheritance: the download client is derived from the media client, so a
    // surviving BODY logger is the exact failure mode. Exactly one logger, and it is ours.
    val downloader = module.downloaderOkHttpClient(mediaClientWithBodyLogging())

    assertEquals(
      "the inherited logger must be replaced, not appended to",
      1,
      loggersOf(downloader).size,
    )
  }

  @Test
  fun `a second inherited body logger is also dropped`() {
    // Filtering is by type, not by identity, so more than one inherited logger cannot slip
    // through. Guards against a future edit adding another logger to the media client.
    val doubled =
      OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .build()

    val downloader = module.downloaderOkHttpClient(doubled)

    assertEquals(1, loggersOf(downloader).size)
    assertEquals(
      HttpLoggingInterceptor.Level.HEADERS,
      loggersOf(downloader).single().level,
    )
  }

  @Test
  fun `every non-logging interceptor survives`() {
    // cu-76's whole gain: downloads inherit the Plex interceptor's token and base URL, and
    // cu-10's re-auth. Dropping those to fix the OOM would trade one bug for a worse one.
    val plexish = Interceptor { chain -> chain.proceed(chain.request()) }
    val media =
      OkHttpClient.Builder()
        .addInterceptor(plexish)
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .build()

    val downloader = module.downloaderOkHttpClient(media)

    assertTrue(
      "the Plex interceptor must survive, or downloads lose their token and connection",
      downloader.interceptors.contains(plexish),
    )
  }

  @Test
  fun `the authenticator and timeouts are inherited`() {
    // Derived with newBuilder precisely so these come along for free rather than being a
    // parallel builder to keep in sync.
    val auth = Authenticator { _, _ -> null }
    val media =
      OkHttpClient.Builder()
        .authenticator(auth)
        .connectTimeout(AppModule.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .build()

    val downloader = module.downloaderOkHttpClient(media)

    assertSame("cu-10's 401 re-auth must reach downloads", auth, downloader.authenticator)
    assertEquals(media.connectTimeoutMillis, downloader.connectTimeoutMillis)
  }

  @Test
  fun `the downloader is a distinct client from the media one`() {
    // If these were the same instance the fix would be a no-op, and body logging would be gone
    // from the API calls where it is actually wanted.
    val media = mediaClientWithBodyLogging()

    val downloader = module.downloaderOkHttpClient(media)

    assertNotNull(downloader)
    assertTrue(
      "sharing the instance would strip body logging from the small API calls too",
      downloader !== media,
    )
    assertEquals(
      "the media client must keep BODY logging; it is how cu-9's time=0 was caught",
      HttpLoggingInterceptor.Level.BODY,
      loggersOf(media).single().level,
    )
  }
}
