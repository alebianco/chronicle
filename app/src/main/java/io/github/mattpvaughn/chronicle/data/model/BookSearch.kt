package io.github.mattpvaughn.chronicle.data.model

// Typo-tolerant, grouped search over a book list (cu-25).
//
// Pure over Audiobook, like FacetKind and friends, so the matching, the ranking and the grouping
// are all testable without a database or a screen.
//
// Why this is local and not /hubs/search. Plex's search endpoint cannot answer the queries this app
// most needs: narrator and series live in Style/Mood tags that are *detail-only* (cu-24), so the
// server has no index of them for a book this install has not synced yet, and a server-side search
// is unavailable in offline mode — which every other read path here honours. A local scan over the
// already-synced library answers all four fields, works offline, and is cheap enough to run per
// keystroke (see searchFuzzy). Seeding narrator/series for the whole library up front is cu-143.

/** Which field a query matched — both a ranking input and the grouping key. */
enum class SearchField {
  Title,
  Author,
  Narrator,
  Series,
}

/**
 * One matched book, with why it matched.
 *
 * [matchedField] is the *best* field this book matched on, so a book found by its title and also
 * by its author appears once, under the title. [score] is ordering only and carries no meaning
 * across queries.
 */
data class SearchResult(
  val book: Audiobook,
  val matchedField: SearchField,
  val matchedValue: String,
  val score: Int,
)

/**
 * Search results split by the field they matched.
 *
 * Grouped rather than flat because the four fields answer different questions: "Kramer" typed into
 * a flat list looks like three unrelated books, while under a *Narrator* heading it reads as the
 * books he reads. A group with no matches is **absent**, not empty — an empty "Series" heading
 * states that the library has no such series, which is a different and usually wrong claim.
 */
data class GroupedSearchResults(
  val groups: List<SearchGroup>,
) {
  val isEmpty: Boolean get() = groups.isEmpty()

  fun group(field: SearchField): SearchGroup? = groups.firstOrNull { it.field == field }
}

/**
 * One heading and its rows.
 *
 * [values] is the distinct matched values — the author names, not one row per book of theirs —
 * because "Sanderson" should offer *Brandon Sanderson* once rather than his fourteen books
 * flattened under a heading. [count] is the number of matching **books**, which is what the
 * heading reports ("Narrators · 3 books"), following the counted filter chips in
 * RESEARCH_FINDINGS §3.1.
 */
data class SearchGroup(
  val field: SearchField,
  val results: List<SearchResult>,
) {
  val count: Int get() = results.size

  val values: List<String> get() = results.map { it.matchedValue }.distinct()
}

/**
 * The shortest query allowed to match fuzzily.
 *
 * Below this a typo is indistinguishable from a different word: at distance 1, "du" reaches "de",
 * "do" and "dune"'s first two letters alike, so the first keystrokes would answer most of the
 * library. Short queries therefore match by prefix/substring only.
 */
private const val MIN_FUZZY_QUERY_LENGTH = 4

/** Edit distance tolerated, by query length — longer queries can afford a looser budget. */
private fun editBudgetFor(query: String): Int = if (query.length >= 8) 2 else 1

/**
 * Character counts for the prefilter, and the reason it is counts and not bigrams.
 *
 * A first cut prefiltered on shared bigrams, which silently rejected the commonest typo of all:
 * transposing two letters rewrites *every* adjacent pair, so "dnue" and "dune" share no bigram
 * and the true match was discarded before any distance was computed. Character counts are
 * unchanged by a transposition, so they survive it — while still being selective enough to be
 * worth doing (a measured 12 of 4000 fields kept for a six-letter query).
 */
private fun charCountsOf(value: String): Map<Char, Int> = value.groupingBy { it }.eachCount()

/**
 * Every book matching [query], best match first.
 *
 * Scans the four searchable fields locally. Cost is the reason this is viable per keystroke: an
 * edit distance is computed only for a field that survives a **length and character-count
 * prefilter**, which on a 1000-book library (the cu-51 target) leaves a few dozen of several
 * thousand fields — so the common keystroke costs a character tally per field, not a distance
 * matrix.
 */
fun List<Audiobook>.searchFuzzy(query: String): List<SearchResult> {
  val needle = query.trim().lowercase()
  if (needle.isEmpty()) return emptyList()

  val needleCounts = charCountsOf(needle)

  return mapNotNull { book -> book.bestMatch(needle, needleCounts) }
    // Descending score, then a stable tiebreak so the list cannot reshuffle between identical
    // queries: sortedWith is stable, but two equal scores would otherwise keep input order, which
    // for a DB-ordered list is not an order the user can learn.
    .sortedWith(
      compareByDescending<SearchResult> { it.score }
        .thenBy { it.matchedField.ordinal }
        .thenBy { it.book.title.lowercase() },
    )
}

/** [searchFuzzy], split into one group per matched field. */
fun List<Audiobook>.groupedSearch(query: String): GroupedSearchResults {
  val groups =
    searchFuzzy(query)
      .groupBy { it.matchedField }
      .entries
      // Field order, not match count: the headings must not swap places as the query grows.
      .sortedBy { it.key.ordinal }
      .map { SearchGroup(field = it.key, results = it.value.orderedFor(it.key)) }
  return GroupedSearchResults(groups)
}

/**
 * Orders one group's results the way that group is read.
 *
 * Only the **series** group differs: reading order is the whole reason to search a series name, and
 * ranking by match quality there sorts alphabetically by title — which put *The Hero of Ages*
 * (book 3) between books 1 and 2. Every other group stays ranked by how well it matched, since
 * "closest match first" is what a title or narrator query means.
 *
 * Delegates to [inSeriesOrder] rather than sorting here, so the reading order the browse screen
 * uses (cu-24, including its rule that an unnumbered extra sorts *after* the numbered books) is
 * the one the search shows. A second copy would drift.
 */
private fun List<SearchResult>.orderedFor(field: SearchField): List<SearchResult> {
  if (field != SearchField.Series) return this
  val byBookId = associateBy { it.book.id }
  return map { it.book }.inSeriesOrder().mapNotNull { byBookId[it.id] }
}

/**
 * This book's best match for [needle], or null if no field matches.
 *
 * Fields are scored and the highest wins, so a book appears once under its strongest reason.
 */
private fun Audiobook.bestMatch(
  needle: String,
  needleCounts: Map<Char, Int>,
): SearchResult? =
  searchableFields()
    .mapNotNull { (field, value) ->
      scoreField(needle, needleCounts, value)?.let { quality ->
        SearchResult(
          book = this,
          matchedField = field,
          matchedValue = value,
          // The field's rank dominates the match quality, so a *fuzzy* title match still outranks
          // an *exact* match on the series name. Without this the two tiers interleave and a query
          // for a title can answer with a series first.
          score = quality + fieldBonusFor(field),
        )
      }
    }
    .maxByOrNull { it.score }

/**
 * How much a match is worth for being on [field] rather than a later one.
 *
 * Wider than the whole within-field range (exact 1000 down to a two-edit fuzzy 380), so field
 * order is never overturned by match quality. Title first because it is what a user types most.
 */
private fun fieldBonusFor(field: SearchField): Int = FIELD_BONUS_STEP * (SearchField.entries.size - field.ordinal)

/** One step of field preference. Must exceed the spread of the score tiers below. */
private const val FIELD_BONUS_STEP = 1000

/**
 * The fields a query is matched against, strongest first.
 *
 * Narrator is split because a full-cast recording stores several comma-separated (cu-24), and a
 * query for one narrator must not have to match the whole joined string.
 */
private fun Audiobook.searchableFields(): List<Pair<SearchField, String>> =
  buildList {
    if (title.isNotEmpty()) add(SearchField.Title to title)
    if (author.isNotEmpty()) add(SearchField.Author to author)
    narrator.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      .forEach { add(SearchField.Narrator to it) }
    if (series.isNotEmpty()) add(SearchField.Series to series)
  }

/** Match-quality tiers, within one field. Field order is applied separately, by [fieldBonusFor]. */
private const val SCORE_EXACT = 1000
private const val SCORE_PREFIX = 800
private const val SCORE_WORD_PREFIX = 700
private const val SCORE_SUBSTRING = 600
private const val SCORE_FUZZY = 400

/**
 * How well [value] matches [needle], or null for no match.
 *
 * Ordered exact → prefix → word prefix → substring → fuzzy, because those are meaningfully
 * different qualities of match and a user typing a full title expects it first. Only the last
 * tier pays for an edit distance.
 */
private fun scoreField(
  needle: String,
  needleCounts: Map<Char, Int>,
  value: String,
): Int? {
  val hay = value.lowercase()

  when {
    hay == needle -> return SCORE_EXACT
    hay.startsWith(needle) -> return SCORE_PREFIX
    hay.split(' ', '-', ':', '\'').any { it.startsWith(needle) } -> return SCORE_WORD_PREFIX
    hay.contains(needle) -> return SCORE_SUBSTRING
  }

  // Below the fuzzy floor the tiers above are the whole contract: a two-letter query that is not
  // a prefix or substring of anything is a miss, not a near-miss.
  if (needle.length < MIN_FUZZY_QUERY_LENGTH) return null

  val budget = editBudgetFor(needle)

  // Whole-value distance first, then per word: "Sandersen" should reach the *word* "Sanderson"
  // inside "Brandon Sanderson", whose whole-string distance is far past any sane budget.
  val candidates = listOf(hay) + hay.split(' ', '-', ':', '\'')
  val best =
    candidates
      .filter { it.isNotEmpty() && withinPrefilter(needle, needleCounts, it, budget) }
      .mapNotNull { editDistanceAtMost(needle, it, budget) }
      .minOrNull()
      ?: return null

  // A closer match scores higher, and every fuzzy match stays below every exact-tier one.
  return SCORE_FUZZY - best * 10
}

/**
 * Whether [candidate] is close enough to be worth an edit distance.
 *
 * Two cheap *necessary* conditions, so this discards only true misses: a length gap wider than the
 * budget cannot be closed by that many edits, and each edit can change the character multiset by
 * at most two (one character out, one in) — so a symmetric difference above `2 * budget` puts the
 * candidate out of reach. This is what makes the scan affordable per keystroke.
 */
private fun withinPrefilter(
  needle: String,
  needleCounts: Map<Char, Int>,
  candidate: String,
  budget: Int,
): Boolean {
  if (kotlin.math.abs(candidate.length - needle.length) > budget) return false

  val candidateCounts = charCountsOf(candidate)
  var difference = 0
  for ((char, count) in needleCounts) {
    val other = candidateCounts[char] ?: 0
    if (count > other) difference += count - other
  }
  for ((char, count) in candidateCounts) {
    val other = needleCounts[char] ?: 0
    if (count > other) difference += count - other
  }
  return difference <= 2 * budget
}

/**
 * Damerau-Levenshtein distance between [left] and [right], or null if it exceeds [budget].
 *
 * **Damerau**, not plain Levenshtein, because a transposition is the commonest typo of all and
 * plain Levenshtein charges 2 for it — so "dnue" for "dune" fell outside the budget a real typo
 * has to fit inside, while a budget widened to 2 to admit it would also start matching genuinely
 * different words. Counting a swap as one edit is the fix that distinguishes those two cases.
 *
 * Three rows rather than a full matrix (a transposition needs the row before last), and it abandons
 * a row whose every entry already exceeds the budget, so a miss costs a fraction of the full
 * computation. Hand-rolled deliberately: this is the "trivial utility where a dependency is pure
 * weight" exception to preferring a library (CLAUDE.md principle 3) — a string-similarity
 * dependency would also add a ProGuard surface for fifteen lines of arithmetic.
 */
internal fun editDistanceAtMost(
  left: String,
  right: String,
  budget: Int,
): Int? {
  if (kotlin.math.abs(left.length - right.length) > budget) return null
  if (left.isEmpty()) return right.length.takeIf { it <= budget }
  if (right.isEmpty()) return left.length.takeIf { it <= budget }

  // beforePrevious is row j-2, needed only for the transposition term.
  var beforePrevious = IntArray(left.length + 1)
  var previous = IntArray(left.length + 1) { it }
  var current = IntArray(left.length + 1)

  for (j in 1..right.length) {
    current[0] = j
    var rowBest = current[0]
    for (i in 1..left.length) {
      val substitution = previous[i - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
      var best = minOf(previous[i] + 1, current[i - 1] + 1, substitution)
      if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
        best = minOf(best, beforePrevious[i - 2] + 1)
      }
      current[i] = best
      rowBest = minOf(rowBest, best)
    }
    // Every alignment through this row already costs more than the budget allows.
    if (rowBest > budget) return null
    val recycled = beforePrevious
    beforePrevious = previous
    previous = current
    current = recycled
  }

  return previous[left.length].takeIf { it <= budget }
}
