package io.github.mattpvaughn.chronicle.data.sources.plex.model

//
// Narrator and series, read out of Plex's `Style` and `Mood` tags (cu-24).
//
// Plex's music schema carries no narrator or series field. The Audnexus tagging convention borrows
// two music fields for them, so **these are not music semantics** — a "style" here is a person, and
// a "mood" is a book series *or an author*. Kept in one file so the convention is written down once
// and every reader goes through it.
//
// Note it is Audnexus, not seanap, that produces these: the seanap guide is a file/ID3 convention
// (narrator in `TCOM`/`TPE1`) and never touches Plex's Style/Mood fields.
//
// Both tags are only present on the per-book **detail** response (`/library/metadata/{id}`), not
// on the library listing — verified against fixtures captured from a real Plex 1.43.3 server. A
// facet index therefore fills in as books are synced, and the UI has to say so rather than
// presenting a partial index as complete.

/**
 * Prefixes a tagger may put on a series `Mood` tag.
 *
 * The seanap guide writes `Series: <name>`; some taggers omit it. Matched case-insensitively and
 * with optional whitespace, because a hand-tagged library is not consistent — and the prefix must
 * be stripped or "Series: Mistborn" and "Mistborn" become two different series in the facet list.
 */
private val SERIES_PREFIXES = listOf("series:", "series -", "series")

/**
 * The narrators named in this book's `Style` tags, in the order the server gave them.
 *
 * A list, not a string: a book can legitimately have several narrators (a full-cast recording),
 * and joining them here would make the facet list contain "A, B" as if it were one person.
 */
fun PlexDirectory.narrators(): List<String> = plexStyles.map { it.tag.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * The series this book belongs to, or an empty string when no `Mood` tag names one.
 *
 * **A prefixed tag always beats an unprefixed one.** `Mood` is not a series-only field: Audnexus
 * writes series as `"Series: <name>"` (`add_series_to_moods`, unconditional) but *also* writes bare
 * **author** names into the same field (`add_authors_to_moods`, gated on its `store_author_as_mood`
 * preference — verified in `Contents/Code/update_tools.py`). Plex returns moods alphabetically, so
 * simply taking the first non-empty tag filed any book whose author sorts before its series under a
 * series named after the author — silently, and only on servers with that preference enabled, which
 * is why fixtures written to match this code never showed it (the cu-24 trap).
 *
 * Among equals the **first** still wins: a book belongs to one series in this convention, and
 * picking arbitrarily from a set would make the facet list unstable between syncs. Audnexus can emit
 * both `seriesPrimary` and `seriesSecondary` as prefixed tags, so that tie is real and order is the
 * only signal available.
 */
fun PlexDirectory.seriesName(): String {
  val candidates = plexMoods.map { it.tag.trim() }.filter { it.isNotEmpty() }

  // Only a prefixed tag is *known* to be a series; an unprefixed one may be an author. Fall back to
  // it regardless, since taggers that omit the prefix are the reason stripSeriesPrefix is lenient.
  val prefixed = candidates.firstOrNull { hasSeriesPrefix(it) }?.let { stripSeriesPrefix(it) }

  return prefixed?.takeIf { it.isNotEmpty() }
    ?: candidates.firstNotNullOfOrNull { stripSeriesPrefix(it).takeIf(String::isNotEmpty) }
      .orEmpty()
}

/** Whether [raw] carries one of the [SERIES_PREFIXES], i.e. is explicitly labelled a series. */
internal fun hasSeriesPrefix(raw: String): Boolean {
  val lower = raw.trim().lowercase()
  return SERIES_PREFIXES.any { lower.startsWith(it) }
}

/**
 * Removes a `Series:`-style prefix and surrounding whitespace.
 *
 * Returns an empty string for a tag that is *only* the prefix, so a stray `Series:` with no name
 * does not become a series called "".
 */
internal fun stripSeriesPrefix(raw: String): String {
  val trimmed = raw.trim()
  val lower = trimmed.lowercase()
  val prefix =
    SERIES_PREFIXES.firstOrNull { lower.startsWith(it) }
      ?: return trimmed
  return trimmed.substring(prefix.length).trimStart(' ', ':', '-').trim()
}
