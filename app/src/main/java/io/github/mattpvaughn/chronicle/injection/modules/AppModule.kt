package io.github.mattpvaughn.chronicle.injection.modules

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2okhttp.OkHttpDownloader
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
 * Application-wide bindings (cu-185).
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
   * whole audiobook, and body-level logging buffers it in memory (cu-109).
   */
  const val OKHTTP_CLIENT_DOWNLOADER = "Downloader"

  /** Qualifier for the credentials preferences file; see [provideAuthPrefs]. */
  const val AUTH_PREFS = "AuthPrefs"

  /**
   * Handshake budget. A reachability probe that takes 15s has already failed as far as
   * the listener is concerned, and the old value let a dead LAN address consume the whole
   * connection attempt before relay was tried (cu-11).
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
   * value of 1 meant a single network blip ended a download permanently (cu-76).
   */
  const val DOWNLOAD_RETRY_ATTEMPTS = 5

  @Provides
  @Singleton
  fun provideContext(
    @ApplicationContext context: Context,
  ): Context = context

  /**
   * The credentials file, separate from settings (cu-108).
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
   * defiance of its type, or a `NullPointerException` at the first use (cu-85).
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

  @Provides
  @Singleton
  fun fetchConfig(
    appContext: Context,
    @Named(OKHTTP_CLIENT_DOWNLOADER) okHttpClient: OkHttpClient,
  ): FetchConfiguration =
    FetchConfiguration.Builder(appContext)
      .setDownloadConcurrentLimit(3)
      .createDownloadFileOnEnqueue(false)
      .enableAutoStart(false)
      // Was 1: a single retry meant a Wi-Fi blip mid-download ended it for good, and
      // nothing re-enqueued it (cu-76). Fetch2 resumes via HTTP Range, so a retry picks up
      // where it stopped rather than restarting a 2GB file.
      .setAutoRetryMaxAttempts(DOWNLOAD_RETRY_ATTEMPTS)
      // Download through the app's own OkHttp client, so downloads inherit the Plex
      // interceptor's headers, cu-10's 401 re-auth and cu-11's connection tiering. This was
      // commented out with a "broken when I set up Fetch" TODO; the cause was simply that
      // the fetch2okhttp artifact was never declared, so OkHttpDownloader did not exist.
      //
      // Note this is the *downloader* client, not the media one: same interceptors and
      // authenticator, but never body-level logging, which would buffer a whole audiobook in
      // memory and OOM the process (cu-109).
      .setHttpDownloader(OkHttpDownloader(okHttpClient))
      // Fetch2 logs whole `DownloadInfo` objects, and that `toString()` includes the headers
      // map — so plain logging wrote the Plex token to logcat three times before a single byte
      // transferred, in release builds too, since `enableLogging(true)` was unconditional.
      // `TokenLoggingTest` could not catch it: it scans our own Timber calls, not a library's
      // internal logging.
      //
      // Redacted rather than switched off. These lines are how the download path is diagnosed —
      // cu-109's OOM inside Fetch2's own thread was found by reading them, and cu-73's remaining
      // download items still need them.
      .enableLogging(true)
      .setLogger(RedactingFetchLogger())
      .build()

  @Provides
  @Singleton
  fun fetch(fetchConfig: FetchConfiguration): Fetch = Fetch.Impl.getInstance(fetchConfig)

  /**
   * The logging level for **download** traffic.
   *
   * Capped at [HttpLoggingInterceptor.Level.HEADERS] even in debug: `BODY` would buffer a whole
   * audiobook in memory (cu-109 / #83). Headers are the useful part for a download anyway — the
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
      // Recovers a rotated server token on a 401 and retries once (cu-10). Media client
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
   * for the token and base URL, cu-10's [PlexTokenAuthenticator] for a rotated server token,
   * and cu-11's chosen connection — which is why this is [OkHttpClient.newBuilder] off that
   * client rather than a second builder. A parallel builder would be a copy to keep in sync, and
   * the whole point of cu-76 was that downloads share playback's HTTP stack.
   *
   * The one thing it must **not** share is [HttpLoggingInterceptor.Level.BODY]. That level
   * buffers an entire response body in memory in order to log it, and a download's body is the
   * whole audiobook: a 293 MB m4b took the process from 248 MB to 350 MB PSS and then killed it
   * with `OutOfMemoryError` on Fetch2's own thread, with zero bytes written to disk. That is
   * issue #83, which cu-12 could not locate by reading app code or Fetch2 — the defect was in
   * neither, but in the client Fetch2 was handed (cu-109).
   *
   * `HEADERS` rather than `NONE` on purpose: a download's status line and `Content-Range` are
   * exactly what you need to tell a resume from a restart, and they cost nothing to log. Body
   * logging stays on the media client, where it is genuinely useful and where bodies are small —
   * it is how cu-9's `time=0` and the `/:/scrobble` storm were both caught.
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
    // No `KotlinJsonAdapterFactory` (cu-62): every model carries
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
   * Moved here from `ActivityModule` in cu-185: see [MediaServiceConnection] for why it is a
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

  /**
   * The in-process broadcast bus.
   *
   * Moved out of `ActivityModule` in cu-185: it is `getInstance`-backed and process-wide, so
   * activity scope was never meaningful — and the player service and a `@HiltViewModel` both need
   * it, which an activity-scoped binding cannot serve.
   */
  @Provides
  @Singleton
  fun provideBroadcastManager(
    @ApplicationContext context: Context,
  ): LocalBroadcastManager = LocalBroadcastManager.getInstance(context)
}
