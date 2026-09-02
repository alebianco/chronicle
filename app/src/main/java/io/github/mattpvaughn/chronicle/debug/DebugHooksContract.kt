package io.github.mattpvaughn.chronicle.debug

import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.sources.plex.ProgressApi
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection

/**
 * The shape both `DebugHooks` twins must have.
 *
 * `app/src/debug/` and `app/src/release/` each provide their own `DebugHooks` object — the debug
 * one drives the fixture-backed mock Plex session, the release one is entirely no-ops, so the mock
 * machinery is not merely disabled in release but never compiled into it.
 *
 * Nothing used to enforce that the two matched. Adding a hook to the debug twin and forgetting the
 * release stub produces a **release-only compile failure**, and `verify.sh` builds only the debug
 * variant — so it would pass review and break the first release build. That nearly happened twice:
 * `onPlayBookIntent` in cu-64 and `onFailSyncIntent` in cu-9, each caught only because the same
 * session happened to build both variants.
 *
 * Declaring the contract here makes the compiler enforce it in *both* variants: a twin missing a
 * member fails to compile as an incomplete implementation, in whichever variant is being built.
 * `verify.sh` also compiles the release variant now as a backstop for anything this cannot express
 * (cu-70).
 */
interface DebugHooksContract {
  /** Called from `ChronicleApplication.onCreate`, before `setupNetwork`. */
  fun onApplicationCreate(application: ChronicleApplication)

  /** Called from `MainActivity.onCreate` and `onNewIntent`. */
  fun onMainActivityIntent(intent: Intent?)

  /** Called once the media service is connected, from both `onCreate` and `onNewIntent`. */
  fun onPlayBookIntent(
    intent: Intent?,
    mediaServiceConnection: MediaServiceConnection,
  )

  /** Called from `MainActivity.onCreate` and `onNewIntent`. */
  fun onFailSyncIntent(intent: Intent?)

  /**
   * Wraps the [ProgressApi] the progress worker reports through.
   *
   * Exists so `--ez fail_sync true` works against a **real** Plex server, not only in mock mode
   * (cu-73). The previous hook set a flag on the fixture server, which is null unless mock mode is
   * running — so on a live server it was a silent no-op, and the "position not synced" badge could
   * not be reached at all without an actual server outage.
   *
   * Release returns [api] unchanged, so there is no wrapper and no branch in a release build.
   */
  fun wrapProgressApi(api: ProgressApi): ProgressApi

  /**
   * Expands the currently-playing sheet, so the player — and the "position not synced" badge on
   * it — can be reached without tap coordinates (cu-73).
   *
   * Takes the activity's lifecycle owner and view model rather than the activity, so the debug
   * twin can observe playback state: the sheet is HIDDEN until a playback state arrives, and an
   * immediate expand would be a no-op.
   */
  fun onShowPlayerIntent(
    intent: Intent?,
    lifecycleOwner: LifecycleOwner,
    viewModel: MainActivityViewModel,
  )
}
