package io.github.mattpvaughn.chronicle.features.settings.licenses

/**
 * Where the licences screen gets its dependency list.
 *
 * A seam, for the reason [LicensesViewModel] needs one: the real implementation reads a **raw
 * Android resource** generated at build time, so a ViewModel that called it directly could not be
 * constructed in a unit test at all (convention 5). The interface is what lets the state machine —
 * loading, loaded, failed — be tested without a `Context`, an APK, or Robolectric.
 */
fun interface LicenseCatalogSource {
  /**
   * Reads the generated catalogue.
   *
   * Suspending because the real implementation parses a few hundred KB of JSON off the main
   * thread. Returns null when the catalogue cannot be read at all, which the screen reports as an
   * error rather than as an empty list — an empty licences page is indistinguishable from a
   * working one that found nothing, and this app always has dependencies.
   */
  suspend fun load(): LicenseCatalog?
}
