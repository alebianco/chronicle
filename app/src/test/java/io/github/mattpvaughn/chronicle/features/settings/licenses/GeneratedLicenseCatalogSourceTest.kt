package io.github.mattpvaughn.chronicle.features.settings.licenses

import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reader actually finds and opens the generated resource.
 *
 * `LicenseCatalogParserTest` proves the parse and `LicensesScreenTest` proves the rendering, both
 * against hand-built input — neither says whether the raw resource is *there*. A wrong resource id,
 * a plugin no longer applied to this variant, or a generated file that never reached the merged
 * resources would leave the screen reporting a failure with every other test green.
 *
 * Robolectric runs against **debug** resources, so this asserts the debug catalogue.
 * `LicenseCatalogCountTest` reconciles against release, which is what ships; this one only answers
 * "can the app open the file at all".
 */
@RunWith(RobolectricTestRunner::class)
class GeneratedLicenseCatalogSourceTest {
  private val source =
    GeneratedLicenseCatalogSource(
      context = ApplicationProvider.getApplicationContext(),
      dispatchers = TestDispatcherProvider(),
    )

  @Test
  fun `reads the generated catalogue out of the packaged resources`() =
    runTest {
      val catalog = source.load()

      assertNotNull("the generated raw resource was not found or not parsed", catalog)
      assertTrue(
        "read a catalogue of ${catalog?.total} dependencies, which is too few to be this app's",
        (catalog?.total ?: 0) > 100,
      )
    }

  /** Every entry the reader produces is usable: an identity and a name, neither of them blank. */
  @Test
  fun `every entry it produces carries a coordinate and a name`() =
    runTest {
      val libraries = source.load()?.libraries.orEmpty()

      assertTrue("read nothing at all", libraries.isNotEmpty())
      assertTrue(
        "these entries have a blank coordinate or name: " +
          libraries.filter { it.uniqueId.isBlank() || it.name.isBlank() }.map { it.uniqueId },
        libraries.none { it.uniqueId.isBlank() || it.name.isBlank() },
      )
    }
}
