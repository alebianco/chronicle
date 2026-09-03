package io.github.mattpvaughn.chronicle.debug

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.sources.plex.ProgressApi
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.Navigator

/**
 * Release build: every debug hook is a no-op.
 *
 * The debug variant provides a different implementation of this same object.
 * Keeping the seam here — rather than a `BuildConfig.DEBUG` branch in shared
 * code — means the mock-Plex machinery is not merely disabled in release, it is
 * not compiled into it at all.
 */
object DebugHooks : DebugHooksContract {
  override fun onApplicationCreate(application: ChronicleApplication) = Unit

  override fun onMainActivityIntent(intent: Intent?) = Unit

  override fun onPlayBookIntent(
    intent: Intent?,
    mediaServiceConnection: MediaServiceConnection,
  ) = Unit

  override fun onFailSyncIntent(intent: Intent?) = Unit

  override fun onInvalidateServerTokenIntent(intent: Intent?) = Unit

  /** Returns the api unchanged: no wrapper, and no failure injection, in a release build. */
  override fun wrapProgressApi(api: ProgressApi): ProgressApi = api

  override fun onShowBrowseIntent(
    intent: Intent?,
    activity: FragmentActivity,
    navigator: Navigator,
  ) = Unit

  override fun onShowPlayerIntent(
    intent: Intent?,
    lifecycleOwner: LifecycleOwner,
    viewModel: MainActivityViewModel,
  ) = Unit
}
