package io.github.mattpvaughn.chronicle.application.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.MiniPlayer
import io.github.mattpvaughn.chronicle.features.currentlyplaying.compose.MiniPlayerState

/**
 * Feeds [MiniPlayer] from [MainActivityViewModel].
 *
 * Separate from `MiniPlayer` itself so that stays stateless and previewable — the same split every
 * other screen here uses.
 *
 * The four flows were four `collectWhileStarted` blocks in `MainActivity`, one of which carried
 * hand-written change detection (`boundBookTitle`/`boundBookThumb`) because `ProgressUpdater`
 * republishes the book once a second during playback and each re-bind cost a Dagger lookup, a
 * `Uri` parse and a Coil load. Collecting into one `data class` gets that from
 * recomposition skipping instead.
 */
@Composable
fun MiniPlayerHost(
  viewModel: MainActivityViewModel,
  plexConfig: PlexConfig,
  modifier: Modifier = Modifier,
) {
  val book by viewModel.audiobook.collectAsStateWithLifecycle()
  val chapterTitle by viewModel.currentChapterTitle.collectAsStateWithLifecycle()
  val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
  val isLoading by viewModel.isAudioLoading.collectAsStateWithLifecycle()

  MiniPlayer(
    state =
      MiniPlayerState(
        bookTitle = book.title,
        chapterTitle = chapterTitle,
        artworkUrl = book.thumb,
        isPlaying = isPlaying,
        isLoading = isLoading,
      ),
    coverUrl = plexConfig::toServerString,
    onClick = viewModel::onCurrentlyPlayingClicked,
    onPlayPauseClick = viewModel::pausePlayButtonClicked,
    modifier = modifier,
  )
}
