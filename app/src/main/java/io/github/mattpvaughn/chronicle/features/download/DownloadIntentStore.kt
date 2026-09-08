package io.github.mattpvaughn.chronicle.features.download

import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.mattpvaughn.chronicle.data.local.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which tracks the user has asked to download, surviving process death.
 *
 * **This exists because removing Fetch2 removed a durable queue**, and one specific safety rule
 * depended on it. `partialsSafeToPrune` deletes a partial only when all three of these hold: it is
 * incomplete, the database does not call it cached, and **the download engine has no record of
 * it**. That third check is what stops the prune from deleting a `PAUSED` or `FAILED` download
 * that a `Range` request could have resumed cheaply.
 *
 * Fetch2 answered it from its own SQLite queue, which outlived a restart. [KtorDownloader] tracks
 * in-flight jobs in memory, so after a process restart it knows about **nothing** — and every
 * resumable partial on disk would become "safe to prune". That is not a small regression: it is
 * the app deleting the user's partly-downloaded audio, in the area where four prior tasks already
 * have failure modes ending in deleted audio.
 *
 * So the intent is recorded here instead, and it is recorded on the *intent* rather than on
 * progress: a track enters when it is enqueued and leaves only when it completes, is cancelled, or
 * its book is deleted. A failure deliberately leaves it in place, because a failed download is
 * exactly the resume candidate the rule protects.
 *
 * ### Why prefs rather than Room
 *
 * The data is a set of ids with no relations and no queries beyond membership, and it must be
 * readable during the cache scan without an extra migration to a database whose five schemas are
 * already under migration tests. `BACKUP_SETTING_KEYS` gates settings export by key, and this key
 * is deliberately **not** in it — a download queue is device-local state, not a setting worth
 * restoring onto another device where the files do not exist.
 */
@Singleton
class DownloadIntentStore
  @Inject
  constructor(
    private val settings: SettingsDataStore,
  ) {
    /** The track ids currently wanted on disk. */
    fun pending(): Set<String> = settings.get(stringSetPreferencesKey(KEY), emptySet())

    fun add(trackIds: Collection<String>) {
      if (trackIds.isEmpty()) return
      write(pending() + trackIds)
    }

    fun remove(trackIds: Collection<String>) {
      if (trackIds.isEmpty()) return
      write(pending() - trackIds.toSet())
    }

    fun clear() = write(emptySet())

    /**
     * Persists [ids] as a set this class owns.
     *
     * `SharedPreferences.getStringSet` documents that the returned instance must not be mutated —
     * it may be the live one the prefs hold, in which case mutating it and putting it back can
     * compare equal to itself, persist nothing, and leave the queue silently not surviving a
     * restart. That would be exactly the failure this class exists to prevent.
     *
     * The callers above already avoid it by construction: `pending() + trackIds` and
     * `pending() - trackIds` both build **new** sets rather than mutating what they read. The
     * `HashSet` copy here is therefore belt-and-braces rather than the thing holding the property
     * up, and it is honest to say so — removing it does not fail any test, verified by sabotage.
     * It stays because it makes the ownership explicit at the one point that writes.
     */
    private fun write(ids: Set<String>) {
      settings.set(stringSetPreferencesKey(KEY), HashSet(ids))
    }

    private companion object {
      const val KEY = "download_intent_track_ids"
    }
  }
