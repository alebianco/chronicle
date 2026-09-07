package io.github.mattpvaughn.chronicle.data.model

import androidx.room.TypeConverter

/**
 * Which backend *installation* a row belongs to — one Plex server, not one library and not
 * "Plex" as a category (decision-21).
 *
 * ## Why this is a type and not a `String`
 *
 * The same reasoning as [BookOffset]. The scoping key is compared in `planIngestion`,
 * bound into queries and used to build a download path; all three would take a bare `String`
 * happily, and a book id, a library id and a server id are all strings here. A mix-up would
 * either scope to nothing or scope to the wrong thing, and both fail *silently* by showing a
 * union — which is the exact symptom this exists to remove.
 *
 * ## Why `String` and not `Long`
 *
 * [MediaSource.id] was `Long` and every row carried the constant `0L`. A per-instance id cannot
 * be a `Long`: a Plex `clientIdentifier` is a ~40-character string, so a numeric id would have to
 * be a hash or a locally-assigned number mapped from it — a second identity to keep in sync, which
 * is the cost removed by retyping entity ids to `String`. decision-21 chose `String` for
 * that reason.
 *
 * ## The prefix
 *
 * Values are namespaced by backend (`"plex:<clientIdentifier>"`) so a future ABS or WebDAV source
 * (decision-11) cannot mint an id that collides with a Plex server's. The prefix is part of the
 * stored value, not something reconstructed on read, so nothing has to parse it back apart.
 */
@JvmInline
value class SourceId(val value: String) {
  companion object {
    /**
     * No source resolved — the truthful value before a server has been chosen.
     *
     * Deliberately **not** equal to any real id, including [forPlexServer] of an empty
     * identifier: coalescing "not chosen yet" with a real server would file pre-login rows under
     * that server's scope, where a later refresh for a *different* server would then delete them
     * as absent from its fetch (the removal rule).
     */
    val UNKNOWN = SourceId("")

    /**
     * The scope every row written before this change carries.
     *
     * Rows stored `source = 0` (`MEDIA_SOURCE_ID_PLEX`) — all 196 of them on the household
     * server. The migration maps that to this value rather than guessing a real server id,
     * because at migration time the connected server is not knowable from inside a
     * `SupportSQLiteDatabase`. It is adopted by the first refresh that knows which server it is
     * talking to; see `BookRepository.adoptLegacyRows`.
     */
    val LEGACY_PLEX = SourceId("plex:legacy")

    /** The scope for one Plex server, keyed on its `clientIdentifier`. */
    fun forPlexServer(clientIdentifier: String): SourceId {
      if (clientIdentifier.isEmpty()) return UNKNOWN
      return SourceId("plex:$clientIdentifier")
    }
  }

  val isKnown: Boolean get() = value.isNotEmpty()
}

/**
 * Stores [SourceId] as TEXT.
 *
 * Unlike [OffsetConverters], this **does** need migrations: the `source` column was `INTEGER` and
 * becomes `TEXT`, so every database carrying it gains a migration and a `RoomSchemaTest` case.
 */
class SourceIdConverters {
  @TypeConverter
  fun toSourceId(value: String): SourceId = SourceId(value)

  @TypeConverter
  fun fromSourceId(sourceId: SourceId): String = sourceId.value
}
