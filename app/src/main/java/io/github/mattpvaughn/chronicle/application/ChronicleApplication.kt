package io.github.mattpvaughn.chronicle.application

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SeriesIndexRulesLoader
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.asServer
import io.github.mattpvaughn.chronicle.data.model.mergeServerRefresh
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.debug.DebugHooks
import io.github.mattpvaughn.chronicle.injection.chronicleGraph
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import javax.inject.Inject

// Exposing a ref to the application statically doesn't leak anything because Application is already
// a singleton
@Suppress("LeakingThis")
@HiltAndroidApp
open class ChronicleApplication :
  Application(),
  Configuration.Provider,
  SingletonImageLoader.Factory {
  /**
   * Builds workers with their dependencies injected, moved to Hilt.
   *
   * `Configuration.Provider` replaces WorkManager's default initialisation, which is what lets a
   * `WorkerFactory` be installed at all. `HiltWorkerFactory` replaces the hand-written
   * `ChronicleWorkerFactory` and its seven graph-reading lambdas — each worker is `@HiltWorker`
   * with an ordinary `@Inject` constructor now, so the goal (workers constructable in a unit
   * test) is met by the framework rather than by a factory we maintain.
   */
  @Inject
  lateinit var workerFactory: HiltWorkerFactory

  override val workManagerConfiguration: Configuration
    get() =
      Configuration.Builder()
        .setWorkerFactory(workerFactory)
        .build()

  init {
    INSTANCE = this
  }

  private var applicationJob = Job()

  /**
   * The one hardcoded dispatcher outside the player layer, and it cannot be otherwise.
   *
   * This is a **field initialiser on the DI root itself**: `applicationComponent` is built inside
   * `onCreate`, so an injected `DispatcherProvider` does not exist yet when this line runs. Reading
   * one here would be a circular dependency — the same reasoning recorded for
   * `MediaPlayerService.serviceScope`. `DispatcherProviderExemptionTest` pins the count at one so
   * this cannot quietly become a precedent.
   */
  private val applicationScope = CoroutineScope(applicationJob + Dispatchers.Main)

  @Inject
  lateinit var plexPrefs: PlexPrefsRepo

  @Inject
  lateinit var plexMediaService: PlexMediaService

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var seriesIndexRulesLoader: SeriesIndexRulesLoader

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var collectionsRepository: CollectionsRepository

  @Inject
  lateinit var unhandledExceptionHandler: CoroutineExceptionHandler

  @Inject
  lateinit var cachedFileManager: ICachedFileManager

  @Inject
  lateinit var dispatchers: DispatcherProvider

  @Inject
  lateinit var plexLoginService: PlexLoginService

  @Inject
  lateinit var accountAuthState: AccountAuthState

  @Inject
  lateinit var deviceAuthorizationCheck: DeviceAuthorizationCheck

  /**
   * Coil's image loader, built on the media **Ktor** client so image requests carry the same Plex
   * auth headers and connection handling as everything else.
   *
   * `coil-network-ktor3` rather than `coil-network-okhttp`, and this is the change that made
   * decision-24 worth doing at all rather than half-doing: the first version of that ADR argued
   * OkHttp would stay in the APK regardless *because* Coil pulls it, which was simply wrong — the
   * ktor3 artifact exists at the same Coil version and is a drop-in swap. Sharing the media client
   * matters for a Plex-specific reason: cover art is served by the media server and needs the
   * `X-Plex-Token`, so an unauthenticated loader would render every cover as a broken image.
   */
  override fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(KtorNetworkFetcherFactory(httpClient = { context.chronicleGraph().mediaHttpClient() }))
      }
      .build()

  override fun onCreate() {
    // **First, not last**. Hilt injects this class's members inside `super.onCreate()`,
    // so everything below it runs with the `@Inject` fields still uninitialised — the app died on
    // launch with "lateinit property unhandledExceptionHandler has not been initialized". It used
    // to be the final statement because the old graph was built by hand *here*, before super ran.
    //
    // The one ordering constraint in this method is unaffected: `DebugHooks.onApplicationCreate`
    // still precedes `setupNetwork`.
    super.onCreate()

    if (USE_STRICT_MODE && BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
//                    choose which ones you want
//                    .detectDiskReads()
//                    .detectDiskWrites()
//                    .detectNetwork() // or .detectAll() for all detectable problems
          .penaltyLog()
          .penaltyDeath()
          .build(),
      )
      StrictMode.setVmPolicy(
        VmPolicy.Builder()
          .detectLeakedSqlLiteObjects()
          .detectLeakedClosableObjects()
          .detectActivityLeaks()
          .penaltyLog()
          .penaltyDeath()
          .build(),
      )
    }
    if (BuildConfig.DEBUG) {
      Timber.plant(Timber.DebugTree())
    }

    // No-op in release. In debug this may seed a fixture-backed Plex session, so
    // it must run before setupNetwork, which would otherwise try to refresh
    // connections against the real plex.tv and clear them.
    DebugHooks.onApplicationCreate(this)
    installSeriesIndexRules()

    adoptLegacyRows()
    setupNetwork(plexPrefs)
    updateDownloadedFileState()
  }

  /**
   * Updates the book and track repositories to reflect the true state of downloaded files
   */

  private fun updateDownloadedFileState() {
    applicationScope.launch {
      withContext(dispatchers.io) {
        cachedFileManager.refreshTrackDownloadedStatus()
      }
      // A download interrupted by a Wi-Fi drop or a process death used to stay abandoned:
      // one retry, then nothing re-enqueued it. Launch is the first chance to pick
      // it back up.
      cachedFileManager.resumeInterruptedDownloads()
    }
  }

  /**
   * Installs the user's own series-index parsing rules, if they wrote a file.
   *
   * Launched rather than awaited: a refresh that beats the install reads the built-in rules, which
   * is the behaviour the app has always had, and `Audiobook.from` re-reads the installed set on
   * every call — so a rule arriving a moment late applies from the next book onward rather than
   * being missed. Blocking startup on an optional file would be the worse trade.
   */
  private fun installSeriesIndexRules() {
    applicationScope.launch {
      seriesIndexRulesLoader.install()
    }
  }

  /**
   * Claims rows written before source scoping existed, for the connected server (decision-21).
   *
   * The v12->v13 and v6->v7 migrations mark every pre-existing row [SourceId.LEGACY_PLEX], because
   * a `SupportSQLiteDatabase` cannot know which server the app is configured for. Until they are
   * adopted, every scoped read filters them out — an upgrading user opens the app to an empty
   * library with their listening positions intact but invisible.
   *
   * Launched rather than awaited: `onCreate` must not block on disk. The consequence is a brief
   * window on the first launch after upgrading where the library reads empty and then fills — the
   * same trade the chapter backfill made before it was retired.
   *
   * Idempotent and a no-op once no row carries the marker, so running it on every start is
   * cheaper than recording whether it has run.
   */
  private fun adoptLegacyRows() {
    applicationScope.launch(unhandledExceptionHandler) {
      bookRepository.adoptLegacyRows()
      trackRepository.adoptLegacyRows()
      // Collections additionally adopt `SourceId.UNKNOWN` rows — see `adoptUnscopedRows`.
      collectionsRepository.adoptUnscopedRows()
    }
  }

  companion object {
    /**
     * How long to wait for a `/api/v2/resources` refresh before launching with the
     * cached server. Short on purpose: this is on the startup path, and a stale
     * connection list is far better than a slow cold start.
     */
    private const val RESOURCE_REFRESH_TIMEOUT_MS = 4000L

    /**
     * Whether a failed `/api/v2/resources` refresh means the **account token was refused**, as
     * opposed to the network being unavailable.
     *
     * Split out of [setupNetwork] so it can be tested: the branch used to be a blanket
     * `catch (e: Exception)` that logged every failure as "keeping cached server", so a real
     * `401 Unauthorized` — the one unambiguous signal that the account is dead — was discarded and
     * the user was never told (decision-17, measured directly).
     *
     * Only an explicit 401 qualifies. A timeout, a connection error and a 5xx are all *not* this:
     * being offline is not being signed out, and treating it as such would nag every user
     * on a train.
     */
    fun isAccountRejection(e: Throwable): Boolean = e is ResponseException && e.response.status.value == HTTP_UNAUTHORIZED

    private var INSTANCE: ChronicleApplication? = null

    @JvmStatic
    fun get(): ChronicleApplication = INSTANCE!!
  }

  private fun setupNetwork(plexPrefs: PlexPrefsRepo) {
    val connectivityManager =
      getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      connectivityManager.registerDefaultNetworkCallback(
        object :
          ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            connectToServer()
            // The other moment an abandoned download can make progress. Fetch2 ignores
            // downloads already running, so calling this on every network change is safe.
            cachedFileManager.resumeInterruptedDownloads()
            super.onAvailable(network)
          }

          override fun onLost(network: Network) {
            // Prevent from running on ConnectivityThread, because onLost is apparently
            // called on ConnectivityThread with no warning
            applicationScope.launch {
              withContext(dispatchers.main) {
                plexConfig.connectionHasBeenLost()
              }
            }
            super.onLost(network)
          }
        },
      )
    } else {
      // network listener for sdk 24 and below
      registerReceiver(
        networkStateListener,
        IntentFilter().apply {
          @Suppress("DEPRECATION")
          addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        },
      )
    }
    val server = plexPrefs.server
    if (server != null) {
      plexConfig.setPotentialConnections(server.connections)
      applicationScope.launch(unhandledExceptionHandler) {
        // Keep the whole refreshed server, not just its connections: asServer() carries a
        // fresh accessToken, and dropping it meant a rotated server token was re-fetched
        // and discarded on every launch.
        val fetched: ServerModel? =
          withTimeoutOrNull(RESOURCE_REFRESH_TIMEOUT_MS) {
            try {
              plexLoginService.resources()
                .filter { it.provides.contains("server") }
                .map { it.asServer() }
                .firstOrNull { it.serverId == server.serverId }
            } catch (e: Exception) {
              if (isAccountRejection(e)) {
                Timber.w("plex.tv refused the account token (401); marking the account revoked")
                accountAuthState.onAccountRejected()
              } else {
                // Launching offline is ordinary; keep the cached credentials.
                Timber.w(e, "Could not refresh server resources; keeping cached server")
              }
              null
            }
          }
        plexPrefs.server = mergeServerRefresh(server, fetched)
        Timber.i("Server refresh applied (fetched = ${fetched != null})")
        // Ask whether this install is still a registered device. Removing one at plex.tv
        // invalidates no token, so without this the app keeps working on credentials the user
        // believes they withdrew (decision-17). Shares the refresh's timeout budget; any failure
        // is inconclusive, never a revocation.
        withTimeoutOrNull(RESOURCE_REFRESH_TIMEOUT_MS) { deviceAuthorizationCheck.run() }
        try {
          plexConfig.connectToServer(plexMediaService)
        } catch (t: Throwable) {
          Timber.e(t, "Failed to connect to server after refresh")
        }
      }
    }
  }

  private val networkStateListener =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        applicationScope.launch {
          if (context != null && intent != null) {
            plexConfig.connectionHasBeenLost()
            connectToServer()
          }
        }
      }
    }

  // Connect to the first connection which can establish a connection
  private fun connectToServer() {
    plexConfig.connectToServer(plexMediaService)
  }
}
