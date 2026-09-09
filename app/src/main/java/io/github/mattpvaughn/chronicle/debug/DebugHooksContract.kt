package io.github.mattpvaughn.chronicle.debug

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ProgressApi
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.ChronicleScreen
import kotlinx.coroutines.CoroutineScope

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
 * `onPlayBookIntent` and `onFailSyncIntent` each broke this way in turn, each caught only because
 * the same session happened to build both variants.
 *
 * Declaring the contract here makes the compiler enforce it in *both* variants: a twin missing a
 * member fails to compile as an incomplete implementation, in whichever variant is being built.
 * `verify.sh` also compiles the release variant now as a backstop for anything this cannot express.
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

  /**
   * Starts a download for a book id, so a sync can be driven from a script.
   *
   * The book details screen cannot be reached by `input tap` — the currently-playing sheet
   * intercepts the coordinates, the same obstacle recorded for the bottom navigation — and
   * `play_book` opens the player rather than details. So a download had no scriptable entry point
   * at all, which is what left the exhausted-retry item unverified.
   *
   * Goes through `ICachedFileManager.downloadTracks`, the call the download button makes, rather
   * than enqueueing with Fetch2 directly: a hook that bypasses the real path proves nothing about
   * it (the same reasoning that applies to `play_book`).
   */
  fun onDownloadBookIntent(
    intent: Intent?,
    cachedFileManager: ICachedFileManager,
    bookRepository: IBookRepository,
    scope: CoroutineScope,
  )

  /** Called from `MainActivity.onCreate` and `onNewIntent`. */
  fun onFailSyncIntent(intent: Intent?)

  /**
   * Replaces the stored **server** access token with a wrong-but-non-empty value, so the
   * 401 → refresh → retry path can be exercised against a real server.
   *
   * That path cannot be reached any other way. A rotation performed server-side does **not** get
   * there: Plex keeps honouring the superseded token, and `ChronicleApplication.setupNetwork`
   * re-fetches `/api/v2/resources` on every launch and adopts the new one *before* any
   * authenticated request — measured, zero 401s. Editing the prefs file does not work
   * either, because `SharedPreferences` caches in memory.
   *
   * Called from `MainActivity`, i.e. **after** `setupNetwork`, or the startup refresh would repair
   * the token before it was ever used.
   */
  fun onInvalidateServerTokenIntent(intent: Intent?)

  /**
   * Wraps the [ProgressApi] the progress worker reports through.
   *
   * Exists so `--ez fail_sync true` works against a **real** Plex server, not only in mock mode.
   * The previous hook set a flag on the fixture server, which is null unless mock mode is
   * running — so on a live server it was a silent no-op, and the "position not synced" badge could
   * not be reached at all without an actual server outage.
   *
   * Release returns [api] unchanged, so there is no wrapper and no branch in a release build.
   */
  fun wrapProgressApi(api: ProgressApi): ProgressApi

  /**
   * Opens the browse-by-facet screen when `show_browse` is set.
   *
   * Takes a **navigate-by-route callback** rather than a `Navigator`, which was deleted when this
   * moved off it. The reason for the hook is unchanged but narrower than it was written: a tab *can*
   * be driven by `adb shell input tap` once the menu's centred inset is accounted for (measured
   * 2026-09-05), so what this really buys is a coordinate-free route that survives a different
   * screen size or a scrolled list.
   */
  fun onShowBrowseIntent(
    intent: Intent?,
    activity: FragmentActivity,
    navigateTo: (ChronicleScreen) -> Unit,
  )

  /**
   * Opens the settings screen, so it can be checked without tapping a bottom-nav tab.
   *
   * See [onShowBrowseIntent] on why this takes a route callback.
   */
  fun onShowSettingsIntent(
    intent: Intent?,
    activity: FragmentActivity,
    navigateTo: (ChronicleScreen) -> Unit,
  )

  /**
   * Expands the currently-playing sheet, so the player — and the "position not synced" badge on
   * it — can be reached without tap coordinates.
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

  /**
   * Moves the sync location to another volume and runs the move worker, as the settings screen
   * does.
   *
   * Exists because the question — whether changing the sync location strands partial downloads on
   * the old volume — needs **two real volumes**, so it cannot be answered by the fixture pack, and
   * the settings control behind it is not reachable from `adb shell input tap` any more than the
   * bottom nav is. This runs exactly what `SettingsViewModel.setSyncLocation` runs: set the pref,
   * then enqueue `MoveSyncLocationWorker`.
   */
  fun onMoveSyncLocationIntent(
    intent: Intent?,
    activity: FragmentActivity,
  )
}
