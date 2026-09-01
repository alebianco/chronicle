package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Room type converter that persists every chapter list.
 *
 * It is a hand-rolled serializer joining fields with `©` and records with `®` — the upstream
 * comment calls it "a little yikes but funny" — and it was at 1.6% instruction coverage while
 * being the only path chapter data takes to disk. A chapter list is not re-derivable without a
 * network round trip, so a corrupting bug here loses data locally.
 *
 * A title containing one of those delimiters used to make the decoder **throw** — Room raised it
 * while reading the row, so the book crashed on open, permanently, until the row was deleted.
 * Plex titles are arbitrary server-side strings and "© 2019 Macmillan Audio" is ordinary chapter
 * metadata, so it was reachable. The delimiters are now escaped (cu-78) and these tests pin both
 * the round trip and the fail-soft decoding that replaced the throw.
 */
class ChapterListConverterTest {
  private val converter = ChapterListConverter()

  private val chapter =
    Chapter(
      title = "Chapter One",
      id = "11",
      index = 1L,
      bookStartTimeOffset = 0L,
      bookEndTimeOffset = 60_000L,
      discNumber = 1,
      downloaded = false,
      trackId = "3001",
      bookId = "1001",
    )

  @Test
  fun `a single chapter round-trips intact`() {
    val restored = converter.toChapterList(converter.toString(listOf(chapter)))

    assertEquals(listOf(chapter), restored)
  }

  @Test
  fun `multiple chapters round-trip in order`() {
    val chapters =
      listOf(
        chapter,
        chapter.copy(title = "Chapter Two", id = "12", index = 2L, bookStartTimeOffset = 60_000L),
        chapter.copy(title = "Chapter Three", id = "13", index = 3L, bookStartTimeOffset = 120_000L),
      )

    assertEquals(chapters, converter.toChapterList(converter.toString(chapters)))
  }

  @Test
  fun `an empty list round-trips to empty`() {
    assertEquals(emptyList<Chapter>(), converter.toChapterList(converter.toString(emptyList())))
  }

  @Test
  fun `an empty string decodes to empty rather than throwing`() {
    assertEquals(emptyList<Chapter>(), converter.toChapterList(""))
  }

  @Test
  fun `the downloaded flag survives`() {
    val downloaded = chapter.copy(downloaded = true)

    assertEquals(true, converter.toChapterList(converter.toString(listOf(downloaded)))[0].downloaded)
  }

  @Test
  fun `large offsets survive as Long`() {
    // A 30-hour book in millis exceeds Int.MAX_VALUE; a narrowing bug would wrap.
    val long = chapter.copy(bookStartTimeOffset = 108_000_000L, bookEndTimeOffset = 109_000_000L)

    val restored = converter.toChapterList(converter.toString(listOf(long)))

    assertEquals(108_000_000L, restored[0].bookStartTimeOffset)
    assertEquals(109_000_000L, restored[0].bookEndTimeOffset)
  }

  /**
   * The case that used to crash a book on open: the record separator inside a title split one
   * chapter into two and `split[1].toLong()` threw IndexOutOfBounds.
   */
  @Test
  fun `a title containing the record separator round-trips`() {
    val hostile = chapter.copy(title = "Part 1 ${'\u00AE'} Part 2")

    assertEquals(listOf(hostile), converter.toChapterList(converter.toString(listOf(hostile))))
  }

  /** "© 2019 Macmillan Audio" is ordinary chapter metadata, and used to throw. */
  @Test
  fun `a title containing the field separator round-trips`() {
    val hostile = chapter.copy(title = "${'\u00A9'} 2019 Macmillan Audio")

    assertEquals(listOf(hostile), converter.toChapterList(converter.toString(listOf(hostile))))
  }

  @Test
  fun `a title containing the escape character round-trips`() {
    // The escape must itself be escaped, or unescaping would be ambiguous.
    val hostile = chapter.copy(title = "Escape ${'\u241B'}R here")

    assertEquals(listOf(hostile), converter.toChapterList(converter.toString(listOf(hostile))))
  }

  @Test
  fun `both delimiters in one title round-trip`() {
    val hostile = chapter.copy(title = "A ${'\u00A9'} B ${'\u00AE'} C")

    assertEquals(listOf(hostile), converter.toChapterList(converter.toString(listOf(hostile))))
  }

  /**
   * Fail-soft: a malformed record must not propagate out of Room. Throwing from a type converter
   * is what made one bad chapter render a whole book unopenable.
   */
  @Test
  fun `a malformed record is skipped rather than throwing`() {
    val good = converter.toString(listOf(chapter))
    val corrupted = "not-a-chapter${'\u00AE'}$good"

    val restored = converter.toChapterList(corrupted)

    assertEquals("the salvageable chapter should survive", listOf(chapter), restored)
  }

  /**
   * Reading a row written by an older schema. The decoder defaults the trailing fields, so a
   * five-field record must still decode rather than crash — this is what makes the
   * `split.size >= n` guards load-bearing.
   */
  @Test
  fun `a legacy record without the trailing fields still decodes`() {
    val legacy = "Old Chapter©11©1©0©60000"

    val restored = converter.toChapterList(legacy)

    assertEquals(1, restored.size)
    assertEquals("Old Chapter", restored[0].title)
    assertEquals(1, restored[0].discNumber)
    assertEquals(false, restored[0].downloaded)
    assertTrue("trackId should fall back, not throw", restored[0].trackId.isNotEmpty())
  }
}
