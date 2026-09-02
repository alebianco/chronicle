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
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject
import javax.inject.Named
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
    @Named(AppModule.AUTH_PREFS) private val authPrefs: SharedPreferences,
    private val moshi: Moshi,
  ) : PlexPrefsRepo {
    init {
      migrateCredentialsToAuthPrefs()
      removeOrphanedPremiumKeys()
    }

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

      /**
       * The keys that live in the credentials file (cu-108).
       *
       * `uuid` and `id` are deliberately **not** here: a client identifier and an OAuth temp id
       * are not credentials, and `uuid` is a stable device identity that should survive a
       * restore. Nor are the server and library choices, which are harmless and worth restoring.
       */
      val CREDENTIAL_KEYS =
        listOf(PREFS_AUTH_TOKEN_KEY, PREFS_SERVER_ACCESS_TOKEN, PREFS_USER)

      /**
       * Set once the credential migration has completed, in the same `commit()` as the values.
       *
       * In the auth file rather than the settings file on purpose: the marker and the data it
       * describes must land together or not at all.
       */
      const val PREFS_AUTH_MIGRATED = "credentials_migrated"

      /**
       * Inert since cu-60, but still on disk for anyone who installed before it.
       *
       * `key_premium_token` held a **Play purchase token**. cu-77's allowlist keeps it out of
       * exports, but deleting it outright is strictly better than merely not copying it.
       */
      val ORPHANED_PREMIUM_KEYS = listOf("key_is_premium", "key_premium_token")

      /** Set once the premium keys have been removed, so the check costs nothing thereafter. */
      const val PREFS_PREMIUM_KEYS_REMOVED = "premium_keys_removed"
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
      get() = credentialString(PREFS_AUTH_TOKEN_KEY)

      @SuppressLint("ApplySharedPref")
      set(value) {
        putCredential(PREFS_AUTH_TOKEN_KEY, value)
      }

    override var user: PlexUser?
      get() {
        val userString = credentialString(PREFS_USER)
        if (userString.isEmpty()) {
          return null
        }
        return moshi.adapter<PlexUser>(PlexUser::class.java).fromJson(userString)
      }

      @SuppressLint("ApplySharedPref")
      set(value) {
        if (value == null) {
          removeCredential(PREFS_USER)
          return
        }
        val userString = moshi.adapter<PlexUser>(PlexUser::class.java).toJson(value)
        putCredential(PREFS_USER, userString)
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
        val token: String = credentialString(PREFS_SERVER_ACCESS_TOKEN)
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
          removeCredential(PREFS_SERVER_ACCESS_TOKEN)
          prefs.edit()
            .remove(PREFS_SERVER_ID_KEY)
            .remove(PREFS_SERVER_IS_OWNED)
            .remove(PREFS_SERVER_CONNECTIONS_KEY)
            // The legacy keys go too, or a later read would resurrect the old flagless shape.
            .remove(PREFS_LOCAL_SERVER_CONNECTIONS_KEY)
            .remove(PREFS_REMOTE_SERVER_CONNECTIONS_KEY)
            .remove(PREFS_SERVER_NAME_KEY).commit()
          return
        }
        // The token goes first, deliberately. This was one atomic `commit()` before the files
        // were split, and it cannot be atomic across two — so the ordering decides which orphan
        // an interrupted write leaves behind. A token with no server is invisible: the getter's
        // `name.isEmpty()` guard reads it as "no server chosen" and the next login overwrites it.
        // A server with no token looks identical to the user but has *lost a working credential*
        // to get there, so the reverse order is strictly worse.
        putCredential(PREFS_SERVER_ACCESS_TOKEN, value.accessToken)
        prefs.edit()
          .putString(PREFS_SERVER_NAME_KEY, value.name)
          .putString(PREFS_SERVER_ID_KEY, value.serverId)
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

    /**
     * Reads a credential, preferring the auth file and falling back to the settings file.
     *
     * The fallback is what makes a downgrade-then-upgrade survivable, and what lets the migration
     * below be interrupted safely: until it completes, the value is only in the settings file.
     */
    private fun credentialString(key: String): String {
      authPrefs.getString(key, null)?.let { return it }
      return prefs.getString(key, "") ?: ""
    }

    /**
     * Writes a credential to the auth file, and clears any stale copy from the settings file.
     *
     * Clearing matters: without it a value written before the migration would linger, and a later
     * read that fell back — after, say, the auth file being cleared by a restore — would resurrect
     * a token the user had signed out of.
     */
    @SuppressLint("ApplySharedPref")
    private fun putCredential(
      key: String,
      value: String,
    ) {
      authPrefs.edit().putString(key, value).commit()
      if (prefs.contains(key)) {
        prefs.edit().remove(key).commit()
      }
    }

    /** Removes a credential from both files, so a fallback read cannot resurrect it. */
    @SuppressLint("ApplySharedPref")
    private fun removeCredential(key: String) {
      authPrefs.edit().remove(key).commit()
      if (prefs.contains(key)) {
        prefs.edit().remove(key).commit()
      }
    }

    /**
     * Moves any credentials still in the settings file into the auth file, once.
     *
     * Ordered so that **no credential is ever absent from both files**:
     *
     * 1. the marker short-circuits a completed migration, so this is idempotent;
     * 2. the values and the marker are written to the auth file in **one** `commit()`, so the
     *    marker cannot land without the data it describes;
     * 3. only then are the originals removed from the settings file.
     *
     * A crash before (2) leaves everything in the settings file and the migration simply runs
     * again. A crash between (2) and (3) leaves the values in *both*, and reads prefer the auth
     * file, so the duplicate is invisible and is cleaned up on the next write. Either way the
     * user stays signed in — which is the whole point, since an empty token reads as a signed-out
     * account.
     */
    @SuppressLint("ApplySharedPref")
    private fun migrateCredentialsToAuthPrefs() {
      if (authPrefs.getBoolean(PREFS_AUTH_MIGRATED, false)) {
        return
      }
      val toMove = CREDENTIAL_KEYS.filter { prefs.contains(it) }
      val editor = authPrefs.edit().putBoolean(PREFS_AUTH_MIGRATED, true)
      toMove.forEach { key ->
        // Every credential is stored as a String; a non-String here would be corruption, and
        // letting it throw is better than silently dropping a token.
        editor.putString(key, prefs.getString(key, "") ?: "")
      }
      editor.commit()

      if (toMove.isNotEmpty()) {
        val settingsEditor = prefs.edit()
        toMove.forEach { settingsEditor.remove(it) }
        settingsEditor.commit()
        Timber.i("Moved ${toMove.size} credential(s) into the separate auth prefs file")
      }
    }

    /**
     * Deletes the premium keys orphaned by cu-60, once.
     *
     * Separate from the credential migration despite running beside it: this one removes data
     * outright, and conflating "moved the tokens" with "deleted the premium keys" in a single
     * marker would mean a future change to either re-runs the other.
     */
    @SuppressLint("ApplySharedPref")
    private fun removeOrphanedPremiumKeys() {
      if (authPrefs.getBoolean(PREFS_PREMIUM_KEYS_REMOVED, false)) {
        return
      }
      val present = ORPHANED_PREMIUM_KEYS.filter { prefs.contains(it) }
      if (present.isNotEmpty()) {
        val editor = prefs.edit()
        present.forEach { editor.remove(it) }
        editor.commit()
        Timber.i("Removed ${present.size} orphaned premium key(s) left by cu-60")
      }
      authPrefs.edit().putBoolean(PREFS_PREMIUM_KEYS_REMOVED, true).commit()
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
