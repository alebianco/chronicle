@file:UnstableApi

package io.github.mattpvaughn.chronicle.features.player

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import timber.log.Timber

/**
 * Creates the [CastPlayer], and is the **only** file in the app that names a Cast SDK type.
 *
 * Class verification loads referenced types eagerly, so keeping these imports in one rarely-touched
 * file means a device without Play services never risks a `NoClassDefFoundError` from an unrelated
 * edit. [CastAvailability] is checked before anything here is called.
 */
class CastPlayerProvider(
  private val context: Context,
  private val castAvailability: CastAvailability,
) {
  /**
   * The live cast player, or null when Cast is unusable here.
   *
   * Typed as [Player] rather than `CastPlayer` so the Media3 unstable opt-in stops at this class:
   * a caller receives the stable interface, which is all `switchToPlayer` needs.
   *
   * Built lazily and remembered, including the null: `CastContext.getSharedInstance` is expensive
   * and its failure is permanent for the process, so retrying per playback would repeat a
   * multi-hundred-millisecond miss on exactly the devices that can never succeed.
   */
  private var resolved = false

  private var castPlayer: CastPlayer? = null

  fun castPlayerOrNull(): Player? {
    if (resolved) {
      return castPlayer
    }
    resolved = true
    if (!castAvailability.isCastSupported()) {
      return null
    }
    castPlayer =
      try {
        // The synchronous overload throws when the receiver app id cannot be resolved or Play
        // services is a stub; the availability check above does not cover either case.
        CastPlayer(CastContext.getSharedInstance(context))
      } catch (e: Throwable) {
        Timber.i(e, "Cast unavailable, CastContext could not be created")
        null
      }
    return castPlayer
  }

  /**
   * Reports cast sessions starting and ending, so the service can move playback between players.
   *
   * A no-op when Cast is unavailable, which keeps the caller free of null handling.
   */
  fun observeSessions(
    onAvailable: (Player) -> Unit,
    onUnavailable: () -> Unit,
  ) {
    val player = castPlayerOrNull() ?: return
    castPlayer?.setSessionAvailabilityListener(
      object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
          Timber.i("Cast session available")
          onAvailable(player)
        }

        override fun onCastSessionUnavailable() {
          Timber.i("Cast session unavailable")
          onUnavailable()
        }
      },
    )
  }

  fun release() {
    castPlayer?.setSessionAvailabilityListener(null)
  }
}

/**
 * Builds a [CastPlayerProvider] with the real Play-services availability check.
 *
 * A top-level function rather than a Dagger `@Provides` body for the reason
 * [artworkFreeExtractorsFactory] is one: a module method needs a live `Service` and so cannot be
 * reached from a unit test, which would leave the wiring unverified — and `injection/modules` is
 * held to a per-package coverage floor that untestable glue erodes.
 */
fun castPlayerProviderFor(context: Context): CastPlayerProvider = CastPlayerProvider(context, PlayServicesCastAvailability(context))
