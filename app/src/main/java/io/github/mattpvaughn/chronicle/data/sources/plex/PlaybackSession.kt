package io.github.mattpvaughn.chronicle.data.sources.plex

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Plex-side of starting playback: the session handshake and the token that authorizes it.
 *
 * Both used to live inside `AudiobookMediaSessionCallback`, which reached the network through
 * `Injector.get().plexMediaService()` and assembled its own `X-Plex-Token` header — credential
 * plumbing inside a MediaSession command callback, and the thing that made the callback
 * untestable.
 *
 * The token precedence was **written out twice**, here and in `ServiceModule.plexDataSourceFactory`.
 * Two copies of a credential rule that must agree is a bug waiting to happen, so [authToken] is now
 * the single statement of it and both call sites read it.
 */
@Singleton
class PlaybackSession
  @Inject
  constructor(
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexMediaService: PlexMediaService,
  ) {
    /**
     * Tells the server a listening session has begun, so progress reports register against it.
     *
     * Returns whether the handshake happened. A failure is logged and swallowed rather than
     * thrown: `/playQueues` is an unofficial endpoint (see CLAUDE.md), and playback of already
     * resolved media must not be cancelled because the server declined to open a session — the
     * only cost is that the server-side "now playing" dashboard misses this session.
     */
    suspend fun start(bookId: String): Boolean {
      val serverId = plexPrefsRepo.server?.serverId
      if (serverId == null) {
        Timber.w("Unknown server id. Cannot start active session. Playback may not be saved")
        return false
      }
      return try {
        plexMediaService.startMediaSession(getMediaItemUri(serverId, bookId))
        true
      } catch (e: Throwable) {
        Timber.e(e, "Failed to start media session for book $bookId")
        false
      }
    }

    /**
     * The most privileged token currently held, for authorizing media requests.
     *
     * The server's own access token first — it is scoped to the server actually being streamed
     * from — then the profile's, then the account's. Answering the `TODO` on [PlexPrefsRepo],
     * which asked for exactly this and said it did not belong on that interface.
     *
     * **Empty counts as absent.** Both copies of this rule used `?:`, and neither `ServerModel`
     * nor `PlexUser` stores null for a missing token: `asServerModel` writes
     * `accessToken = this.accessToken ?: ""`, so a server that reports no token of its own — the
     * ordinary case for a server the user owns — stored `""`, won the elvis, and authorized every
     * media request with an **empty** `X-Plex-Token` while a perfectly good account token sat
     * unused. `ifNotBlank` is what makes the fallback chain actually fall through.
     *
     * Never logged; `TokenLoggingTest` fails the build on any Timber call that interpolates one.
     */
    val authToken: String
      get() =
        plexPrefsRepo.server?.accessToken?.ifNotBlank()
          ?: plexPrefsRepo.user?.authToken?.ifNotBlank()
          ?: plexPrefsRepo.accountAuthToken

    private fun String.ifNotBlank(): String? = takeIf { it.isNotBlank() }
  }
