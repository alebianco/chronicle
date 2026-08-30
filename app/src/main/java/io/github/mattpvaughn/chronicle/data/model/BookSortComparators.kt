package io.github.mattpvaughn.chronicle.data.model

/**
 * Sorting helpers for library ordering.
 *
 * Ported from the fork ecosystem's numeric-series/author-surname sorting work
 * (upstream issue #21). Kept here as pure functions so the ordering rules are
 * unit-testable without a ViewModel or a database.
 */
object BookSortComparators {
  private val digitRun = Regex("\\d+")

  /**
   * Compares titles the way a reader expects a series to be shelved: digit runs
   * compare by numeric value, so "Book 2" precedes "Book 10". A plain
   * [String.compareTo] orders them the other way, because '1' < '2' character-wise.
   *
   * Comparison is case-insensitive; ties fall back to the raw string so the
   * ordering stays total and therefore stable.
   */
  fun compareTitlesNaturally(
    left: String,
    right: String,
  ): Int {
    val l = left.trim()
    val r = right.trim()
    var i = 0
    var j = 0
    while (i < l.length && j < r.length) {
      val lc = l[i]
      val rc = r[j]
      if (lc.isDigit() && rc.isDigit()) {
        val lNum = digitRun.find(l, i)!!.value
        val rNum = digitRun.find(r, j)!!.value
        // Compare by value, not by text: "10" > "9" even though "1" < "9".
        val byValue =
          lNum.trimStart('0').padStart(1, '0').let { a ->
            rNum.trimStart('0').padStart(1, '0').let { b ->
              if (a.length != b.length) a.length - b.length else a.compareTo(b)
            }
          }
        if (byValue != 0) return byValue
        i += lNum.length
        j += rNum.length
      } else {
        val byChar = lc.lowercaseChar().compareTo(rc.lowercaseChar())
        if (byChar != 0) return byChar
        i++
        j++
      }
    }
    val byRemaining = (l.length - i) - (r.length - j)
    return if (byRemaining != 0) byRemaining else l.compareTo(r)
  }

  /**
   * Sort key placing an author under their surname: "Brandon Sanderson" sorts as
   * "sanderson brandon". Sorting the raw display name files everyone under their
   * first name, which is not how anyone looks for a book.
   *
   * A name already written "Surname, Given" is left in that order. Single-word
   * names (and blanks) are returned as-is.
   */
  fun authorSortKey(author: String): String {
    val name = author.trim()
    if (name.isEmpty()) return name
    if (name.contains(',')) return name.lowercase()

    val parts = name.split(Regex("\\s+"))
    if (parts.size < 2) return name.lowercase()

    val surname = parts.last()
    val given = parts.dropLast(1).joinToString(" ")
    return "$surname $given".lowercase()
  }
}
