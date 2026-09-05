package io.github.mattpvaughn.chronicle.features.settings

import android.content.Context
import android.net.Uri
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SettingsBackupRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
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
 * First tests for [SettingsViewModel], the largest uncovered file in the codebase — **2,980 missed
 * instructions at 0%**.
 *
 * DRAFT-175 proposed splitting a pure `SettingsPreferencesBuilder` out first, on the grounds that
 * 15 constructor dependencies make the class untestable. **That premise was wrong**: nothing here
 * calls the service locator, `init` only registers a prefs listener, and the class constructs fine
 * from fifteen mocks. The extraction is still worth doing for *readability* — a 737-line
 * `makePreferences` is hard to review — but it was not the blocker, and doing it first would have
 * meant restructuring a large function with no tests to catch a mistake.
 *
 * So this comes first, and the refactor can lean on it.
 *
 * Scope is the **backup and restore** surface, because that is where a wrong answer is invisible:
 * every one of the five outcomes has to say something specific, or a refused file reads exactly
 * like a successful restore that changed nothing — the silent failure cu-77 set out to avoid.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val backupRepo = mockk<SettingsBackupRepo>(relaxed = true)
  private val prefsRepo = mockk<PrefsRepo>(relaxed = true)
  private val destination = mockk<Uri>(relaxed = true)

  private fun viewModel(): SettingsViewModel {
    // `makePreferences` runs during construction and reads a resource for every row.
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
      cachedFileManager = mockk<ICachedFileManager>(relaxed = true),
      plexConfig = mockk(relaxed = true),
      workManager = mockk(relaxed = true),
      plexPrefs = mockk(relaxed = true),
      collectionsRepository = mockk(relaxed = true),
      settingsBackupRepo = backupRepo,
      appContext = context,
      externalDeviceDirs = emptyList(),
      exceptionHandler = CoroutineExceptionHandler { _, _ -> },
      dispatchers = TestDispatcherProvider(mainDispatcherRule.testDispatcher.scheduler),
    )
  }

  private fun SettingsViewModel.lastMessage(): FormattableString? = messageForUser.value?.peekContent()

  private fun stringResOf(message: FormattableString?): Int? = (message as? FormattableString.ResourceString)?.stringRes

  @Test
  fun `the preference list is built during construction`() {
    val prefs = viewModel().preferences.value

    assertTrue("settings must not open empty", prefs.isNotEmpty())
  }

  // ---- export ----

  @Test
  fun `a successful export reports how many settings were written`() =
    runTest {
      coEvery { backupRepo.exportTo(destination) } returns
        SettingsBackupRepo.ExportResult.Written(settingCount = 12)
      val vm = viewModel()

      vm.onExportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(R.string.settings_backup_export_response, stringResOf(vm.lastMessage()))
    }

  /**
   * A failed export must not be silent. The user picked a location and expects a file there; a
   * missing message is indistinguishable from success until they go looking for it.
   */
  @Test
  fun `a failed export says so rather than staying quiet`() =
    runTest {
      coEvery { backupRepo.exportTo(destination) } returns
        SettingsBackupRepo.ExportResult.Failed(IllegalStateException("no space"))
      val vm = viewModel()

      vm.onExportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(R.string.settings_backup_export_failed, stringResOf(vm.lastMessage()))
    }

  // ---- import ----

  @Test
  fun `a clean import reports the applied count`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.Applied(applied = 9, skipped = 0)
      val vm = viewModel()

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(R.string.settings_backup_import_response, stringResOf(vm.lastMessage()))
    }

  /**
   * Skipped settings get a *different* message, because "9 applied" while quietly dropping three
   * is the failure mode this reporting exists to prevent (cu-77 — the allowlist gates keys, and a
   * value it cannot use must be visible, not swallowed).
   */
  @Test
  fun `an import with skipped settings reports them separately`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.Applied(applied = 9, skipped = 3)
      val vm = viewModel()

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(
        R.string.settings_backup_import_response_skipped,
        stringResOf(vm.lastMessage()),
      )
    }

  @Test
  fun `a newer file version is refused with its version named`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.WrongVersion(fileVersion = 99)
      val vm = viewModel()

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(
        R.string.settings_backup_import_wrong_version,
        stringResOf(vm.lastMessage()),
      )
    }

  @Test
  fun `an unreadable file is reported rather than swallowed`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.Unreadable(IllegalArgumentException("bad json"))
      val vm = viewModel()

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertEquals(
        R.string.settings_backup_import_unreadable,
        stringResOf(vm.lastMessage()),
      )
    }

  /**
   * The list is rebuilt explicitly after a successful import rather than left to the prefs
   * listener: the import writes the same file the listener watches, in one commit, so relying on
   * the callback's timing would leave the screen showing pre-import values.
   */
  @Test
  fun `a successful import rebuilds the preference list`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.Applied(applied = 1, skipped = 0)
      val vm = viewModel()
      val before = vm.preferences.value

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertNotNull(vm.preferences.value)
      assertEquals(
        "the rebuilt list must have the same shape, not be emptied",
        before.size,
        vm.preferences.value.size,
      )
    }

  /** A refused import must not touch the stored list at all. */
  @Test
  fun `a refused import does not rebuild the list`() =
    runTest {
      coEvery { backupRepo.importFrom(destination) } returns
        SettingsBackupRepo.ImportResult.WrongVersion(fileVersion = 99)
      val vm = viewModel()

      vm.onImportFileChosen(destination)
      advanceUntilIdle()

      assertTrue(vm.preferences.value.isNotEmpty())
    }

  @Test
  fun `a missing document picker is reported instead of crashing`() {
    val vm = viewModel()

    vm.onNoFilePickerAvailable()

    assertEquals(R.string.settings_backup_no_picker, stringResOf(vm.lastMessage()))
  }

  // ---- small surface ----

  @Test
  fun `the licence activity flag round trips`() {
    val vm = viewModel()
    assertFalse(vm.showLicenseActivity.value)

    vm.setShowLicenseActivity(true)
    assertTrue(vm.showLicenseActivity.value)

    vm.setShowLicenseActivity(false)
    assertFalse(vm.showLicenseActivity.value)
  }

  @Test
  fun `the bottom sheet can be dismissed`() {
    val vm = viewModel()

    vm.setBottomSheetVisibility(false)

    assertFalse(vm.bottomChooserState.value.shouldShow)
  }
}
