package io.github.mattpvaughn.chronicle.injection.modules

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
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
  }

  @Provides
  @Singleton
  fun provideContext(): Context = app.applicationContext

  @Provides
  @Singleton
  fun provideSharedPrefs(): SharedPreferences = app.getSharedPreferences(APP_NAME, MODE_PRIVATE)

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
  fun provideCollectionsDao(): CollectionsDao =
    getCollectionsDatabase(
      app.applicationContext,
    ).collectionsDao

  @Provides
  @Singleton
  fun provideInternalDeviceDirs(): File = app.applicationContext.filesDir

  @Provides
  @Singleton
  fun provideExternalDeviceDirs(): List<File> =
    ContextCompat.getExternalFilesDirs(
      app.applicationContext,
      null,
    ).toList()

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
      .setAutoRetryMaxAttempts(1)
      // TODO: this was broken when I set up Fetch, maybe figure it out at some point?
//            .setHttpDownloader(OkHttpDownloader(okHttpClient))
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
        PlexTokenAuthenticator(plexPrefsRepo) {
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
    Moshi.Builder()
      // Use Kotlin reflection adapter for Moshi since codegen is disabled
      .add(KotlinJsonAdapterFactory())
      .build()

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
      Timber.e("Caught unhandled exception! $e")
    }

  @Provides
  @Singleton
  fun provideCachedFileManager(cacheManager: CachedFileManager): ICachedFileManager = cacheManager

  @Provides
  @Singleton
  fun provideCurrentlyPlaying(): CurrentlyPlaying = CurrentlyPlayingSingleton()
}
