package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicenseCatalog
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicenseSummary
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensedLibrary
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicensesUiState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The licences page, asserted on what it renders.
 *
 * The screen is a compliance artefact, so the things worth pinning are the ones whose failure would
 * be *invisible*: an entry silently dropped, a count that disagrees with the list, a licence styled
 * as a link that goes nowhere.
 *
 * A Compose test measures whatever width it is told, so this proves the content and not the layout
 * — the device check in both orientations is a separate obligation this cannot discharge.
 */
@RunWith(RobolectricTestRunner::class)
class LicensesScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun setScreen(
    state: LicensesUiState,
    onLicenseClick: (LicenseSummary) -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        LicensesScreen(state = state, onLicenseClick = onLicenseClick)
      }
    }
  }

  private fun loaded(vararg libraries: LicensedLibrary) = LicensesUiState.Loaded(LicenseCatalog.from(libraries.toList()))

  @Test
  fun `renders every library it was given`() {
    setScreen(loaded(compose3, ktor))

    compose.onNodeWithText("Compose UI").assertIsDisplayed()
    compose.onNodeWithText("Ktor client core").assertIsDisplayed()
  }

  /**
   * The count is on the page, and it is the list's own length.
   *
   * The number is the one claim a reader can check without auditing the build, so it must not be a
   * separately maintained figure that can disagree with the rows beneath it.
   */
  @Test
  fun `renders the dependency count`() {
    setScreen(loaded(compose3, ktor))

    compose.onNodeWithText("2 libraries").assertIsDisplayed()
  }

  @Test
  fun `renders the singular count for one dependency`() {
    setScreen(loaded(compose3))

    compose.onNodeWithText("1 library").assertIsDisplayed()
  }

  /**
   * A dependency that declares no licence is shown and marked, never hidden.
   *
   * Hiding it would make the page shorter and less true at the same time — and the count would
   * agree with the shortened list, so nothing would look wrong.
   */
  @Test
  fun `marks a library that declares no licence instead of dropping it`() {
    setScreen(loaded(unlicensed))

    compose.onNodeWithText("An artifact with no licence in its POM").assertIsDisplayed()
    compose.onNodeWithText("No license declared").assertIsDisplayed()
    compose.onNodeWithText("1 library").assertIsDisplayed()
  }

  @Test
  fun `renders the coordinate and version beside the name`() {
    setScreen(loaded(compose3))

    compose.onNodeWithText("androidx.compose.ui:ui:1.9.4").assertIsDisplayed()
  }

  @Test
  fun `tapping a licence reports the licence that was tapped`() {
    var tapped: LicenseSummary? = null
    setScreen(loaded(compose3), onLicenseClick = { tapped = it })

    compose.onNodeWithText("Apache License 2.0").performClick()

    assertEquals(apache, tapped)
  }

  /**
   * A licence with no URL is inert.
   *
   * The pair with the test above. A dead link on a compliance page is worse than plain text: it
   * offers terms it cannot show, which is the "looks like diligence" failure in miniature.
   */
  @Test
  fun `a licence with no URL is not clickable`() {
    var taps = 0
    setScreen(loaded(linkless), onLicenseClick = { taps++ })

    compose.onNodeWithText("A licence with no canonical URL").performClick()

    assertEquals(0, taps)
  }

  /**
   * A failed read says so, rather than rendering an empty list.
   *
   * An empty licences page and a broken one look identical to a reader, and this app always has
   * dependencies — so "no libraries" is never a true thing for this screen to render.
   */
  @Test
  fun `a failed read renders an explanation, not an empty list`() {
    setScreen(LicensesUiState.Failed)

    compose.onNodeWithText("The license list could not be read", substring = true).assertIsDisplayed()
  }

  @Test
  fun `the intro names Chronicle's own licence, which is a separate obligation`() {
    setScreen(loaded(compose3))

    compose.onNodeWithText("GNU General Public License v3", substring = true).assertIsDisplayed()
  }

  private companion object {
    val apache = LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")

    val compose3 =
      LicensedLibrary(
        uniqueId = "androidx.compose.ui:ui",
        name = "Compose UI",
        version = "1.9.4",
        licenses = listOf(apache),
      )

    val ktor =
      LicensedLibrary(
        uniqueId = "io.ktor:ktor-client-core",
        name = "Ktor client core",
        version = "3.2.1",
        licenses = listOf(apache),
      )

    val unlicensed =
      LicensedLibrary(
        uniqueId = "com.example:mystery",
        name = "An artifact with no licence in its POM",
        version = "1.0.0",
        licenses = emptyList(),
      )

    val linkless =
      LicensedLibrary(
        uniqueId = "com.example:linkless",
        name = "An artifact whose licence has no URL",
        version = "1.0.0",
        licenses = listOf(LicenseSummary("A licence with no canonical URL", null)),
      )
  }
}
