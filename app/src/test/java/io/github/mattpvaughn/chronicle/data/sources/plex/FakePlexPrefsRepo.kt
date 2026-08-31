package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser

/**
 * An in-memory [PlexPrefsRepo].
 *
 * The real one wraps `SharedPreferences` and commits synchronously, so it needs Robolectric. These
 * fields are plain properties, which keeps a test about *which credentials survive a re-auth* from
 * also being a test of Android's preference storage.
 *
 * [clear] and [clearCredentials] deliberately duplicate the real implementations rather than
 * delegating: the difference between them **is** the behaviour under test, so a fake that guessed
 * at it would prove nothing.
 */
class FakePlexPrefsRepo : PlexPrefsRepo {
  override var accountAuthToken: String = ""
  override var user: PlexUser? = null
  override var library: PlexLibrary? = null
  override var server: ServerModel? = null
  override var oAuthTempId: Long = 0L
  override val uuid: String = "fake-uuid"

  override fun clear() {
    server = null
    library = null
    user = null
    accountAuthToken = ""
  }

  override fun clearCredentials() {
    accountAuthToken = ""
    user = user?.copy(authToken = "")
  }
}
