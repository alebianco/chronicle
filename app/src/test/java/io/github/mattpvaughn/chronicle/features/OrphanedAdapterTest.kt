package io.github.mattpvaughn.chronicle.features

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A RecyclerView adapter that is created but never given data.
 *
 * This is the shape of **seven** bugs found in one evening on the owner's device (cu-73). cu-58
 * converted six screens off DataBinding by hand-translating 106 `@{…}` expressions into observers,
 * and seven were missed. Every miss is silent: the view is created, laid out and shown, and simply
 * never told anything, so the screen renders empty and nothing fails.
 *
 * What was lost, and what it looked like:
 *
 * | screen | binding | symptom |
 * |---|---|---|
 * | choose-user | `app:users` | login could not proceed, list always empty |
 * | home | `searchBookList` | search returned nothing |
 * | home | `android:visibility` | results list stayed GONE even once fed |
 * | currently-playing | `chapterList` | chapter list empty during playback |
 * | book-details | `chapterList` | chapter list empty |
 * | library | `checked` + `onClick` | "hide played" switch inert |
 *
 * A unit test cannot render a Fragment, so this reads the *source* instead — the same approach as
 * [io.github.mattpvaughn.chronicle.data.sources.plex.TokenLoggingTest], which catches token leaks
 * the same way. It is a lint rule expressed as a test: crude, but it fails on precisely the mistake
 * that shipped seven times.
 *
 * It deliberately does not try to prove the data is *correct* — only that something feeds it.
 */
class OrphanedAdapterTest {
  @Test
  fun `every fragment that sets an adapter also submits data to one`() {
    val offenders =
      File(MAIN_SOURCE_ROOT)
        .walkTopDown()
        .filter { it.extension == "kt" && it.name.endsWith("Fragment.kt") }
        .filter { file ->
          val source = file.readText()
          val setsAdapter = ADAPTER_ASSIGNMENT.containsMatchIn(source)
          // `submitList` is the ListAdapter default; `submitChapters` is ChapterListAdapter's own
          // entry point, which routes through an overridden submitList to insert section headers.
          val submitsData = SUBMIT_CALL.containsMatchIn(source)
          setsAdapter && !submitsData
        }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals(
      "these fragments create an adapter and never give it data — the cu-58 regression shape",
      emptyList<String>(),
      offenders,
    )
  }

  /** Guards the guard: if the scan matches nothing, it proves nothing. */
  @Test
  fun `the scan actually inspects fragments`() {
    val scanned =
      File(MAIN_SOURCE_ROOT)
        .walkTopDown()
        .count { it.extension == "kt" && it.name.endsWith("Fragment.kt") }

    assert(scanned >= 8) { "expected to scan the app's fragments, saw $scanned" }
  }

  private companion object {
    const val MAIN_SOURCE_ROOT = "src/main/java"

    /** `binding.foo.adapter = …`, on one line or wrapped onto the next. */
    val ADAPTER_ASSIGNMENT = Regex("""\.adapter\s*=""")

    val SUBMIT_CALL = Regex("""\.submit(List|Chapters)\s*\(""")
  }
}
