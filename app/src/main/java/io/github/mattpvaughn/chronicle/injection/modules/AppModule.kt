package io.github.mattpvaughn.chronicle.injection.modules

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2okhttp.OkHttpDownloader
import dagger.Module
import dagger.Provides
import io.github.mattpvaughn.chronicle.application.LOG_NETWORK_REQUESTS
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.model.asServer
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import io.github.mattpvaughn.chronicle.util.DefaultDispatcherProvider
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
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

@Module
class AppModule(private val app: Application) {
  companion object {
    const val OKHTTP_CLIENT_MEDIA = "Media"
    const val OKHTTP_CLIENT_LOGIN = "Login"

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
  }

  @Provides
  @Singleton
  fun provideContext(): Context = app.applicationContext

  @Provides
  @Singleton
  fun provideSharedPrefs(): SharedPreferences = app.getSharedPreferences(APP_NAME, MODE_PRIVATE)

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
  fun provideAuthPrefs(): SharedPreferences = app.getSharedPreferences(AUTH_PREFS_NAME, MODE_PRIVATE)

  @Provides
  @Singleton
  fun provideContentResolver(): ContentResolver = app.contentResolver

  @Provides
  @Singleton
  fun providePlexPrefsRepo(prefsImpl: SharedPreferencesPlexPrefsRepo): PlexPrefsRepo = prefsImpl

  @Provides
  @Singleton
  fun providePrefsRepo(prefsImpl: SharedPreferencesPrefsRepo): PrefsRepo = prefsImpl

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
  fun provideExternalScope(dispatchers: DispatcherProvider): CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

  @Provides
  @Singleton
  fun provideTrackDao(): TrackDao = getTrackDatabase(app.applicationContext).trackDao

  @Provides
  @Singleton
  fun provideTrackRepo(trackRepository: TrackRepository): ITrackRepository = trackRepository

  @Provides
  @Singleton
  fun provideBookDao(): BookDao = getBookDatabase(app.applicationContext).bookDao

  @Provides
  @Singleton
  fun provideBookRepo(bookRepository: BookRepository): IBookRepository = bookRepository

  @Provides
  @Singleton
  fun provideChapterDao(): ChapterDao = getChapterDatabase(app.applicationContext).chapterDao

  @Provides
  @Singleton
  fun provideChapterRepo(chapterRepository: ChapterRepository): IChapterRepository = chapterRepository

  @Provides
  @Singleton
  fun provideCollectionsDao(): CollectionsDao =
    getCollectionsDatabase(
      app.applicationContext,
    ).collectionsDao

  @Provides
  @Singleton
  fun provideInternalDeviceDirs(): File = app.applicationContext.filesDir

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
  fun provideExternalDeviceDirs(): List<File> =
    ContextCompat.getExternalFilesDirs(
      app.applicationContext,
      null,
    ).filterNotNull()

  @Provides
  @Singleton
  fun loginRepo(plexLoginRepo: PlexLoginRepo): IPlexLoginRepo = plexLoginRepo

  @Provides
  @Singleton
  fun workManager(): WorkManager = WorkManager.getInstance(app)

  @Provides
  @Singleton
  fun fetchConfig(
    appContext: Context,
    @Named(OKHTTP_CLIENT_MEDIA) okHttpClient: OkHttpClient,
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
      .setHttpDownloader(OkHttpDownloader(okHttpClient))
      .enableLogging(true)
      .build()

  @Provides
  @Singleton
  fun fetch(fetchConfig: FetchConfiguration): Fetch = Fetch.Impl.getInstance(fetchConfig)

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
  fun provideCachedFileManager(cacheManager: CachedFileManager): ICachedFileManager = cacheManager

  @Provides
  @Singleton
  fun provideCurrentlyPlaying(): CurrentlyPlaying = CurrentlyPlayingSingleton()
}
