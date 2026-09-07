package io.github.mattpvaughn.chronicle.features.settings.licenses

/**
 * One third-party dependency, as the licences screen shows it.
 *
 * Deliberately **not** AboutLibraries' own `Library`: that type carries twelve fields the screen
 * never renders, has no ordering, and would put a library type into the signature of every
 * composable and every test. This is the projection the screen actually needs, which is also what
 * makes [LicenseCatalog.from] a pure function worth testing.
 *
 * @param uniqueId the Maven coordinate, `group:artifact`. The stable identity — [name] is a
 *   *display* name and several groups publish one called "Core" or "Runtime", so a list key or a
 *   duplicate check must use this instead.
 * @param licenses the licences the artifact is offered under. A dependency offered under a choice
 *   lists all of them, because which one applies is the reader's choice to make and dropping the
 *   alternatives would misstate the terms. Can be empty — see [LicenseCatalog.from].
 */
data class LicensedLibrary(
  val uniqueId: String,
  val name: String,
  val version: String,
  val licenses: List<LicenseSummary>,
)

/**
 * One licence, named and linked.
 *
 * [url] is nullable because the metadata can carry a licence with no canonical URL. That renders as
 * a name with nothing to tap rather than as a dead link — a link that goes nowhere is exactly the
 * "looks like diligence" failure this screen exists to avoid.
 */
data class LicenseSummary(
  val name: String,
  val url: String?,
)

/**
 * The third-party dependency list, generated from the resolved dependency graph.
 *
 * ### Why the count is part of the state
 *
 * A generated page that silently misses a dependency is worse than none, because it looks like
 * diligence. [total] is rendered on the screen so the number is visible rather than implied, and
 * `LicenseCatalogCountTest` reconciles it against the `aboutlibraries.json` the Gradle plugin
 * writes from `releaseRuntimeClasspath` — so an entry lost between the graph and the screen fails
 * the build instead of quietly shortening the list.
 */
data class LicenseCatalog(
  val libraries: List<LicensedLibrary>,
) {
  /** How many dependencies the catalogue names. */
  val total: Int
    get() = libraries.size

  companion object {
    /** An empty catalogue — the pre-load state, distinct from "loaded and genuinely empty". */
    val EMPTY = LicenseCatalog(emptyList())

    /**
     * Builds the catalogue from raw entries, de-duplicated and ordered for display.
     *
     * Three rules live here rather than in the composable, because the screen must not be free to
     * disagree with any of them:
     *
     * - **Ordered by display name, case-insensitively**, then by [LicensedLibrary.uniqueId] to
     *   break the ties display names genuinely produce. Without the tiebreak, two libraries both
     *   called "Core" sit in whatever order the JSON happened to list them, which moves under an
     *   unrelated dependency bump.
     * - **De-duplicated by `uniqueId`, keeping the first.** The Gradle plugin already merges
     *   platform artifacts, but a catalogue rendering one coordinate twice would inflate the count
     *   this screen publishes as evidence.
     * - **An entry with no licence at all is kept, never dropped.** Dropping it is precisely the
     *   omission this screen exists to rule out; it renders with an explicit "no licence declared"
     *   marker instead, which is a finding rather than a gap.
     */
    fun from(entries: List<LicensedLibrary>): LicenseCatalog =
      LicenseCatalog(
        entries
          .distinctBy { it.uniqueId }
          .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, LicensedLibrary::name)
              .thenBy(LicensedLibrary::uniqueId),
          ),
      )
  }
}
