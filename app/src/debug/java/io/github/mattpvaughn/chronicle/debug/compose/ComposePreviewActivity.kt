package io.github.mattpvaughn.chronicle.debug.compose

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsContent
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsScreen
import io.github.mattpvaughn.chronicle.features.collections.compose.CollectionsUiState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import timber.log.Timber

/**
 * Renders a Compose screen on a real device with no server and no login.
 *
 * A POC that only ever runs under `createComposeRule()` proves the logic and nothing about how it
 * *looks* — and every UI bug this migration is meant to prevent was
 * a bug about appearance on a real screen in a real orientation. So the screen has to be seen.
 *
 * **Debug-only, and deliberately not wired into `CollectionsFragment`.** That Fragment also owns
 * search, pull-to-refresh, toasts and a bottom-sheet chooser; half-migrating it would put an
 * unfinished screen in front of the household. This activity renders the composable alone, so the
 * production Collections screen is untouched until the migration is a whole task.
 *
 * It lives in `src/debug` rather than behind a `DebugHooks` method on purpose: the contract has a
 * release twin that must be kept in step, and a standalone activity needs neither.
 *
 * ```
 * adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.debug.compose.ComposePreviewActivity
 * adb shell am start -n ... --es state empty     # or: offline, loaded (default)
 * ```
 */
class ComposePreviewActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val requested = intent?.getStringExtra(EXTRA_STATE) ?: STATE_LOADED
    Timber.i("ComposePreviewActivity rendering state=$requested")

    val content =
      when (requested) {
        STATE_EMPTY -> CollectionsContent.Empty
        STATE_OFFLINE -> CollectionsContent.OfflineEmpty
        else -> CollectionsContent.Loaded(SAMPLE_COLLECTIONS)
      }

    setContent {
      ChronicleTheme {
        CollectionsScreen(
          state = CollectionsUiState(content = content),
          // No server in this harness, so no cover art. The layout must hold up without it —
          // which is itself worth seeing, since it is what an offline device shows.
          coverUrl = { "" },
          onCollectionClick = { Timber.i("preview: tapped ${it.title}") },
          onDisableOfflineMode = { Timber.i("preview: disable offline mode") },
        )
      }
    }
  }

  companion object {
    const val EXTRA_STATE = "state"
    const val STATE_LOADED = "loaded"
    const val STATE_EMPTY = "empty"
    const val STATE_OFFLINE = "offline"

    private val SAMPLE_COLLECTIONS =
      listOf(
        "Mistborn",
        "The Stormlight Archive",
        "Discworld",
        "The Expanse",
        "Baby Ganesh Agency",
        "Wheel of Time",
        "Foundation",
        "Dune",
      ).mapIndexed { index, title ->
        Collection(id = "c$index", source = SourceId("plex:preview"), title = title)
      }
  }
}
