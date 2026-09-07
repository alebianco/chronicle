package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.github.michaelbull.result.Result
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.data.sources.HttpMediaSource
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.ktor.client.statement.HttpStatement
import javax.inject.Inject

/** A [MediaSource] wrapping Plex media server and its media calls via audio libraries */
class PlexMediaSource
  @Inject
  constructor(
    private val plexConfig: PlexConfig,
    private val plexMediaService: PlexMediaService,
    private val plexLoginRepo: IPlexLoginRepo,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val appContext: Context,
    defaultDataSourceFactory: DefaultHttpDataSource.Factory,
  ) : HttpMediaSource {
    /**
     * The connected Plex **server**, not "Plex" (decision-21).
     *
     * Read from prefs on each access rather than captured once: the user can switch servers
     * without this object being rebuilt, and a stale id would scope writes to the previous
     * server — filing the new server's books where the next refresh of the old one would delete
     * them (the removal rule).
     */
    override val id: SourceId
      get() = SourceId.forPlexServer(plexPrefsRepo.server?.serverId.orEmpty())

    override val dataSourceFactory: DefaultDataSource.Factory =
      DefaultDataSource.Factory(
        appContext,
        defaultDataSourceFactory,
      )

    override val isDownloadable: Boolean = true

    // Plex carries all three: narrator via Style tags and series via Mood tags (the
    // Audnexus/seanap convention, not music semantics), and progress server-side via
    // viewOffset.
    override val hasNarrator: Boolean = true
    override val hasSeries: Boolean = true
    override val hasServerProgress: Boolean = true

    override suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable> {
      TODO("Not yet implemented")
    }

    override suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable> {
      TODO("Not yet implemented")
    }

    override suspend fun fetchAdditionalTrackInfo(): MediaItemTrack {
      TODO("Not yet implemented")
    }

    override suspend fun fetchStream(url: String): HttpStatement {
      TODO("Not yet implemented")
    }

    override suspend fun updateProgress(
      mediaItemTrack: MediaItemTrack,
      playbackState: String,
    ) {
      TODO("Not yet implemented")
    }

    override suspend fun sendMediaSessionStartCommand() {
      TODO("Not yet implemented")
    }

    override suspend fun isReachable(): Boolean {
      TODO("Not yet implemented")
    }

    override fun makeDownloadUrl(trackUrl: String): String {
      TODO("Not yet implemented")
    }

    override fun makeImageRequestHeaders(): Any? {
      TODO("Not yet implemented")
    }

    override fun toServerString(relativePathForResource: String): String {
      TODO("Not yet implemented")
    }
  }
