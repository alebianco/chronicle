package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.tonyodev.fetch2.Request
import com.tonyodev.fetch2core.Extras
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionResult.Failure
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionResult.Success
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig.ConnectionState.*
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.features.download.EXTRA_BOOK_ID
import io.github.mattpvaughn.chronicle.features.download.downloadGroupId
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Responsible for the configuration of the Plex.
 *
 * Eventually will provide the sole interface for interacting with the Plex remote source.
 *
 * TODO: merge the behavior here into [PlexMediaSource]
 */
@Singleton
class PlexConfig
  @Inject
  constructor(
    private val plexPrefsRepo: PlexPrefsRepo,
    private val connectionChooser: ConnectionChooser,
    private val appContext: Context,
    private val dispatchers: DispatcherProvider,
  ) {
    /**
     * Scope for the derived [isConnected] only.
     *
     * `PlexConfig` is a `@Singleton` and lives for the process, so this never needs cancelling —
     * `SupervisorJob` so a failure in one derivation cannot take the scope down.
     *
     * `Unconfined`, deliberately **not** `Main.immediate`: the derivation is a pure `map` over a
     * `StateFlow` with no view work in it, so it needs no particular thread, and it must stay
     * readable synchronously right after a write. `Main.immediate` is also evaluated when this
     * object is constructed, which made every unit test that builds a `PlexConfig` fail with
     * "Dispatchers.Main was accessed when the platform dispatcher was absent" unless it installed
     * a `MainDispatcherRule` it had no other need for.
     */
    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val connectionSet = mutableSetOf<Connection>()

    var url: String = PLACEHOLDER_URL

    private val _connectionState = MutableStateFlow(NOT_CONNECTED)
    val connectionState: StateFlow<ConnectionState>
      get() = _connectionState

    /**
     * Whether the server is reachable — derived from [connectionState], not stored beside it.
     *
     * This used to be a second `MutableLiveData` kept in sync by an anonymous subclass that
     * overrode both `postValue` and `setValue` to mirror into it. Two fields holding one
     * fact, updated by hand in two overrides: the flag could disagree with the state for a frame,
     * and every new writer had to remember both. `map` makes the derivation the only definition.
     */
    val isConnected: StateFlow<Boolean> =
      _connectionState
        .map { it == CONNECTED }
        .stateIn(configScope, SharingStarted.Eagerly, false)

    enum class ConnectionState {
      CONNECTING,
      NOT_CONNECTED,
      CONNECTED,
      CONNECTION_FAILED,
    }

    val sessionIdentifier = Random.nextInt(until = 10000).toString()

    /**
     * Prepends the current server url to [relativePath] with exactly one `/` between them.
     *
     * The both-slashes branch used to emit **two**: it stripped the path's leading slash
     * and then added one back, `"$url/" + path.substring(1)`, which is precisely the case this
     * function exists to normalise. A doubled slash is not cosmetic to Plex — a path is matched,
     * not normalised — so a request on such a url would 404.
     *
     * It was **unreachable from live data**, verified against the real server rather than by
     * reading: every `uri` in `/api/v2/resources` and the url `ConnectionChooser` selects from them
     * come without a trailing slash, and the only other writer is `PLACEHOLDER_URL`. It is fixed
     * rather than deleted because [url] is a public `var` — nothing writes a trailing slash today,
     * but the type allows one, and a silently broken path is a poor failure mode for a hole that
     * costs one line to close.
     */
    fun toServerString(relativePath: String): String = "${url.trimEnd('/')}/${relativePath.trimStart('/')}"

    val plexMediaInterceptor = PlexInterceptor(plexPrefsRepo, this, isLoginService = false)
    val plexLoginInterceptor = PlexInterceptor(plexPrefsRepo, this, isLoginService = true)

    /** Attempt to load in a cached bitmap for the given thumbnail */
    suspend fun getBitmapFromServer(
      thumb: String?,
      requireCached: Boolean = false,
    ): Bitmap? {
      if (thumb.isNullOrEmpty()) {
        return null
      }

      // Retrieve cached album art from the image cache if available
      val imageSize = appContext.resources.getDimension(R.dimen.audiobook_image_width).toInt()
      val uri =
        if (thumb.startsWith("http")) {
          thumb.toUri()
        } else {
          Timber.i("Taking part uri")
          toServerString(
            "photo/:/transcode?width=$imageSize&height=$imageSize&url=$thumb",
          ).toUri()
        }

      Timber.i("Notification thumb uri is: $uri")
      return withContext(dispatchers.io) {
        try {
          val request =
            ImageRequest.Builder(appContext)
              .data(uri)
              // Same query-based key as the cover binding adapter, so a notification
              // reuses artwork already cached for the library screen regardless of
              // which route (LAN/WAN/relay) fetched it.
              .memoryCacheKey(uri.query ?: uri.toString())
              .build()
          val result = SingletonImageLoader.get(appContext).execute(request)
          val bm = result.image?.toBitmap()
          if (bm == null) {
            Timber.e("Failed to retrieve album art for $thumb")
          } else {
            Timber.i("Successfully retrieved album art for $thumb")
          }
          bm
        } catch (t: Throwable) {
          Timber.e(t, "Failed to retrieve album art for $thumb")
          null
        }
      }
    }

    fun makeDownloadRequest(
      trackSource: String,
      bookId: String,
      bookTitle: String,
      downloadLoc: String,
    ): Request {
      Timber.i("Preparing download request for: ${Uri.parse(toServerString(trackSource))}")
      val token = plexPrefsRepo.server?.accessToken ?: plexPrefsRepo.accountAuthToken
      val remoteUri = "${toServerString(trackSource)}?download=1"
      return Request(remoteUri, downloadLoc).apply {
        tag = bookTitle
        // Fetch2's grouping API is Int-only, so the book id is hashed for the group id — and
        // carried verbatim in extras, because the listeners get a groupId back and need the real
        // id to update the database. A hash cannot be reversed.
        groupId = downloadGroupId(bookId)
        extras = Extras(mapOf(EXTRA_BOOK_ID to bookId))
        addHeader("X-Plex-Token", token)
      }
    }

    fun makeThumbUri(part: String): Uri {
      val imageSize = appContext.resources.getDimension(R.dimen.audiobook_image_width).toInt()
      val plexThumbPart = "photo/:/transcode?width=$imageSize&height=$imageSize&url=$part"
      val uri = Uri.parse(toServerString(plexThumbPart))
      return uri.buildUpon()
        .appendQueryParameter(
          "X-Plex-Token",
          plexPrefsRepo.server?.accessToken ?: plexPrefsRepo.accountAuthToken,
        ).build()
    }

    fun setPotentialConnections(connections: List<Connection>) {
      connectionSet.clear()
      connectionSet.addAll(connections)
    }

    /**
     * Indicates to observers that connectivity has been lost, but does not update URL yet, as
     * querying a possibly dead url has a better chance of success than querying no url
     */
    fun connectionHasBeenLost() {
      _connectionState.value = NOT_CONNECTED
    }

    private var prevConnectToServerJob: CompletableJob? = null

    fun connectToServer(plexMediaService: PlexMediaService) {
      prevConnectToServerJob?.cancel("Killing previous connection attempt")
      _connectionState.value = CONNECTING
      prevConnectToServerJob =
        Job().also {
          val context = CoroutineScope(it + dispatchers.main)
          context.launch {
            val connectionResult = chooseViableConnections(plexMediaService)
            Timber.i("Returned connection $connectionResult")
            if (connectionResult is Success && connectionResult.url != PLACEHOLDER_URL) {
              url = connectionResult.url
              _connectionState.value = CONNECTED
              Timber.i("Connection success: $url")
            } else {
              _connectionState.value = CONNECTION_FAILED
            }
          }
        }
    }

    /** Clear server data from [plexPrefsRepo] and [url] managed by [PlexConfig] */
    fun clear() {
      plexPrefsRepo.clear()
      _connectionState.value = NOT_CONNECTED
      url = PLACEHOLDER_URL
      connectionSet.clear()
    }

    fun clearServer() {
      _connectionState.value = NOT_CONNECTED
      url = PLACEHOLDER_URL
      plexPrefsRepo.server = null
      plexPrefsRepo.library = null
    }

    fun clearLibrary() {
      plexPrefsRepo.library = null
    }

    fun clearUser() {
      plexPrefsRepo.library = null
      plexPrefsRepo.server = null
      plexPrefsRepo.user = null
    }

    sealed class ConnectionResult {
      data class Success(val url: String) : ConnectionResult()

      data class Failure(val reason: String) : ConnectionResult()
    }

    /**
     * Picks a connection via [ConnectionChooser], which prefers LAN, then direct WAN, then
     * relay. The previous implementation launched every attempt at once and polled them, so
     * a relay could win a race against a LAN address it should never have been in.
     */
    private suspend fun chooseViableConnections(plexMediaService: PlexMediaService): ConnectionResult {
      val chosen =
        connectionChooser.choose(connectionSet.toList()) { connection ->
          plexMediaService.checkServer(connection.uri).isSuccessful
        }
      return if (chosen != null) {
        Success(chosen.uri)
      } else {
        Failure("No connection answered")
      }
    }
  }
