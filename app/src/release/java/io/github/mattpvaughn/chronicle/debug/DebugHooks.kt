package io.github.mattpvaughn.chronicle.debug

import android.content.Intent
import io.github.mattpvaughn.chronicle.application.ChronicleApplication

/**
 * Release build: every debug hook is a no-op.
 *
 * The debug variant provides a different implementation of this same object.
 * Keeping the seam here — rather than a `BuildConfig.DEBUG` branch in shared
 * code — means the mock-Plex machinery is not merely disabled in release, it is
 * not compiled into it at all.
 */
object DebugHooks {
  fun onApplicationCreate(application: ChronicleApplication) = Unit

  fun onMainActivityIntent(intent: Intent?) = Unit
}
