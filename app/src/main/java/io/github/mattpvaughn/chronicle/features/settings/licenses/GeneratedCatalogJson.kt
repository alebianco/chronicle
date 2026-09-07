package io.github.mattpvaughn.chronicle.features.settings.licenses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape of `res/raw/aboutlibraries.json`, as the AboutLibraries Gradle plugin writes it.
 *
 * ### Why the app declares this instead of using the library's own reader
 *
 * `aboutlibraries-core` 14.0.0 and newer are compiled for **Java 21** (class file major 65) while
 * this project is Java 17 throughout. The class loads on a device, where everything is dexed, but
 * a JVM unit test cannot construct it at all — `UnsupportedClassVersionError` — so the reader
 * would have been the one part of this feature no unit test could reach. The last Java-17 line is
 * 13.x, a version behind and carrying an extra transitive dependency.
 *
 * The *plugin* is Java 17 and runs on the Gradle daemon exactly as intended, so only the runtime
 * artifact was ever in question. Declaring the six fields the screen reads costs less than either
 * an outdated pin or a Java bump, and it adds **no** dependency: the JSON is parsed with the
 * `ChronicleJson` this app already ships.
 *
 * `ignoreUnknownKeys` (from `ChronicleJson`) is what makes this safe against a plugin upgrade: the
 * generator writes a dozen more fields per library — developers, funding, SCM, targets — and a new
 * one must not break the screen. Every property here is optional for the same reason, since the
 * plugin omits what a POM does not supply.
 */
@Serializable
internal data class GeneratedCatalog(
  val libraries: List<GeneratedLibrary> = emptyList(),
  /** Keyed by the id each library's `licenses` array refers to, e.g. `"Apache-2.0"`. */
  val licenses: Map<String, GeneratedLicense> = emptyMap(),
)

@Serializable
internal data class GeneratedLibrary(
  val uniqueId: String,
  val artifactVersion: String? = null,
  val name: String? = null,
  /** License ids, resolved against [GeneratedCatalog.licenses]. */
  val licenses: List<String> = emptyList(),
)

@Serializable
internal data class GeneratedLicense(
  val name: String? = null,
  val url: String? = null,
  @SerialName("spdxId") val spdxId: String? = null,
)
