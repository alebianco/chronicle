package io.github.mattpvaughn.chronicle.injection.modules

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.jensklingenberg.ktorfit.Ktorfit
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
import timber.log.Timber
import java.io.File
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

  // The three `OkHttpClient` providers are gone (decision-24). OkHttp is still the *engine*
  // Ktor runs on, so the same connection pool and TLS stack move the bytes — what left is app
  // code written against OkHttp's API, which OkHttp 5.0 made a JVM-only commitment when it dropped
  // Kotlin Multiplatform support. `RetiredDependencyTest` keeps them out.
  //
  // Their reasons live on in the Ktor equivalents: `plexMediaInterceptor` became
  // `plexHeadersPlugin`, `PlexTokenAuthenticator` became `plexReauthPlugin` (where "retry exactly
  // once" had to become explicit code, since Ktor has no `Authenticator` contract), and the
  // download client's capped log level is still capped, for issue #83's reason.

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
      // `true`, and the interaction with the re-auth plugin is the subtle part.
      //
      // Ktor 3 defaults this to **false**, which surfaces a non-2xx as an ordinary response. That
      // looks like what `plexReauthPlugin` wants — it inspects a 401 itself — but it is wrong for
      // everything downstream: `ProgressReporter` and the account-rejection check both branch on a
      // thrown `ResponseException`, and with `expectSuccess = false` no exception ever arrives, so
      // a failed scrobble looked like a success and a revoked account was never noticed.
      //
      // The plugin is unaffected, because it runs on the `Send` hook — *before* the validation
      // that raises the exception. So it still sees the raw 401 and can retry, and a 401 that
      // survives the retry still becomes an exception for the caller. Both needs are met.
      expectSuccess = true
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

  /**
   * The Ktor client for plex.tv login traffic.
   *
   * No re-auth plugin, deliberately: a 401 here means the *account* token is dead, and re-fetching
   * resources with that same dead token cannot help. Only the media client recovers from a 401.
   *
   * The token differs too — the login service sends the **user** token where the media client
   * sends the server's, which is why the header plugin takes its token as a lambda rather than
   * reading one canonical value.
   */
  @Provides
  @Singleton
  @Named(OKHTTP_CLIENT_LOGIN)
  fun loginKtorClient(
    plexConfig: PlexConfig,
    plexPrefsRepo: PlexPrefsRepo,
  ): HttpClient =
    HttpClient(OkHttp) {
      // See the media client: a non-2xx must throw, because callers branch on the exception.
      expectSuccess = true
      install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_SECONDS * 1000
        requestTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
        socketTimeoutMillis = READ_TIMEOUT_SECONDS * 1000
      }
      install(
        plexHeadersPlugin(plexPrefsRepo, plexConfig) {
          val userToken = plexPrefsRepo.user?.authToken
          if (userToken.isNullOrEmpty()) plexPrefsRepo.accountAuthToken else userToken
        },
      )
      install(Logging) {
        level = if (LOG_NETWORK_REQUESTS) LogLevel.BODY else LogLevel.NONE
        sanitizeHeader { it.equals("X-Plex-Token", ignoreCase = true) }
        logger =
          object : Logger {
            override fun log(message: String) = Timber.tag("KtorLogin").v(message)
          }
      }
    }

  /**
   * Ktorfit over the media client, replacing the media `Retrofit` (decision-24).
   *
   * `baseUrl` is still [PLACEHOLDER_URL] and still meaningless on its own: the real server address
   * is only known at runtime and can change mid-session when connectivity shifts, so
   * `plexHeadersPlugin` rewrites it per request. That indirection is unchanged — only the library
   * reading the annotations is different.
   */
  @Provides
  @Named(OKHTTP_CLIENT_MEDIA)
  @Singleton
  fun mediaKtorfit(
    @Named(OKHTTP_CLIENT_MEDIA) client: HttpClient,
  ): Ktorfit =
    Ktorfit.Builder()
      .baseUrl(PLACEHOLDER_URL)
      .httpClient(client.config { installPlexJson() })
      .build()

  @Provides
  @Named(OKHTTP_CLIENT_LOGIN)
  @Singleton
  fun loginKtorfit(
    @Named(OKHTTP_CLIENT_LOGIN) client: HttpClient,
  ): Ktorfit =
    Ktorfit.Builder()
      .baseUrl(PLACEHOLDER_URL)
      .httpClient(client.config { installPlexJson() })
      .build()

  @Provides
  @Singleton
  fun plexMediaService(
    @Named(OKHTTP_CLIENT_MEDIA) ktorfit: Ktorfit,
  ): PlexMediaService = ktorfit.createPlexMediaService()

  @Provides
  @Singleton
  fun plexLoginService(
    @Named(OKHTTP_CLIENT_LOGIN) ktorfit: Ktorfit,
  ): PlexLoginService = ktorfit.createPlexLoginService()

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
