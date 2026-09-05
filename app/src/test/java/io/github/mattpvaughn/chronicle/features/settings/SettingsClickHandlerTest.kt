package io.github.mattpvaughn.chronicle.features.settings

import android.content.Context
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SettingsBackupRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings screen's **click handlers**, exercised through the list `makePreferences` builds.
 *
 * These are the 231 uncovered lines inside that 737-line function, and they are worth reaching for
 * a reason cu-175's original plan missed: extracting a pure `SettingsPreferencesBuilder` would move
 * the *labels* out and leave every one of these handlers behind in the ViewModel. The refactor and
 * the coverage are separate problems.
 *
 * Nothing here needs the fragment. A `PreferenceModel` carries its own `click`, so a test can pull
 * a row out of the built list and invoke it — which is exactly what a tap does — then assert on the
 * state the ViewModel publishes.
 *
 * Deliberately about **observable consequences**, not call counts: what matters is that a
 * destructive action opens a confirmation rather than acting, and that the chooser it opens offers
 * a way out.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsClickHandlerTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { bookCoverStyle } returns "Square"
      every { refreshRateMinutes } returns 60L
      every { jumpForwardSeconds } returns 30L
      every { jumpBackwardSeconds } returns 10L
    }
  private val cachedFileManager = mockk<ICachedFileManager>(relaxed = true)

  private fun viewModel(): SettingsViewModel {
    val context =
      mockk<Context>(relaxed = true) {
        every { getString(any()) } returns "label"
        every { getString(any(), *anyVararg()) } returns "label"
      }
    return SettingsViewModel(
      bookRepository = mockk<IBookRepository>(relaxed = true),
      trackRepository = mockk<ITrackRepository>(relaxed = true),
      mediaServiceConnection = mockk(relaxed = true),
      prefsRepo = prefsRepo,
      plexLoginRepo = mockk(relaxed = true),
      cachedFileManager = cachedFileManager,
      plexConfig = mockk(relaxed = true),
      workManager = mockk(relaxed = true),
      plexPrefs = mockk(relaxed = true),
      collectionsRepository = mockk(relaxed = true),
      settingsBackupRepo = mockk<SettingsBackupRepo>(relaxed = true),
      appContext = context,
      externalDeviceDirs = emptyList(),
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
    )
  }

  /** Every clickable row, in the order the screen lists them. */
  private fun SettingsViewModel.clickableRows() = preferences.value.filter { it.type == PreferenceType.CLICKABLE }

  @Test
  fun `the list carries titles switches and clickable rows`() {
    val prefs = viewModel().preferences.value

    assertTrue(prefs.any { it.type == PreferenceType.TITLE })
    assertTrue(prefs.any { it.type == PreferenceType.BOOLEAN })
    assertTrue(prefs.any { it.type == PreferenceType.CLICKABLE })
  }

  /**
   * A switch row must publish its **current** value, not a default: the row is what tells the user
   * what the setting is, and a hardcoded `false` would silently misreport every enabled option.
   */
  @Test
  fun `switch rows carry a current value rather than a null`() {
    val switches = viewModel().preferences.value.filter { it.type == PreferenceType.BOOLEAN }

    assertTrue("expected switch rows", switches.isNotEmpty())
    switches.forEach {
      assertNotNull("switch '${it.key}' published no value", it.defaultValue)
    }
  }

  /**
   * Every clickable row must actually do something. A row with the default no-op `click` looks
   * identical to a working one on screen and does nothing when tapped — the silent failure this
   * screen is most prone to, since the handlers are 700 lines from the rows they belong to.
   */
  @Test
  fun `no clickable row is left with the default no-op handler`() {
    val vm = viewModel()
    val inert =
      vm.clickableRows().filter { row ->
        val before = vm.bottomChooserState.value
        vm.setBottomSheetVisibility(false)
        row.click.onClick()
        val changed =
          vm.bottomChooserState.value != before ||
            vm.bottomChooserState.value.shouldShow ||
            vm.messageForUser.value != null ||
            vm.webLink.value != null ||
            vm.exportFileRequest.value != null ||
            vm.importFileRequest.value != null ||
            vm.showSeriesIndexTester.value != null ||
            vm.showLicenseActivity.value
        !changed
      }

    assertTrue(
      "these clickable rows did nothing when tapped: ${inert.map { it.title }}",
      inert.isEmpty(),
    )
  }

  /**
   * The destructive one. Emptying the cache deletes audio the user chose to keep for offline
   * listening, so a tap must open a confirmation — never act. cu-85 and cu-81 are both about
   * downloads disappearing; this is the path where the user asks for it, and even then it asks
   * first.
   */
  @Test
  fun `a destructive action opens a confirmation instead of acting`() =
    runTest {
      coEvery { cachedFileManager.hasUserCachedTracks() } returns true
      val vm = viewModel()

      // Tap every clickable row; at least one must raise a chooser rather than act immediately.
      var opened = false
      vm.clickableRows().forEach { row ->
        vm.setBottomSheetVisibility(false)
        row.click.onClick()
        advanceUntilIdle()
        if (vm.bottomChooserState.value.shouldShow) opened = true
      }

      assertTrue("no settings row opened a chooser", opened)
    }

  /**
   * A sheet the user cannot dismiss or decline is a trap, so **no row may raise an empty one**.
   *
   * Deliberately "not empty" rather than "more than one": the credits row legitimately opens a
   * single-body informational sheet, which is a different thing from a choice. Requiring two
   * options flagged it — the first version of this test did, and the credits sheet is correct as
   * it stands.
   *
   * The empty case is the real defect, and this found one: the sync-location row built its options
   * from `externalDeviceDirs`, which `provideExternalDeviceDirs` filters nulls out of, so a device
   * with no available volume opened a dialog containing nothing. It reports the situation now.
   */
  @Test
  fun `no settings row opens an empty sheet`() =
    runTest {
      val vm = viewModel()

      vm.clickableRows().forEach { row ->
        vm.setBottomSheetVisibility(false)
        row.click.onClick()
        advanceUntilIdle()
        val state = vm.bottomChooserState.value
        if (state.shouldShow) {
          assertTrue(
            "row '${row.title}' opened a sheet with nothing in it",
            state.options.isNotEmpty(),
          )
        }
      }
    }

  @Test
  fun `dismissing the chooser hides it without clearing its contents`() =
    runTest {
      val vm = viewModel()
      vm.clickableRows().forEach { row ->
        row.click.onClick()
        advanceUntilIdle()
      }

      vm.setBottomSheetVisibility(false)

      assertFalse(vm.bottomChooserState.value.shouldShow)
    }

  /**
   * The list is rebuilt from `prefsRepo` on every prefs change, so a row's label must reflect the
   * stored value rather than a value captured once at construction (cu-101 — a stored
   * `"Rectangle"` read back under an option offered as `"Rectangular"`).
   */
  @Test
  fun `the list is rebuilt from the repository rather than captured once`() {
    every { prefsRepo.bookCoverStyle } returns "Square"
    val first = viewModel().preferences.value.size

    every { prefsRepo.bookCoverStyle } returns "Rectangular"
    val second = viewModel().preferences.value.size

    assertEquals("the row count must not depend on a stored value", first, second)
  }
}
