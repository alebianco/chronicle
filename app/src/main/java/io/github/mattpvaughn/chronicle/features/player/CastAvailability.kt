package io.github.mattpvaughn.chronicle.features.player

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import timber.log.Timber

/**
 * Whether the Cast SDK may be touched on this device at all.
 *
 * `CastContext.getSharedInstance(Context)` **throws** when Play services is missing, and the
 * `Task`-returning overload fails asynchronously. Neither is a theoretical branch here: both
 * development tablets are `Phh-Treble vanilla` GSIs with *zero* Google packages installed, so the
 * unavailable path is the only one that runs during development, and a crash on it would take the
 * whole media service down on every launch.
 *
 * This is also the reason no Cast type is referenced outside [CastPlayerProvider]: a class is
 * verified when it is first loaded, so merely *naming* `CastPlayer` in a widely-touched file risks
 * a `NoClassDefFoundError` on a device the app is otherwise expected to work on.
 */
interface CastAvailability {
  fun isCastSupported(): Boolean
}

class PlayServicesCastAvailability(
  private val context: Context,
) : CastAvailability {
  override fun isCastSupported(): Boolean {
    return try {
      val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
      val supported = status == ConnectionResult.SUCCESS
      if (!supported) {
        // Not an error: a de-Googled ROM is a supported configuration for this app.
        Timber.i("Cast unavailable, Play services status=$status")
      }
      supported
    } catch (e: Throwable) {
      // isGooglePlayServicesAvailable is documented not to throw, but it reaches into a package
      // that may be absent or a stub on the GSIs this app targets. Refusing Cast is always safe.
      Timber.i(e, "Cast unavailable, Play services check failed")
      false
    }
  }
}
