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
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.asServer
import io.github.mattpvaughn.chronicle.data.model.mergeServerRefresh
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.debug.DebugHooks
import io.github.mattpvaughn.chronicle.injection.components.AppComponent
import io.github.mattpvaughn.chronicle.injection.components.DaggerAppComponent
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import kotlinx.coroutines.*
import retrofit2.HttpException
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import javax.inject.Inject
import javax.inject.Singleton

// Exposing a ref to the application statically doesn't leak anything because Application is already
// a singleton
@Suppress("LeakingThis")
@Singleton
open class ChronicleApplication :
  Application(),
  SingletonImageLoader.Factory {
  // Instance of the AppComponent that will be used by all the Activities in the project
  val appComponent by lazy {
    initializeComponent()
  }

  init {
    INSTANCE = this
  }

  private var applicationJob = Job()
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
  lateinit var unhandledExceptionHandler: CoroutineExceptionHandler

  @Inject
  lateinit var cachedFileManager: ICachedFileManager

  @Inject
  lateinit var plexLoginService: PlexLoginService

  @Inject
  lateinit var accountAuthState: AccountAuthState

  @Inject
  lateinit var deviceAuthorizationCheck: DeviceAuthorizationCheck

  /**
   * Coil's image loader, built on the media OkHttp client so image requests carry
   * the same Plex auth headers and connection handling as everything else.
   */
  override fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
      .components {
        add(
          OkHttpNetworkFetcherFactory(
            callFactory = { Injector.get().mediaOkHttpClient() },
          ),
        )
      }
      .build()

  override fun onCreate() {
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

    appComponent.inject(this)
    // No-op in release. In debug this may seed a fixture-backed Plex session, so
    // it must run before setupNetwork, which would otherwise try to refresh
    // connections against the real plex.tv and clear them.
    DebugHooks.onApplicationCreate(this)
    setupNetwork(plexPrefs)
    updateDownloadedFileState()
    super.onCreate()
  }

  /**
   * Updates the book and track repositories to reflect the true state of downloaded files
   */
  private fun updateDownloadedFileState() {
    applicationScope.launch {
      withContext(Dispatchers.IO) {
        cachedFileManager.refreshTrackDownloadedStatus()
      }
      // A download interrupted by a Wi-Fi drop or a process death used to stay abandoned:
      // one retry, then nothing re-enqueued it (cu-76). Launch is the first chance to pick
      // it back up.
      cachedFileManager.resumeInterruptedDownloads()
    }
  }

  open fun initializeComponent(): AppComponent {
    // We pass the applicationContext that will be used as Context in the graph
    return DaggerAppComponent.builder().appModule(AppModule(this)).build()
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
     * the user was never told (decision-17, cu-73).
     *
     * Only an explicit 401 qualifies. A timeout, a connection error and a 5xx are all *not* this:
     * being offline is not being signed out (cu-84), and treating it as such would nag every user
     * on a train.
     */
    fun isAccountRejection(e: Throwable): Boolean = e is HttpException && e.code() == HTTP_UNAUTHORIZED

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
              withContext(Dispatchers.Main) {
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
        // and discarded on every launch (cu-10).
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
