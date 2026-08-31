package io.github.mattpvaughn.chronicle.debug

import android.content.Intent
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
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
}
