package io.github.mattpvaughn.chronicle.features.settings.licenses

import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The licences screen's three states.
 *
 * A **fake** source rather than a mock: the ViewModel calls it and its answer *is* the state
 * machine's input, so a relaxed mock returning null would silently exercise only the failure path
 * while every assertion about it passed.
 */
class LicensesViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  /**
   * `StandardTestDispatcher` queues rather than runs, so the ViewModel's `init` coroutine has not
   * executed when the constructor returns — which is exactly what makes the seed observable.
   */
  @Test
  fun `starts Loading, before the source has answered`() =
    runTest {
      val viewModel = LicensesViewModel { catalogOf("a:a") }

      assertEquals(LicensesUiState.Loading, viewModel.uiState.value)
    }

  @Test
  fun `becomes Loaded with the catalogue the source returned`() =
    runTest {
      val viewModel = LicensesViewModel { catalogOf("io.ktor:ktor-core", "androidx.room:room-runtime") }
      advanceUntilIdle()

      val state = viewModel.uiState.value
      assertTrue("expected Loaded but was $state", state is LicensesUiState.Loaded)
      assertEquals(2, (state as LicensesUiState.Loaded).catalog.total)
    }

  /**
   * A null read is [LicensesUiState.Failed], never `Loaded(EMPTY)`.
   *
   * The distinction is the whole point of the sealed type. An empty licences page and a broken one
   * render identically to a reader, and only one of them is honest — this app always has
   * dependencies, so "loaded, and there are none" is never a true statement about it.
   */
  @Test
  fun `becomes Failed, not an empty catalogue, when the source cannot read it`() =
    runTest {
      val viewModel = LicensesViewModel { null }
      advanceUntilIdle()

      assertEquals(LicensesUiState.Failed, viewModel.uiState.value)
    }

  /**
   * A genuinely empty catalogue still reports Loaded.
   *
   * The pair with the test above: the ViewModel must branch on *whether the read succeeded*, not on
   * whether the result is empty. Branching on emptiness would pass the test above for the wrong
   * reason and mislabel a real (if impossible) empty result as a failure.
   */
  @Test
  fun `an empty but successful read is Loaded, not Failed`() =
    runTest {
      val viewModel = LicensesViewModel { LicenseCatalog.EMPTY }
      advanceUntilIdle()

      assertEquals(LicensesUiState.Loaded(LicenseCatalog.EMPTY), viewModel.uiState.value)
    }

  /**
   * The read happens once.
   *
   * There is no refresh and there cannot be a new answer without a new build, so a `WhileSubscribed`
   * flow here would re-parse a few hundred KB of JSON every time the user rotated the device past
   * the stop timeout.
   */
  @Test
  fun `reads the catalogue exactly once`() =
    runTest {
      var reads = 0
      val viewModel =
        LicensesViewModel {
          reads++
          catalogOf("a:a")
        }
      advanceUntilIdle()
      repeat(3) { viewModel.uiState.value }
      advanceUntilIdle()

      assertEquals(1, reads)
    }

  private fun catalogOf(vararg ids: String) =
    LicenseCatalog.from(
      ids.map {
        LicensedLibrary(
          uniqueId = it,
          name = it,
          version = "1.0.0",
          licenses = listOf(LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")),
        )
      },
    )
}
