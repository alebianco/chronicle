package io.github.mattpvaughn.chronicle.features.settings.licenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's three display rules.
 *
 * All three exist because the alternative is a page that is *quietly* wrong, which on a compliance
 * artefact is worse than a page that is obviously missing — it looks like diligence.
 */
class LicenseCatalogTest {
  private fun library(
    uniqueId: String,
    name: String = uniqueId,
    version: String = "1.0.0",
    licenses: List<LicenseSummary> = listOf(apache),
  ) = LicensedLibrary(uniqueId = uniqueId, name = name, version = version, licenses = licenses)

  @Test
  fun `orders by display name, ignoring case`() {
    val catalog =
      LicenseCatalog.from(
        listOf(
          library("z:z", name = "zeta"),
          library("a:a", name = "Alpha"),
          library("b:b", name = "beta"),
        ),
      )

    assertEquals(listOf("Alpha", "beta", "zeta"), catalog.libraries.map { it.name })
  }

  /**
   * The tiebreak, and the reason it is not decorative.
   *
   * Several groups publish a library whose POM `<name>` is just "Core" or "Runtime". Without a
   * second sort key their order is whatever the generated JSON happened to list first, which moves
   * under an unrelated dependency bump and turns every such bump into a diff on this page.
   */
  @Test
  fun `breaks display-name ties on the coordinate`() {
    val catalog =
      LicenseCatalog.from(
        listOf(
          library("io.ktor:ktor-core", name = "Core"),
          library("androidx.room:room-core", name = "Core"),
          library("com.example:core", name = "Core"),
        ),
      )

    assertEquals(
      listOf("androidx.room:room-core", "com.example:core", "io.ktor:ktor-core"),
      catalog.libraries.map { it.uniqueId },
    )
  }

  @Test
  fun `de-duplicates on the coordinate, not the display name`() {
    val catalog =
      LicenseCatalog.from(
        listOf(
          library("io.ktor:ktor-core", name = "Core"),
          library("io.ktor:ktor-core", name = "Core"),
          library("androidx.room:room-core", name = "Core"),
        ),
      )

    assertEquals(
      "two different libraries both called \"Core\" are two entries; the same coordinate twice is one",
      listOf("androidx.room:room-core", "io.ktor:ktor-core"),
      catalog.libraries.map { it.uniqueId },
    )
    assertEquals(2, catalog.total)
  }

  /**
   * An entry with no licence is kept.
   *
   * Dropping it is precisely the omission this whole feature exists to rule out: the list would
   * get shorter, the count would agree with the list, and the one dependency whose terms nobody
   * knows would be the one that vanished.
   */
  @Test
  fun `keeps a library that declares no licence`() {
    val catalog = LicenseCatalog.from(listOf(library("com.example:mystery", licenses = emptyList())))

    assertEquals(1, catalog.total)
    assertTrue(catalog.libraries.single().licenses.isEmpty())
  }

  @Test
  fun `keeps every licence of a multi-licensed library`() {
    val both = listOf(apache, LicenseSummary("MIT License", "https://spdx.org/licenses/MIT.html"))
    val catalog = LicenseCatalog.from(listOf(library("com.example:dual", licenses = both)))

    assertEquals(
      "which licence applies is the reader's choice; dropping the alternatives misstates the terms",
      both,
      catalog.libraries.single().licenses,
    )
  }

  @Test
  fun `total counts the entries after de-duplication`() {
    assertEquals(0, LicenseCatalog.EMPTY.total)
    assertEquals(
      1,
      LicenseCatalog.from(listOf(library("a:a"), library("a:a"))).total,
    )
  }

  private companion object {
    val apache = LicenseSummary("Apache License 2.0", "https://spdx.org/licenses/Apache-2.0.html")
  }
}
