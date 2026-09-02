package io.github.mattpvaughn.chronicle.data.sources.plex

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.ConnectionTier
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashSet

/** A interface for Plex exclusive preferences */
interface PlexPrefsRepo {
  /**
   * The active auth token for the active account/profile. A 20ish character string. Defaults to
   * empty string "" if user is not signed in
   */
  var accountAuthToken: String

  // TODO: exposes the most privileged token we currently have access to (via new class/func-
  //       wouldn't be appropriate to use this class for it)

  /** The active user profile */
  var user: PlexUser?

  /** The active plex library */
  var library: PlexLibrary?

  /** Reference to the connected server */
  var server: ServerModel?

  /**
   * Temporary id used by oAuth to identify the client. Provided by the server. Only valid for
   * a few minutes so no strong need to clear it after login
   */
  var oAuthTempId: Long

  /** Unique user id */
  val uuid: String

  /** Clear all preferences which are handled by PrefsRepo */
  fun clear()

  /**
   * Clears only the credentials, keeping the chosen user, server and library.
   *
   * The difference between re-authenticating and logging out. A Plex token can be invalidated
   * server-side (a password change with "sign out connected devices", a server re-claim) while the
   * user's choice of server and library remains perfectly good — so the recovery is a fresh OAuth
   * PIN, not re-picking a library they already picked (cu-84).
   *
   * The per-user token on [user] goes too: it is derived from the account token, so keeping it
   * would leave a dead credential that outlives the re-auth.
   */
  fun clearCredentials()
}

/** An implementation of [PlexPrefsRepo] wrapping [SharedPreferences]. */
class SharedPreferencesPlexPrefsRepo
  @Inject
  constructor(
    private val prefs: SharedPreferences,
    private val moshi: Moshi,
  ) : PlexPrefsRepo {
    /** Guards the legacy-load log; see [legacyServerConnections]. */
    private var hasLoggedLegacyConnections = false

    /**
     * Reused rather than rebuilt per call: `Moshi.adapter` walks its reflective factory list on
     * every lookup, and this is on the launch path.
     */
    private val connectionsAdapter =
      moshi.adapter<List<Connection>>(
        Types.newParameterizedType(List::class.java, Connection::class.java),
      )

    private companion object {
      const val PREFS_AUTH_TOKEN_KEY = "auth_token"
      const val PREFS_LIBRARY_NAME_KEY = "library_name"
      const val PREFS_LIBRARY_ID_KEY = "library_id"
      const val PREFS_SERVER_NAME_KEY = "server_name"
      const val PREFS_SERVER_ACCESS_TOKEN = "server_token"
      const val PREFS_SERVER_IS_OWNED = "server_owned"
      const val PREFS_SERVER_ID_KEY = "server_id"
      const val PREFS_USER = "user"

      /**
       * The chosen server's connections, as a serialized `List<Connection>`.
       *
       * Replaces the two keys below, which stored **bare URI strings** and so lost `local`,
       * `relay` and `protocol` — every connection read back as [ConnectionTier.DIRECT] and
       * cu-11's tiering did nothing from the second launch onwards (cu-107).
       */
      const val PREFS_SERVER_CONNECTIONS_KEY = "server_connections_v2"

      /**
       * Legacy connection keys, read for migration and then removed.
       *
       * Both were written the *same* complete list of URIs — the names implied a partition that
       * the code never made — so the flags cannot be recovered from them. They are re-derived
       * from the next `/api/v2/resources` refresh instead, which happens on every launch via
       * `mergeServerRefresh`.
       */
      const val PREFS_LOCAL_SERVER_CONNECTIONS_KEY = "local_server_connections"
      const val PREFS_REMOTE_SERVER_CONNECTIONS_KEY = "remote_server_connections"
      const val PREFS_UUID_KEY = "uuid"
      const val PREFS_TEMP_ID = "id"
      const val NO_TEMP_ID_FOUND = -1L
    }

    override val uuid: String
      @SuppressLint("ApplySharedPref")
      get() {
        var tempUUID = getString(PREFS_UUID_KEY, "")
        if (tempUUID.isEmpty()) {
          val generatedUUID = UUID.randomUUID().toString()
          prefs.edit().putString(PREFS_UUID_KEY, generatedUUID).commit()
          tempUUID = generatedUUID
        }
        return tempUUID
      }

    override var accountAuthToken: String
      get() = getString(PREFS_AUTH_TOKEN_KEY)

      @SuppressLint("ApplySharedPref")
      set(value) {
        prefs.edit().putString(PREFS_AUTH_TOKEN_KEY, value).commit()
      }

    override var user: PlexUser?
      get() {
        val userString = prefs.getString(PREFS_USER, "")
        if (userString.isNullOrEmpty()) {
          return null
        }
        return moshi.adapter<PlexUser>(PlexUser::class.java).fromJson(userString)
      }

      @SuppressLint("ApplySharedPref")
      set(value) {
        if (value == null) {
          prefs.edit().remove(PREFS_USER).commit()
          return
        }
        val userString = moshi.adapter<PlexUser>(PlexUser::class.java).toJson(value)
        prefs.edit().putString(PREFS_USER, userString).commit()
      }

    override var library: PlexLibrary?
      get() {
        val name = getString(PREFS_LIBRARY_NAME_KEY)
        val id = getString(PREFS_LIBRARY_ID_KEY)
        if (name.isEmpty() || id.isEmpty()) {
          return null
        }
        return PlexLibrary(name, MediaType.ARTIST, id)
      }

      @SuppressLint("ApplySharedPref")
      set(value) {
        if (value == null) {
          prefs.edit()
            .remove(PREFS_LIBRARY_ID_KEY)
            .remove(PREFS_LIBRARY_NAME_KEY).commit()
          return
        }
        prefs.edit()
          .putString(PREFS_LIBRARY_NAME_KEY, value.name)
          .putString(PREFS_LIBRARY_ID_KEY, value.id).commit()
      }

    override var server: ServerModel?
      get() {
        val name = getString(PREFS_SERVER_NAME_KEY)
        val id = getString(PREFS_SERVER_ID_KEY)
        val token: String = getString(PREFS_SERVER_ACCESS_TOKEN)
        val owned: Boolean = prefs.getBoolean(PREFS_SERVER_IS_OWNED, true)

        val connections = getServerConnections()

        if (name.isEmpty() || token.isEmpty() || connections.isEmpty()) {
          return null
        }

        return ServerModel(name, connections, id, token, owned)
      }

      @SuppressLint("ApplySharedPref")
      set(value) {
        if (value == null) {
          prefs.edit()
            .remove(PREFS_SERVER_ID_KEY)
            .remove(PREFS_SERVER_ACCESS_TOKEN)
            .remove(PREFS_SERVER_IS_OWNED)
            .remove(PREFS_SERVER_CONNECTIONS_KEY)
            // The legacy keys go too, or a later read would resurrect the old flagless shape.
            .remove(PREFS_LOCAL_SERVER_CONNECTIONS_KEY)
            .remove(PREFS_REMOTE_SERVER_CONNECTIONS_KEY)
            .remove(PREFS_SERVER_NAME_KEY).commit()
          return
        }
        prefs.edit()
          .putString(PREFS_SERVER_NAME_KEY, value.name)
          .putString(PREFS_SERVER_ID_KEY, value.serverId)
          .putString(PREFS_SERVER_ACCESS_TOKEN, value.accessToken)
          .putBoolean(PREFS_SERVER_IS_OWNED, value.owned).commit()
        putConnections(value.connections)
      }

    /**
     * The stored connections, preferring the serialized form and falling back to the legacy one.
     *
     * The fallback must not fail loudly: returning an empty list here makes [server] read as null,
     * which presents as "no server chosen" and sends the user back through the chooser. An
     * upgrade must not do that, so a legacy install still loads — with `DIRECT` for everything,
     * exactly as before, until the next `/resources` refresh restores the real flags.
     */
    private fun getServerConnections(): List<Connection> {
      val serialized = prefs.getString(PREFS_SERVER_CONNECTIONS_KEY, null)
      if (!serialized.isNullOrEmpty()) {
        val parsed =
          try {
            connectionsAdapter.fromJson(serialized)
          } catch (e: JsonDataException) {
            Timber.e(e, "Stored connections are unreadable; falling back to the legacy keys")
            null
          } catch (e: IOException) {
            Timber.e(e, "Stored connections are unreadable; falling back to the legacy keys")
            null
          }
        if (parsed != null) {
          return parsed
        }
      }
      return legacyServerConnections()
    }

    /**
     * Connections from the pre-cu-107 keys, as bare URIs.
     *
     * The flags are unrecoverable here — both keys were written the same full list, so the
     * union below is a formality kept only in case a hand-edited install has them differing.
     * `Connection(uri)` leaves `local` and `relay` false, which is the old behaviour; it is
     * corrected on the next `/resources` refresh rather than guessed from the URI shape, since a
     * wrong guess would re-introduce the mis-tiering this fix exists to remove.
     */
    private fun legacyServerConnections(): List<Connection> {
      val local = getStringSet(PREFS_LOCAL_SERVER_CONNECTIONS_KEY)
      val remote = getStringSet(PREFS_REMOTE_SERVER_CONNECTIONS_KEY)
      val uris = (local union remote).filter { it.isNotEmpty() }
      // Logged once per process, not once per read: `server` is a computed getter and the
      // launch path reads it repeatedly, which turned one migration into seven identical lines.
      if (uris.isNotEmpty() && !hasLoggedLegacyConnections) {
        hasLoggedLegacyConnections = true
        Timber.i(
          "Loaded ${uris.size} connection(s) from the pre-cu-107 keys; tiers will be " +
            "re-derived on the next /resources refresh",
        )
      }
      return uris.map { Connection(uri = it) }
    }

    // TODO: ensure this is only usable for a certain amount of time
    override var oAuthTempId: Long
      get() = prefs.getLong(PREFS_TEMP_ID, NO_TEMP_ID_FOUND)

      @SuppressLint("ApplySharedPref")
      set(value) {
        prefs.edit().putLong(PREFS_TEMP_ID, value).commit()
      }

    override fun clear() {
      server = null
      library = null
      user = null
      accountAuthToken = ""
    }

    override fun clearCredentials() {
      accountAuthToken = ""
      // Keep the identity, drop the derived token: the same person, needing a new credential.
      user = user?.copy(authToken = "")
    }

    /**
     * Stores [connections] whole, flags included.
     *
     * Was two `putStringSet` calls holding bare URIs — and, both times, the *same* complete list
     * despite the keys being named local and remote, so the partition they implied never
     * happened (cu-107). The legacy keys are removed here rather than left behind, so a
     * downgrade-then-upgrade cannot read a stale flagless copy.
     */
    @SuppressLint("ApplySharedPref")
    private fun putConnections(connections: List<Connection>) {
      prefs.edit()
        .putString(PREFS_SERVER_CONNECTIONS_KEY, connectionsAdapter.toJson(connections))
        .remove(PREFS_LOCAL_SERVER_CONNECTIONS_KEY)
        .remove(PREFS_REMOTE_SERVER_CONNECTIONS_KEY)
        .commit()
    }

    private fun getStringSet(key: String): MutableSet<String> {
      return prefs.getStringSet(key, HashSet<String>()) ?: HashSet()
    }

    /**
     * Retrieve a string stored in shared preferences
     *
     * @param key the key of the item stored in preferences
     * @param defaultValue (optional) the value to return if the desired string cannot be found.
     *                     Defaults to the empty string
     *
     * @return the stored preference value corresponding to the [key] passed in. If there is no
     * corresponding value, return the default value provided
     *
     */
    private fun getString(
      key: String,
      defaultValue: String = "",
    ): String {
      return prefs.getString(key, defaultValue) ?: defaultValue
    }
  }
