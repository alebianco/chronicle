package io.github.mattpvaughn.chronicle.data.model

import timber.log.Timber

/**
 * Validation for ids that arrive from a media server (cu-111).
 *
 * An id is server-controlled data that becomes a **filename**: a downloaded track is written to
 * `File(cachedMediaDir, "$id.$extension")`, and `File(parent, child)` does not normalize. So an id
 * of `../../../../databases/BookDatabase` writes attacker-controlled bytes outside the cache
 * directory, next to the Room databases and `ChronicleAuth.xml`.
 *
 * The threat model is narrow and worth stating: the attacker has to *be* the media server, since
 * the app refuses cleartext app-wide (cu-42) and so a network attacker cannot inject a response.
 * This is therefore server-compromise escalation rather than a remote primitive — which is why the
 * response is to reject the item and carry on, not to fail the whole sync.
 *
 * Ids are `String` since cu-71 precisely so a non-numeric backend can be represented
 * (decision-11), so this cannot be "must be digits". It is a deny-list of the characters that give
 * a filename meaning it should not have.
 */
object MediaId {
  /** Path separators on this platform and inside any archive format. */
  private const val UNIX_SEPARATOR = '/'
  private const val WINDOWS_SEPARATOR = '\\'

  /**
   * NUL terminates a string in native code, so a validated prefix can resolve somewhere else
   * entirely once the path reaches a native `open()`.
   */
  private const val NUL = '\u0000'

  private val FORBIDDEN_CHARS = charArrayOf(UNIX_SEPARATOR, WINDOWS_SEPARATOR, NUL)

  /**
   * Whether [id] is safe to use as an id and as a filename component.
   *
   * Rejects: anything empty or blank, `.` and `..` entire (the traversal primitives), any id
   * *containing* `..` (which covers `a/../b` even if a separator were ever permitted), and
   * anything holding a forbidden character.
   *
   * Deliberately permits a single `.` inside an id (e.g. `1001.2`), because ids are `String` for
   * a reason and a dot is not dangerous on its own — `getCachedFileName` appends its own
   * extension, and `getTrackIdFromFileName` reads up to the *first* dot.
   */
  fun isValid(id: String): Boolean {
    if (id.isBlank()) return false
    if (id == "." || id == "..") return false
    if (id.contains("..")) return false
    return FORBIDDEN_CHARS.none { it in id }
  }

  /**
   * [isValid], but logs what was rejected.
   *
   * Logs the id itself: it is server metadata, never a credential, and a rejection that does not
   * say *which* id it dropped is undiagnosable — the user's symptom would be "one book is missing"
   * with nothing in the log to explain it.
   */
  fun isValidOrLog(
    id: String,
    kind: String,
  ): Boolean {
    val valid = isValid(id)
    if (!valid) {
      Timber.e("Rejecting $kind with an unsafe id: '$id' - it would escape the cache directory")
    }
    return valid
  }
}
