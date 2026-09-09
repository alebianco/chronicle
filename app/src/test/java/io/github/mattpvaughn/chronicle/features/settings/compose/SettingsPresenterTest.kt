package io.github.mattpvaughn.chronicle.features.settings.compose

import android.content.Context
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.slack.circuit.test.FakeNavigator
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SettingsBackupRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.features.settings.PreferenceModel
import io.github.mattpvaughn.chronicle.features.settings.SettingsViewModel
import io.github.mattpvaughn.chronicle.navigation.LicensesScreenKey
import io.github.mattpvaughn.chronicle.navigation.SeriesIndexTesterScreenKey
import io.github.mattpvaughn.chronicle.navigation.SettingsScreenKey
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings navigation, which took the most unusual shape of the thirteen.
 *
 * Every other screen raises navigation from a tap the presenter can see. Settings cannot: the list
 * is **data**, and each `PreferenceModel` carries its own `click` lambda, so "the user tapped the
 * licences row" is indistinguishable at the presenter from "the user tapped anything else". The
 * ViewModel raises `showLicenses` and `showSeriesIndexTester` as one-shot `Event` flows instead,
 * and the presenter collects them.
 *
 * That is worth a test precisely because it is the branch that does **not** follow the pattern. It
 * also asserts something the `*Destination` could not: the collector runs inside `present()`, so a
 * screen with no `Ui` attached still routes — the old `EventEffect` needed a composition with a
 * `LocalLifecycleOwner` in it.
 *
 * Robolectric, unlike the other presenter tests, only because `makePreferences` resolves a string
 * resource for all 36 rows during construction.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPresenterTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

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
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexLoginRepo = mockk(relaxed = true),
      cachedFileManager = mockk<ICachedFileManager>(relaxed = true),
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

  /** The real row, found by its title resource rather than by position. */
  private fun SettingsCircuitState.row(titleRes: Int): PreferenceModel =
    rows.map { it.model }.first { it.title == FormattableString.from(titleRes) }

  @Test
  fun `tapping the licences row navigates to the licences screen`() =
    runTest {
      val navigator = FakeNavigator(SettingsScreenKey)
      val vm = viewModel()
      val presenter = SettingsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        val state = awaitItem()
        state.eventSink(SettingsEvent.RowClicked(state.row(R.string.settings_licenses_title)))

        assertEquals(LicensesScreenKey, navigator.awaitNextScreen())
        cancel()
      }
    }

  @Test
  fun `tapping the series rules row navigates to the tester`() =
    runTest {
      val navigator = FakeNavigator(SettingsScreenKey)
      val vm = viewModel()
      val presenter = SettingsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        val state = awaitItem()
        state.eventSink(SettingsEvent.RowClicked(state.row(R.string.settings_series_rules_title)))

        assertEquals(SeriesIndexTesterScreenKey, navigator.awaitNextScreen())
        cancel()
      }
    }

  /**
   * Settings is a leaf of no one: it is a bottom-nav root, so it must never navigate on its own.
   *
   * Asserted because both branches above are driven by flows the presenter subscribes to, and a
   * subscription that fired on its seed rather than on an event would send the user to the licences
   * screen the moment they opened Settings.
   */
  @Test
  fun `opening settings navigates nowhere by itself`() =
    runTest {
      val navigator = FakeNavigator(SettingsScreenKey)
      val vm = viewModel()
      val presenter = SettingsPresenter({ vm }, navigator)

      moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
        awaitItem()

        navigator.assertGoToIsEmpty()
        navigator.assertPopIsEmpty()
        cancel()
      }
    }
}
