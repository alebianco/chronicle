package io.github.mattpvaughn.chronicle.injection.modules

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mattpvaughn.chronicle.application.LOG_NETWORK_REQUESTS
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.model.asServer
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.injection.qualifiers.ApplicationScope
import io.github.mattpvaughn.chronicle.util.DefaultDispatcherProvider
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.ServiceUtils
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Application-wide bindings.
 *
 * An `object` with `@ApplicationContext` parameters rather than a class holding an `Application`:
 * Hilt builds the module itself, so a constructor argument has nowhere to come from. Each provider
 * that needed the app now takes the context it actually wanted.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  const val OKHTTP_CLIENT_MEDIA = "Media"
  const val OKHTTP_CLIENT_LOGIN = "Login"

  /**
   * Qualifier for the client Fetch2 downloads through; see [downloaderOkHttpClient].
   *
   * Deliberately *not* the media client, even though it is derived from it: a media body is a
   * whole audiobook, and body-level logging buffers it in memory.
   */
  const val OKHTTP_CLIENT_DOWNLOADER = "Downloader"

  /** Qualifier for the credentials preferences file; see [provideAuthPrefs]. */
  const val AUTH_PREFS = "AuthPrefs"

  /**
   * Handshake budget. A reachability probe that takes 15s has already failed as far as
   * the listener is concerned, and the old value let a dead LAN address consume the whole
   * connection attempt before relay was tried.
   */
  const val CONNECT_TIMEOUT_SECONDS = 5L

  /**
   * Transfer budget, deliberately still long. A slow *stream* of audio is useful; a slow
   * *handshake* just means the route is wrong. Do not shorten this to match the connect
   * timeout.
   */
  const val READ_TIMEOUT_SECONDS = 15L

  /**
   * How many times Fetch2 retries a failed download before giving up.
   *
   * Retries resume via HTTP Range rather than restarting, so this is cheap; the previous
   * value of 1 meant a single network blip ended a download permanently.
   */
  const val DOWNLOAD_RETRY_ATTEMPTS = 5

  @Provides
  @Singleton
  fun provideContext(
    @ApplicationContext context: Context,
  ): Context = context

  /**
   * The credentials file, separate from settings.
   *
   * Qualified rather than replacing the unqualified binding: settings, the sync path and the
   * backup export all legitimately want `Chronicle.xml`, and only the three credential accessors
   * in `SharedPreferencesPlexPrefsRepo` want this one. An unqualified second `SharedPreferences`
   * would be ambiguous to Dagger and, worse, easy to inject by accident.
   */
  @Provides
  @Singleton
  @Named(AUTH_PREFS)
  fun provideAuthPrefs(
    @ApplicationContext context: Context,
  ): SharedPreferences = context.getSharedPreferences(AUTH_PREFS_NAME, MODE_PRIVATE)

  @Provides
  @Singleton
  fun provideContentResolver(
    @ApplicationContext context: Context,
  ): ContentResolver = context.contentResolver

  @Provides
  @Singleton
  fun providePlexPrefsRepo(prefsImpl: SharedPreferencesPlexPrefsRepo): PlexPrefsRepo = prefsImpl

  @Provides
  @Singleton
  fun provideDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider = impl

  /**
   * A long-lived scope for work that must outlive the caller — a download finishing
   * after its screen closes, say.
   *
   * [SupervisorJob] so one failed child does not cancel the rest: these are
   * independent operations, and cancelling unrelated downloads because one failed
   * would be a regression, not cleanup.
   */
  @Provides
  @Singleton
  @ApplicationScope
  fun provideExternalScope(dispatchers: DispatcherProvider): CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

  @Provides
  @Singleton
  fun provideTrackDao(
    @ApplicationContext context: Context,
  ): TrackDao = getTrackDatabase(context).trackDao

  @Provides
  @Singleton
  fun provideBookDao(
    @ApplicationContext context: Context,
  ): BookDao = getBookDatabase(context).bookDao

  @Provides
  @Singleton
  fun provideChapterDao(
    @ApplicationContext context: Context,
  ): ChapterDao = getChapterDatabase(context).chapterDao

  @Provides
  @Singleton
  fun provideBookmarkDao(
    @ApplicationContext context: Context,
  ): BookmarkDao = getBookmarkDatabase(context).bookmarkDao

  @Provides
  @Singleton
  fun provideBookmarkRepo(bookmarkRepository: BookmarkRepository): IBookmarkRepository = bookmarkRepository

  @Provides
  @Singleton
  fun provideCollectionsDao(
    @ApplicationContext context: Context,
  ): CollectionsDao = getCollectionsDatabase(context).collectionsDao

  @Provides
  @Singleton
  fun provideInternalDeviceDirs(
    @ApplicationContext context: Context,
  ): File = context.filesDir

  /**
   * The app's external storage directories, nulls removed.
   *
   * `getExternalFilesDirs` returns a `File[]` that **may contain null entries** for volumes that
   * are currently unavailable — an ejected SD card, or one not yet mounted. `.toList()` kept those,
   * so the declared `List<File>` really held nulls at runtime and `first()` could hand back null in
   * defiance of its type, or a `NullPointerException` at the first use.
   *
   * The order is also not a stable identity: entries come and go with the volumes, so the *index*
   * of a directory must never be treated as a durable reference to it. See
   * `SharedPreferencesPrefsRepo.cachedMediaDir`, which stores the chosen path instead.
   */
  @Provides
  @Singleton
  fun provideExternalDeviceDirs(
    @ApplicationContext context: Context,
  ): List<File> =
    ContextCompat.getExternalFilesDirs(
      context,
      null,
    ).filterNotNull()

  @Provides
  @Singleton
  fun loginRepo(plexLoginRepo: PlexLoginRepo): IPlexLoginRepo = plexLoginRepo

  @Provides
  @Singleton
  fun workManager(
    @ApplicationContext context: Context,
  ): WorkManager = WorkManager.getInstance(context)

  // `fetchConfig` and `fetch` are gone with Fetch2 (decision-24). Two of their settings had
  // reasons worth keeping on the record, and both are now properties of `KtorDownloader` instead:
  //
  // - `setAutoRetryMaxAttempts` was raised from 1 because a single retry meant a Wi-Fi blip mid
  //   download ended it for good. Retry is WorkManager's job now, and resume is a `Range`
  //   request, so a blip costs the tail of a file rather than the whole thing.
  // - `RedactingFetchLogger` existed because Fetch2 logged whole `DownloadInfo` objects, whose
  //   `toString()` includes the headers map — so plain logging wrote the Plex token to logcat
  //   three times before a single byte transferred, in release builds too. `TokenLoggingTest`
  //   could not catch it, because it scans this app's Timber calls and not a library's internals.
  //   The replacement is `sanitizeHeader` on the download client's `Logging` plugin, which
  //   `KtorDownloadClientTest` pins with logging forced on.

  /**
   * The logging level for **download** traffic.
   *
   * Capped at [HttpLoggingInterceptor.Level.HEADERS] even in debug: `BODY` would buffer a whole
   * audiobook in memory (issue #83). Headers are the useful part for a download anyway — the
   * `206`, the `Content-Range`, and whether a retry resumed or restarted.
   *
   * Named rather than inlined so [io.github.mattpvaughn.chronicle.injection.DownloadLogLevelTest]
   * can pin it: a future edit raising this to `BODY` reintroduces an OOM that no unit test could
   * otherwise catch.
   */
  fun downloadLogLevel(): HttpLoggingInterceptor.Level =
    if (LOG_NETWORK_REQUESTS) {
      HttpLoggingInterceptor.Level.HEADERS
    } else {
      HttpLoggingInterceptor.Level.NONE
    }

  @Provides
  @Singleton
  fun loggingInterceptor() =
    if (LOG_NETWORK_REQUESTS) {
      HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY)
    } else {
      HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.NONE)
    }

  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_MEDIA)
  fun mediaOkHttpClient(
    plexConfig: PlexConfig,
    loggingInterceptor: HttpLoggingInterceptor,
    plexPrefsRepo: PlexPrefsRepo,
    // Provider, not the service: resolving PlexLoginService here would tie the media
    // client's construction to the login Retrofit's. There is no cycle today, but a lazy
    // edge keeps it that way if the login branch ever grows a media dependency.
    plexLoginService: Provider<PlexLoginService>,
    accountAuthState: AccountAuthState,
  ): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .protocols(listOf(Protocol.HTTP_1_1, Protocol.QUIC))
      .addInterceptor(plexConfig.plexMediaInterceptor)
      .addInterceptor(loggingInterceptor)
      // Recovers a rotated server token on a 401 and retries once. Media client
      // only: a 401 from the *login* client means the account token is dead, and
      // re-fetching resources with that same dead token cannot help.
      .authenticator(
        PlexTokenAuthenticator(
          plexPrefsRepo = plexPrefsRepo,
          accountAuthState = accountAuthState,
        ) {
          val cached = plexPrefsRepo.server ?: return@PlexTokenAuthenticator null
          plexLoginService.get().resources()
            .filter { it.provides.contains("server") }
            .map { it.asServer() }
            .firstOrNull { it.serverId == cached.serverId }
        },
      )
      .build()

  /**
   * The client Fetch2 downloads through: the media client with body logging turned down.
   *
   * Downloads must keep everything the media client provides — [PlexConfig.plexMediaInterceptor]
   * for the token and base URL, the [PlexTokenAuthenticator] for a rotated server token,
   * and the chosen connection — which is why this is [OkHttpClient.newBuilder] off that
   * client rather than a second builder. A parallel builder would be a copy to keep in sync, and
   * downloads are meant to share playback's HTTP stack.
   *
   * The one thing it must **not** share is [HttpLoggingInterceptor.Level.BODY]. That level
   * buffers an entire response body in memory in order to log it, and a download's body is the
   * whole audiobook: a 293 MB m4b took the process from 248 MB to 350 MB PSS and then killed it
   * with `OutOfMemoryError` on Fetch2's own thread, with zero bytes written to disk. That is
   * issue #83, which could not be located by reading app code or Fetch2 — the defect was in
   * neither, but in the client Fetch2 was handed.
   *
   * `HEADERS` rather than `NONE` on purpose: a download's status line and `Content-Range` are
   * exactly what you need to tell a resume from a restart, and they cost nothing to log. Body
   * logging stays on the media client, where it is genuinely useful and where bodies are small —
   * it is how the `time=0` and the `/:/scrobble` storm were both caught.
   */
  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_DOWNLOADER)
  fun downloaderOkHttpClient(
    @Named(OKHTTP_CLIENT_MEDIA) mediaClient: OkHttpClient,
  ): OkHttpClient =
    mediaClient.newBuilder()
      .apply {
        // Drop every logging interceptor the media client carries, then re-add one capped at
        // HEADERS. Filtering by type rather than by identity so an added second logger cannot
        // slip through, and rebuilding the list because `interceptors()` on the builder is a
        // mutable view — there is no "replace" on OkHttp's builder.
        val survivors = interceptors().filterNot { it is HttpLoggingInterceptor }
        interceptors().clear()
        interceptors().addAll(survivors)
        addInterceptor(HttpLoggingInterceptor().setLevel(downloadLogLevel()))
      }
      .build()

  /**
   * The Ktor client for media traffic, per decision-24.
   *
   * Runs on the **OkHttp engine**, which is deliberate and is what keeps this migration low-risk:
   * the connection pool, TLS stack and HTTP/1.1 behaviour are the ones already in production. Ktor
   * supplies the plugin pipeline and the multiplatform-shaped API; OkHttp still moves the bytes.
   *
   * The plugin order matters. `PlexHeaders` runs on every request and sets the token from prefs, so
   * when `PlexReauth` persists a refreshed server and calls `proceed` again, the retry picks up the
   * new value on the way back through. Reversing them would retry with the stale token.
   */
  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_MEDIA)
  fun mediaKtorClient(
    plexConfig: PlexConfig,
    plexPrefsRepo: PlexPrefsRepo,
    // Provider, not the service: resolving the login service here would tie the media client's
    // construction to the login client's. There is no cycle today, and a lazy edge keeps it that
    // way if the login branch ever grows a media dependency.
    plexLoginService: Provider<PlexLoginService>,
    accountAuthState: AccountAuthState,
  ): HttpClient =
    HttpClient(OkHttp) {
      // Ktor surfaces a non-2xx as a response rather than throwing, which is what the re-auth
      // plugin needs in order to inspect a 401 itself.
      expectSuccess = false
      install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_SECONDS * 1000
        requestTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
        socketTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
      }
      install(
        plexHeadersPlugin(plexPrefsRepo, plexConfig) {
          val serverToken = plexPrefsRepo.server?.accessToken
          if (serverToken.isNullOrEmpty()) plexPrefsRepo.accountAuthToken else serverToken
        },
      )
      // Media client only: a 401 from the *login* client means the account token is dead, and
      // re-fetching resources with that same dead token cannot help.
      install(
        plexReauthPlugin(
          plexPrefsRepo = plexPrefsRepo,
          accountAuthState = accountAuthState,
        ) {
          val cached = plexPrefsRepo.server ?: return@plexReauthPlugin null
          plexLoginService.get().resources()
            .filter { it.provides.contains("server") }
            .map { it.asServer() }
            .firstOrNull { it.serverId == cached.serverId }
        },
      )
      // Installed unconditionally, with the *level* switched off in release — the shape the
      // OkHttp clients used. An `if (LOG_NETWORK_REQUESTS) install(...)` would mean the
      // `sanitizeHeader` below does not exist in any build where logging is off, so a future
      // change that turns logging on would ship without redaction. Keeping the plugin present
      // keeps the sanitiser present.
      install(Logging) {
        level = if (LOG_NETWORK_REQUESTS) LogLevel.BODY else LogLevel.NONE
        sanitizeHeader { it.equals("X-Plex-Token", ignoreCase = true) }
        logger =
          object : Logger {
            override fun log(message: String) = Timber.tag("KtorMedia").v(message)
          }
      }
    }

  /**
   * The Ktor client downloads run through: the media client's configuration with body logging off.
   *
   * Downloads must keep everything the media client provides — the Plex headers, the re-auth
   * plugin, the chosen connection — which is why this is [HttpClient.config] off that client rather
   * than a second builder. A parallel builder would be a copy to keep in sync, and downloads are
   * meant to share playback's HTTP stack.
   *
   * The one thing it must **not** share is [LogLevel.BODY]. That level buffers an entire response
   * body in order to log it, and a download's body is the whole audiobook: a 293 MB m4b took the
   * process from 248 MB to 350 MB PSS and then killed it with `OutOfMemoryError`, with zero bytes
   * written to disk. That is issue #83, and the defect was in neither the app code nor the download
   * library — it was in the client the library was handed. The same trap exists here, so the same
   * cap applies.
   *
   * [LogLevel.HEADERS] rather than `NONE` on purpose: a download's status line and `Content-Range`
   * are exactly what you need to tell a resume from a restart, and they cost nothing to log.
   *
   * No `HttpTimeout` override is inherited-and-kept by accident: `config` copies the media client's,
   * whose `requestTimeoutMillis` would abort a multi-minute download. It is removed here.
   */
  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_DOWNLOADER)
  fun downloaderKtorClient(
    @Named(OKHTTP_CLIENT_MEDIA) mediaClient: HttpClient,
  ): HttpClient =
    mediaClient.config {
      install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_SECONDS * 1000
        // A download is arbitrarily long; only stalls matter, which the socket timeout catches.
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
      }
      // Re-installed rather than inherited, which is the whole point of this provider: `config`
      // copies the media client's BODY level, and BODY on a download is issue #83. Always present,
      // level gated — see the media client above for why the plugin is not itself conditional.
      installDownloadLogging(
        level = if (LOG_NETWORK_REQUESTS) LogLevel.HEADERS else LogLevel.NONE,
        sink = { Timber.tag("KtorDownload").v(it) },
      )
    }

  /**
   * Installs download logging with the token redacted.
   *
   * Named and taking its [level] and [sink] as parameters so `KtorDownloadClientTest` can force
   * logging **on** and assert the redaction. That is not a convenience: `LOG_NETWORK_REQUESTS` is
   * `BuildConfig.DEBUG`, which is false under unit test, so a redaction test against the real
   * provider passes with the sanitiser deleted — verified by sabotage. The level had to become an
   * argument for the guard to be able to fail.
   */
  fun HttpClientConfig<*>.installDownloadLogging(
    level: LogLevel,
    sink: (String) -> Unit,
  ) {
    install(Logging) {
      this.level = level
      sanitizeHeader { it.equals("X-Plex-Token", ignoreCase = true) }
      logger =
        object : Logger {
          override fun log(message: String) = sink(message)
        }
    }
  }

  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_LOGIN)
  fun loginOkHttpClient(
    plexConfig: PlexConfig,
    loggingInterceptor: HttpLoggingInterceptor,
  ): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .addInterceptor(plexConfig.plexLoginInterceptor)
      .addInterceptor(loggingInterceptor)
      .build()

  @Provides
  @Named(OKHTTP_CLIENT_MEDIA)
  @Singleton
  fun mediaRetrofit(
    @Named(OKHTTP_CLIENT_MEDIA) okHttpClient: OkHttpClient,
    moshi: Moshi,
  ): Retrofit =
    Retrofit.Builder()
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .client(okHttpClient)
      .baseUrl(PLACEHOLDER_URL) // this will be replaced by PlexInterceptor as needed
      .build()

  @Provides
  @Named(OKHTTP_CLIENT_LOGIN)
  @Singleton
  fun loginRetrofit(
    @Named(OKHTTP_CLIENT_LOGIN) okHttpClient: OkHttpClient,
    moshi: Moshi,
  ): Retrofit =
    Retrofit.Builder()
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .client(okHttpClient)
      .baseUrl(PLACEHOLDER_URL) // this will be replaced by PlexInterceptor as needed
      .build()

  @Provides
  @Singleton
  fun moshi(): Moshi =
    // No `KotlinJsonAdapterFactory`: every model carries
    // `@JsonClass(generateAdapter = true)` and the KSP processor now generates a real adapter for
    // each, so the reflective fallback is dead weight — and worse, it would mask a model that
    // *lost* its annotation by silently handling it reflectively.
    Moshi.Builder().build()

  @Provides
  @Singleton
  fun plexMediaService(
    @Named(OKHTTP_CLIENT_MEDIA) mediaRetrofit: Retrofit,
  ): PlexMediaService = mediaRetrofit.create(PlexMediaService::class.java)

  @Provides
  @Singleton
  fun plexLoginService(
    @Named(OKHTTP_CLIENT_LOGIN) loginRetrofit: Retrofit,
  ): PlexLoginService = loginRetrofit.create(PlexLoginService::class.java)

  @Provides
  @Singleton
  fun exceptionHandler(): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
      Timber.e(e, "Caught unhandled exception!")
    }

  @Provides
  @Singleton
  fun provideCurrentlyPlaying(): CurrentlyPlaying = CurrentlyPlayingSingleton()

  /**
   * The app's one connection to the media service, reconnecting to a session already running.
   *
   * Moved here from `ActivityModule`: see [MediaServiceConnection] for why it is a
   * singleton. It takes the application context, so nothing about it was activity-shaped.
   */
  @Provides
  @Singleton
  fun mediaServiceConnection(
    @ApplicationContext context: Context,
  ): MediaServiceConnection {
    val conn =
      MediaServiceConnection(
        context,
        ComponentName(context, MediaPlayerService::class.java),
      )
    val doesServiceExist =
      ServiceUtils.isServiceRunning(
        context,
        MediaPlayerService::class.java,
      )
    Timber.i("Connecting to existing service? $doesServiceExist")
    if (doesServiceExist) {
      conn.connect()
    }
    return conn
  }

  // `provideBroadcastManager` is gone with LocalBroadcastManager. Its replacements —
  // `SleepTimerBus` and `PlaybackErrorBus` — are @Singleton classes with @Inject constructors, so
  // they need no provider: there is nothing to construct them *from*, which is the point of
  // replacing a `getInstance`-backed singleton with an ordinary injectable one.
}
