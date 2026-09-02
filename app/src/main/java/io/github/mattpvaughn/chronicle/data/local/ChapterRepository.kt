package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.assembleChapters
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.toChapter
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A repository abstracting all [Chapter]s.
 *
 * **Scaffolding, not load-bearing.** Nothing injects this, and no Dagger module provides it or
 * [ChapterDatabase]. Chapters currently live serialized inside `Audiobook.chapters` via
 * `ChapterListConverter`, and the live chapter fetch is in `BookRepository.loadChapterData`.
 *
 * [[cu-49]] moves chapters into their own table and makes this real. Until then, treat it the
 * way CLAUDE.md describes the `MediaSource` seam: don't call it, don't delete it.
 *
 * It is also why cu-13's "chapter logic has no Plex imports" holds only for the *live* path —
 * `Chapter.kt` itself is Plex-free, but this dead file still fetches through `PlexMediaService`.
 * cu-49 should take its data through the source seam rather than inherit these imports.
 */
interface IChapterRepository {
  /**
   * Loads m4b chapter data and any other audiobook details which are not loaded in by default
   * by [BookRepository] or [TrackRepository] and saves results to the DB
   */
  suspend fun loadChapterData(
    isAudiobookCached: Boolean,
    tracks: List<MediaItemTrack>,
  )
}

@Singleton
class ChapterRepository
  @Inject
  constructor(
    private val chapterDao: ChapterDao,
    private val prefsRepo: PrefsRepo,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexMediaService: PlexMediaService,
    private val dispatchers: DispatcherProvider,
  ) : IChapterRepository {
    override suspend fun loadChapterData(
      isAudiobookCached: Boolean,
      tracks: List<MediaItemTrack>,
    ) = withContext(dispatchers.io) {
      // Ids only: the full `MediaItemTrack` list is large and this adds nothing diagnostic
      // over knowing which tracks were asked for (cu-110).
      Timber.i("Loading chapter data for tracks: ${tracks.map { it.id }}")
      val chapters: List<Chapter> =
        try {
          // Shares assembleChapters with BookRepository, which is where the running offset
          // lives — both used to fall back to `track.asChapter(0L)` per track (cu-49).
          assembleChapters(tracks) { track ->
            val networkChapters =
              plexMediaService.retrieveChapterInfo(track.id)
                .plexMediaContainer.metadata.firstOrNull()?.plexChapters
            if (BuildConfig.DEBUG) {
              // prevent networkChapters from toString()ing and being slow even if timber
              // tree isn't attached in the release build
              Timber.i("Network chapters: $networkChapters")
            }
            networkChapters.orEmpty().map { plexChapter ->
              plexChapter.toChapter(
                trackId = track.id,
                trackDiscNumber = track.discNumber,
                downloaded = isAudiobookCached,
                // The track's parentKey is its book; no book is passed to this function.
                bookId = track.parentKey,
              )
            }
          }
        } catch (t: Throwable) {
          Timber.e(t, "Failed to load chapters")
          emptyList()
        }
      chapterDao.insertAll(chapters)
    }
  }
