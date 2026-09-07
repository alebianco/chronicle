package io.github.mattpvaughn.chronicle.data

import kotlinx.serialization.json.Json

/**
 * The one `Json` this app parses and writes with.
 *
 * A single configured instance rather than one per call site, because **kotlinx-serialization's
 * defaults are stricter than Moshi's were** and the difference is silent in the direction that
 * hurts. Moshi dropped an unknown key; kotlinx throws on one unless told otherwise. Every format
 * this app reads — the Plex API, the settings export (D8), the user's series-index rules
 * (decision-18) — depends on unknown keys being *ignored*, so that setting is made once here where
 * it can be read, tested and not forgotten.
 *
 * Each setting and why it is not the default:
 *
 * - **[JsonBuilder.ignoreUnknownKeys]** — the load-bearing one. Plex adds response fields between
 *   server versions and this app names only the ones it uses; a settings file written by a *newer*
 *   build must still restore on an *older* one, which is the direction `SettingsBackup`'s KDoc
 *   calls out because it is the direction nobody tests by hand. Without this, every one of those
 *   is a thrown exception instead of a degraded read.
 * - **`coerceInputValues` is left off, deliberately.** It would make an explicit `null` on a
 *   non-nullable property take the property's default instead of throwing. Moshi's generated
 *   adapters *threw*, and `PlexFixtureContractTest` pins that: "a null on a non-null field must
 *   fail loudly, not parse as empty". A server that starts sending nulls should produce a known
 *   failure rather than a library of books silently titled "". Absent keys already fall back to
 *   their defaults — that is a different case, and the one Plex actually exercises.
 * - **[JsonBuilder.encodeDefaults] = true** — the one that bit. kotlinx omits a property whose
 *   value equals its default, so `SettingsBackup(version = 2)` wrote a file with **no `version`
 *   field at all** — the format's own self-description, silently gone, caught by
 *   `SettingsBackupRepoTest`'s "should declare its schema". Moshi wrote defaults. A hand-editable
 *   file that omits the fields currently at their defaults is also a worse file to hand-edit,
 *   which is the D12 rule 7 argument.
 * - **[JsonBuilder.explicitNulls] = false** — a null property is omitted when writing rather than
 *   written as `"key": null`. This keeps the settings export and the rules file readable by hand,
 *   which is the whole point of the format (D12 rule 7). Note it does not fight `encodeDefaults`:
 *   that writes a default *value*, this suppresses an actual `null`.
 * - **[JsonBuilder.isLenient] is left off deliberately.** Accepting unquoted keys and single quotes
 *   would hide a genuinely malformed file rather than reporting it, and both formats already
 *   degrade gracefully on a parse failure — they do not need the parser to guess as well.
 */
val ChronicleJson: Json =
  Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

/**
 * The same configuration, indented, for the files a user is meant to open in an editor.
 *
 * Split from [ChronicleJson] rather than made a parameter: pretty-printing is a *writing* choice
 * for two specific on-disk formats, and the wire format should never pay for it.
 */
val ChronicleJsonPretty: Json =
  Json(ChronicleJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
  }
