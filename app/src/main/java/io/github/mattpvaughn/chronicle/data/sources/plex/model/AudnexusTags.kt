package io.github.mattpvaughn.chronicle.data.sources.plex.model

//
// Narrator and series, read out of Plex's `Style` and `Mood` tags (cu-24).
//
// Plex's music schema carries no narrator or series field. The Audnexus/seanap tagging convention
// borrows two music fields for them, so **these are not music semantics** — a "style" here is a
// person and a "mood" is a book series. Kept in one file so the convention is written down once
// and every reader goes through it.
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
 * The **first** usable tag wins. Plex allows several moods and a book belongs to one series in this
 * convention, so taking the first is the honest reading of an ambiguous library; picking arbitrarily
 * from a set would make the facet list unstable between syncs.
 */
fun PlexDirectory.seriesName(): String =
  plexMoods.asSequence()
    .map { stripSeriesPrefix(it.tag) }
    .firstOrNull { it.isNotEmpty() }
    .orEmpty()

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
