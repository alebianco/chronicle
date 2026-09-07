package io.github.mattpvaughn.chronicle.features.settings.licenses

import io.github.mattpvaughn.chronicle.data.ChronicleJson

/**
 * Turns the generated `aboutlibraries.json` into the catalogue the screen renders.
 *
 * **Framework-free on purpose.** Reading a raw resource needs a `Context`; turning its bytes into
 * a list does not, and separating them is what makes the parse — the part with the fallbacks and
 * the licence-id lookup, so the part that can be wrong — testable without Robolectric. The Android
 * half is [GeneratedLicenseCatalogSource] and does nothing but supply the string.
 */
object LicenseCatalogParser {
  /**
   * Parses [json], or returns null if it cannot be read at all.
   *
   * Null rather than an empty catalogue: an empty licences page and a broken one are
   * indistinguishable to a reader, and this app always has dependencies, so "successfully read,
   * and there are none" is never a true statement about it. The caller renders an error instead.
   */
  fun parse(json: String): LicenseCatalog? {
    val catalog = runCatching { ChronicleJson.decodeFromString<GeneratedCatalog>(json) }.getOrNull() ?: return null
    return LicenseCatalog.from(catalog.libraries.map { toLicensedLibrary(it, catalog.licenses) })
  }

  private fun toLicensedLibrary(
    library: GeneratedLibrary,
    licenses: Map<String, GeneratedLicense>,
  ) = LicensedLibrary(
    uniqueId = library.uniqueId,
    // A handful of artifacts carry no <name> in their POM. The coordinate is always present, so it
    // is the fallback — an entry with an empty title reads as a rendering bug and hides which
    // dependency it even is.
    name = library.name?.takeIf { it.isNotBlank() } ?: library.uniqueId,
    version = library.artifactVersion.orEmpty(),
    licenses = library.licenses.mapNotNull { id -> licenses[id]?.let { toSummary(id, it) } },
  )

  private fun toSummary(
    id: String,
    license: GeneratedLicense,
  ) = LicenseSummary(
    // The SPDX id before the raw key, because "Apache-2.0" reads better than a hash-like id if the
    // generator ever changes its keying. The key itself is the last resort, never a blank label.
    name =
      license.name?.takeIf { it.isNotBlank() }
        ?: license.spdxId?.takeIf { it.isNotBlank() }
        ?: id,
    // Blank normalised to null here rather than at the render site, so the composable has one
    // thing to check instead of two.
    url = license.url?.takeIf { it.isNotBlank() },
  )
}
