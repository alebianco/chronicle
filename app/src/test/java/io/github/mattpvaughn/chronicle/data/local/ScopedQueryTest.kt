package io.github.mattpvaughn.chronicle.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every DAO query that returns rows without naming a single row must be scoped by source
 * (decision-21).
 *
 * **Why a source-level gate rather than a behavioural test.** `SourceIsolationTest` proves the
 * queries that exist today are scoped. It cannot prove anything about the *next* one: a new
 * unscoped list query would fail no test, because the symptom is a union that only appears with
 * two servers configured, and nothing else here has two. The task file names this as the risk —
 * "a filter on every read, which is a broad diff and easy to miss one of. A missed filter fails
 * *silently*". So the rule is enforced where it can be, at the source.
 *
 * **The rule.** A query is exempt when it names a row (`WHERE id = :`), is confined to one book
 * (`parentKey = :`, `bookId = :`), or is a whole-table write whose whole point is to ignore scope
 * (`DELETE FROM x` with no WHERE — the library-switch clear). Everything else selecting from a
 * scoped table must carry `source = :source`.
 *
 * Chapters and bookmarks are absent by design: every one of their queries is keyed by `bookId`, so
 * their scope is their book's. Adding an unkeyed read there is what would make this fire.
 */
class ScopedQueryTest {
  private val daoFiles =
    File("src/main/java/io/github/mattpvaughn/chronicle/data/local")
      .walkTopDown()
      .filter { it.extension == "kt" }
      .toList()

  @Test
  fun `every unkeyed read of a scoped table filters by source`() {
    val offenders =
      daoFiles.flatMap { file ->
        QUERY_PATTERN.findAll(file.readText()).mapNotNull { match ->
          val query = queryTextOf(match)
          if (query.contains("source = :source") || isExempt(query) || !readsScopedTable(query)) {
            null
          } else {
            "${file.name}: $query"
          }
        }
      }.sorted()

    assertEquals(
      "a read that returns rows without naming one must filter by source, or two servers merge " +
        "into one list — the exact symptom source scoping removes. Scope it with `source = :source` and " +
        "pass the repository's currentSourceId, or if it genuinely is a per-row or per-book " +
        "query, say so with `id = :` / `parentKey = :` so this guard can see that.",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: a wrong path, or a pattern that matches nothing, would prove nothing. */
  @Test
  fun `the scan finds the queries it is meant to police`() {
    assertTrue("expected DAO sources to resolve", daoFiles.any { it.name == "BookDatabase.kt" })

    val allQueries = daoFiles.flatMap { QUERY_PATTERN.findAll(it.readText()).map { m -> queryTextOf(m) } }
    assertTrue("expected to find DAO queries at all, found ${allQueries.size}", allQueries.size > 40)

    val scoped = allQueries.count { it.contains("source = :source") }
    assertTrue("expected the scan to see scoped queries, found $scoped", scoped >= 20)
  }

  /** Joins a wrapped string literal back into one query and normalises whitespace. */
  private fun queryTextOf(match: MatchResult): String {
    val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
    return raw
      .replace(Regex(""""\s*\+\s*""""), "")
      .replace("\"", "")
      .replace(Regex("""\s+"""), " ")
      .trim()
  }

  private fun isExempt(query: String): Boolean = EXEMPT_PATTERNS.any { it.containsMatchIn(query) }

  private fun readsScopedTable(query: String): Boolean =
    SCOPED_TABLES.any { table ->
      Regex("""\bFROM\s+`?$table`?\b""", RegexOption.IGNORE_CASE).containsMatchIn(query)
    }

  private companion object {
    /**
     * The `@Query("...")` string. Group 1 is a triple-quoted body, group 2 a plain one; a plain
     * string may still be split across lines by ktlint's wrapping, which is why the second
     * alternative allows a `" +` continuation.
     */
    val QUERY_PATTERN =
      Regex("""@Query\(\s*"{3}([\s\S]*?)"{3}|@Query\(\s*((?:"[^"]*"\s*\+?\s*)+)""")

    /** Tables carrying a `source` column. Chapters and bookmarks are keyed by their book. */
    val SCOPED_TABLES = listOf("Audiobook", "MediaItemTrack", "Collection")

    val EXEMPT_PATTERNS =
      listOf(
        // Names a single row, or every row of one book — the book carries the scope.
        Regex("""\b(id|bookId|trackId|collectionId|parentKey|parentId)\s*=\s*:"""),
        Regex("""\bid\s+IN\s*\(:"""),
        // The same thing with the operands the other way round — SQL allows it and one query
        // here is written that way, so matching only `id = :` would call a per-row read unscoped.
        Regex("""\bWHERE\s+:\w+\s*=\s*(id|bookId|trackId|parentKey|parentId)\b""", RegexOption.IGNORE_CASE),
        // Whole-table writes whose purpose is to ignore scope: the library-switch clear and the
        // uncache-everything sweep. Scoping these would leave another source's rows behind.
        Regex("""^\s*DELETE\s+FROM\s+\S+\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*UPDATE\s+\S+\s+SET\s+(isCached|cached)\s*=\s*:\w+\s*$""", RegexOption.IGNORE_CASE),
        // The chapter backfill deliberately counts the whole table: it repairs rows written
        // before any of this existed, which by definition carry no resolved scope.
        Regex("""chapters\s+IS\s+NOT\s+NULL""", RegexOption.IGNORE_CASE),
      )
  }
}
