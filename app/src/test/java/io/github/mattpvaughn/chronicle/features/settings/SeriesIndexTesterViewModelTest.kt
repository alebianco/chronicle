package io.github.mattpvaughn.chronicle.features.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.PatternOrder
import io.github.mattpvaughn.chronicle.data.model.SeriesIndexPattern
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.keepCollected
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The tester reports what a rule did, including when it did nothing.
 *
 * The screen exists to prevent tvnamer's #216 — a user unable to tell whether their pattern was
 * wrong or the tool was broken — so the cases that matter most here are the *negative* ones: a rule
 * that does not compile, one that matches nothing, and a user rule that a built-in pre-empted.
 */
class SeriesIndexTesterViewModelTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private fun book(
    id: String,
    titleSort: String,
  ) = Audiobook(id = id, source = TEST_SOURCE, title = "T$id", titleSort = titleSort)

  private fun TestScope.viewModel(books: List<Audiobook> = emptyList()): SeriesIndexTesterViewModel {
    val repo =
      mockk<IBookRepository>(relaxed = true) {
        coEvery { getAllBooksAsync() } returns books
      }
    // One scheduler shared with the main-dispatcher rule, so `advanceUntilIdle` drains the `io`
    // hop in `loadLibrary` as well as the main-thread work that follows it.
    val dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler)
    return SeriesIndexTesterViewModel(repo, dispatchers, testExceptionHandler()).also {
      // `winningRule` and `parsedPosition` are `stateIn(WhileSubscribed)`, so they compute only
      // while collected — `.value` reads the seed however many times the source changed. The
      // Fragment collects them; a test has to as well or it asserts against nothing. (Same need as
      // the `observeForever` this replaced, when they were cold LiveData transformations.)
      keepCollected(it.winningRule)
      keepCollected(it.parsedPosition)
    }
  }

  @After
  fun resetPatterns() {
    // The rule set is process-global, so a test that installs one would leak it into the next.
    Audiobook.resetSeriesIndexPatterns()
  }

  /**
   * More than one rule routinely succeeds, and only the first counts.
   *
   * `"Mistborn, Book 2 - …"` satisfies both `audnexus` and `seanap`. First-match-wins is the
   * disambiguation mechanism, so the screen has to name the rule that *decided* rather
   * than every rule that could have — a user reading two green rows cannot tell which applied.
   */
  @Test
  fun `the winning rule is the first that succeeded, not the only one`() =
    runTest {
      val vm = viewModel()

      vm.onTitleSortChanged("Mistborn, Book 2 - The Well of Ascension")
      // The derived flows are collected, but the collector is scheduled — without this the
      // `winningRule` read below sees the seed rather than the emission just produced.
      advanceUntilIdle()

      val succeeded = vm.attempts.value.filter { it.succeeded }
      assertTrue("expected more than one rule to succeed here", succeeded.size > 1)
      assertEquals(succeeded.first().patternName, vm.winningRule.value!!.patternName)
      assertEquals("2", vm.parsedPosition.value)
    }

  @Test
  fun `every rule that did not match says why`() =
    runTest {
      val vm = viewModel()

      vm.onTitleSortChanged("A Standalone Novel")
      advanceUntilIdle()

      val attempts = vm.attempts.value
      assertTrue("expected the built-in rules to be reported", attempts.isNotEmpty())
      assertTrue(
        "every rule must carry a reason when none of them succeeded",
        attempts.all { it.rejectedReason != null },
      )
      assertNull("nothing matched, so there is no position", vm.parsedPosition.value)
    }

  /**
   * The tvnamer #216 case, and the reason this screen exists.
   *
   * A rule that cannot compile is dropped at load with only a `Timber.w` line. Without the tester
   * the user sees it having no effect and nothing telling them why.
   */
  @Test
  fun `a rule that cannot compile is reported as such rather than silently missing`() =
    runTest {
      Audiobook.installSeriesIndexPatterns(
        listOf(SeriesIndexPattern(name = "broken", source = "Book (?<index>[0-9]+")),
      )
      val vm = viewModel()

      vm.onTitleSortChanged("Mistborn, Book 2")
      advanceUntilIdle()

      val broken = vm.attempts.value.single { it.patternName == "broken" }
      assertEquals(false, broken.matched)
      assertTrue(
        "the reason must say the expression is invalid, not merely that it did not match: " +
          "${broken.rejectedReason}",
        broken.rejectedReason!!.contains("not a valid regular expression"),
      )
    }

  @Test
  fun `a user rule is distinguishable from a built-in`() =
    runTest {
      Audiobook.installSeriesIndexPatterns(
        listOf(SeriesIndexPattern(name = "mine", source = """#(?<index>\d+)""")),
      )
      val vm = viewModel()

      vm.onTitleSortChanged("Some Series #4")
      advanceUntilIdle()

      val attempts = vm.attempts.value
      assertEquals(true, attempts.single { it.patternName == "mine" }.isUserDefined)
      assertTrue(
        "the built-ins must not be reported as the user's own",
        attempts.filter { it.patternName != "mine" }.none { it.isUserDefined },
      )
      assertEquals(1, vm.userRuleCount)
    }

  /**
   * "My rule did not match" and "a built-in ran first and won" are different problems.
   *
   * With `AFTER`, a user rule that *would* have matched never gets the chance, and a flat verdict
   * list cannot explain the outcome without the order being visible.
   */
  @Test
  fun `the effective rule order is reported`() =
    runTest {
      Audiobook.installSeriesIndexPatterns(
        listOf(SeriesIndexPattern(name = "mine", source = """#(?<index>\d+)""")),
        PatternOrder.AFTER,
      )

      assertEquals(PatternOrder.AFTER, viewModel().ruleOrder)
    }

  @Test
  fun `the summary counts the library as it parses today`() =
    runTest {
      val vm =
        viewModel(
          listOf(
            book("1", "Mistborn, Book 2 - The Well of Ascension"),
            book("2", "A Standalone Novel"),
            book("3", ""),
          ),
        )

      advanceUntilIdle()

      val summary = vm.summary.value!!
      assertEquals(3, summary.total)
      assertEquals("the book with no titleSort has nothing to parse", 2, summary.withTitleSort)
      assertEquals(1, summary.parsed)
      assertEquals(1, summary.unparsed)
    }

  /**
   * The samples are the titles a rule could plausibly fix — not every title.
   *
   * A book that already parses needs no rule, and one with no `titleSort` cannot be fixed by any
   * rule, so offering either would pad the list with entries that waste the user's attention.
   */
  @Test
  fun `samples offer only titles that currently parse to nothing`() =
    runTest {
      val vm =
        viewModel(
          listOf(
            book("1", "Mistborn, Book 2 - The Well of Ascension"),
            book("2", "A Standalone Novel"),
            book("3", ""),
          ),
        )

      advanceUntilIdle()

      assertEquals(listOf("A Standalone Novel"), vm.samples.value)
    }

  @Test
  fun `choosing a sample loads it into the input and runs the rules`() =
    runTest {
      val vm = viewModel(listOf(book("1", "A Standalone Novel")))
      advanceUntilIdle()

      vm.onSampleChosen("Mistborn, Book 2 - The Well of Ascension")
      advanceUntilIdle()

      assertEquals("Mistborn, Book 2 - The Well of Ascension", vm.titleSort.value)
      assertEquals("2", vm.parsedPosition.value)
    }

  /**
   * Two different unparseable titles both report a verdict.
   *
   * The regression this pins appeared only when the StateFlow migration happened: `winningRule` became
   * a `StateFlow`, which **conflates**, so a second title that also fails emits `null` after a
   * `null` and the collector never fires — the headline stayed hidden while the rule list beneath
   * it updated. As a `LiveData` transformation it re-emitted regardless. The screen drives that
   * headline from `attempts` rather than `winningRule` for that reason, and `titleSort` — which
   * always changes — is what ultimately drives the redraw. This pins the *reported state* after
   * each input rather than the emission mechanism, since the mechanism is the screen's business.
   */
  @Test
  fun `a second unparseable title still produces a verdict`() =
    runTest {
      val vm = viewModel()

      // A title that parses, then one that does not, then another that does not. The last step is
      // the one that used to break: `winningRule` goes null -> null, a `StateFlow` conflates that
      // to nothing, and a screen collecting it alone never learns to redraw.
      vm.onTitleSortChanged("Mistborn, Book 2")
      advanceUntilIdle()
      assertEquals("2", vm.parsedPosition.value)

      vm.onTitleSortChanged("A Standalone Novel")
      advanceUntilIdle()
      assertNull("a title with no position must report none", vm.winningRule.value)
      assertTrue("the rules are still reported", vm.attempts.value.isNotEmpty())

      vm.onTitleSortChanged("Another Standalone Novel")
      advanceUntilIdle()

      assertNull("the second unparseable title must also report none", vm.winningRule.value)
      assertTrue(
        "and it must still carry a full rule list for the screen to render",
        vm.attempts.value.isNotEmpty(),
      )
      assertEquals("Another Standalone Novel", vm.titleSort.value)
    }

  /** An empty box reports nothing rather than every rule failing against "". */
  @Test
  fun `a blank input clears the verdicts instead of reporting failures`() =
    runTest {
      val vm = viewModel()
      vm.onTitleSortChanged("Mistborn, Book 2")

      vm.onTitleSortChanged("   ")
      advanceUntilIdle()

      assertEquals(emptyList<Any>(), vm.attempts.value)
      assertNull(vm.parsedPosition.value)
    }
}
