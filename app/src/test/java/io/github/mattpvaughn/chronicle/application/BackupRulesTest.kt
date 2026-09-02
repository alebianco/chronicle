package io.github.mattpvaughn.chronicle.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Android's Auto Backup is allowed to take.
 *
 * The app keeps its Plex auth token, server access token and user record in
 * `ChronicleAuth.xml`, separate from the settings in `Chronicle.xml` since cu-108. With
 * `allowBackup="true"` and no rules, Auto Backup's default is to include all shared preferences,
 * so working credentials would go to the user's Drive. D8 is explicit that tokens stay on the
 * device.
 *
 * The separation is the point of these tests, and it cuts both ways: the credentials file must be
 * excluded, and the settings file must **not** be — otherwise cu-108 moved the tokens for nothing
 * and the user still loses their preferences on a device transfer.
 *
 * Two files are needed because `dataExtractionRules` is honoured only on API 31+ while minSdk
 * is 27. A rule present in one and missing from the other applies on some devices and not
 * others, which is exactly the kind of gap that stays invisible until it matters — so these
 * tests compare them.
 */
class BackupRulesTest {
  private val extractionRules by lazy { File(EXTRACTION_RULES).readText() }
  private val legacyRules by lazy { File(LEGACY_RULES).readText() }
  private val manifest by lazy { File(MANIFEST).readText() }

  @Test
  fun `the credentials file is excluded from cloud backup`() {
    assertTrue(
      "ChronicleAuth.xml holds the Plex auth token; Auto Backup would upload it by default",
      extractionRules.contains(AUTH_PREFS_FILE),
    )
    assertTrue(
      "API 27-30 devices use fullBackupContent instead, and need the same exclusion",
      legacyRules.contains(AUTH_PREFS_FILE),
    )
  }

  @Test
  fun `the settings file is not excluded`() {
    // The reason cu-108 split the files. Asserted on the parsed `path` attributes rather than
    // with `contains`, because "ChronicleAuth.xml" contains neither more nor less than itself —
    // a substring check here would be answering a different question than it appears to.
    val excludedPaths =
      (exclusionsIn(extractionRules, "cloud-backup") + exclusionsIn(extractionRules, "device-transfer"))
        .mapNotNull { Regex("""path="([^"]+)"""").find(it)?.groupValues?.get(1) }

    assertTrue(
      "settings must survive a restore; only credentials are withheld (cu-108)",
      SETTINGS_PREFS_FILE !in excludedPaths,
    )
    assertTrue(
      "the credentials file must still be among the exclusions",
      AUTH_PREFS_FILE in excludedPaths,
    )
  }

  @Test
  fun `the legacy rules do not exclude the settings file either`() {
    val excludedPaths =
      Regex("""<exclude\s+([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(legacyRules)
        .mapNotNull { Regex("""path="([^"]+)"""").find(it.groupValues[1])?.groupValues?.get(1) }
        .toList()

    assertTrue(SETTINGS_PREFS_FILE !in excludedPaths)
    assertTrue(AUTH_PREFS_FILE in excludedPaths)
  }

  @Test
  fun `databases are excluded from both rule sets`() {
    assertTrue(extractionRules.contains("""<exclude domain="database" />"""))
    assertTrue(legacyRules.contains("""<exclude domain="database" />"""))
  }

  /**
   * A cloud rule without a matching transfer rule still moves a token onto hardware whose
   * owner has not authenticated to Plex.
   */
  @Test
  fun `device transfer is as strict as cloud backup`() {
    val cloudExclusions = exclusionsIn(extractionRules, "cloud-backup")
    val transferExclusions = exclusionsIn(extractionRules, "device-transfer")

    assertEquals(
      "device-transfer must exclude everything cloud-backup does",
      cloudExclusions,
      transferExclusions,
    )
    assertTrue("expected some exclusions, found none", cloudExclusions.isNotEmpty())
  }

  @Test
  fun `both rule files are referenced from the manifest`() {
    assertTrue(
      manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
    )
    assertTrue(
      "without fullBackupContent, API 27-30 falls back to backing up everything",
      manifest.contains("""android:fullBackupContent="@xml/backup_rules""""),
    )
  }

  /** Guards the guard: a wrong path would make every assertion above vacuous. */
  @Test
  fun `all inspected files resolve`() {
    listOf(EXTRACTION_RULES, LEGACY_RULES, MANIFEST).forEach {
      assertTrue("expected $it to exist", File(it).exists())
    }
  }

  /** The `path`/`domain` pairs excluded inside one section of the extraction rules. */
  private fun exclusionsIn(
    xml: String,
    section: String,
  ): Set<String> {
    val body =
      Regex("""<$section>(.*?)</$section>""", RegexOption.DOT_MATCHES_ALL)
        .find(xml)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    return Regex("""<exclude\s+([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(body)
      .map { it.groupValues[1].replace(Regex("""\s+"""), " ").trim() }
      .toSet()
  }

  private companion object {
    /** Relative to the `app` module dir, the unit tests' working directory. */
    const val EXTRACTION_RULES = "src/main/res/xml/data_extraction_rules.xml"
    const val LEGACY_RULES = "src/main/res/xml/backup_rules.xml"
    const val MANIFEST = "src/main/AndroidManifest.xml"

    /** `APP_NAME` is "Chronicle", so the settings file on disk is Chronicle.xml. */
    const val SETTINGS_PREFS_FILE = "Chronicle.xml"

    /** `AUTH_PREFS_NAME` is "ChronicleAuth" (cu-108). */
    const val AUTH_PREFS_FILE = "ChronicleAuth.xml"
  }
}
