package io.github.mattpvaughn.chronicle.application

import io.github.mattpvaughn.chronicle.data.sources.plex.AUTH_PREFS_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Android's Auto Backup is allowed to take.
 *
 * The app keeps its Plex auth token, server access token and user record in
 * `ChronicleAuth.xml`, separate from the settings in `Chronicle.xml`. With
 * `allowBackup="true"` and no rules, Auto Backup's default is to include all shared preferences,
 * so working credentials would go to the user's Drive. D8 is explicit that tokens stay on the
 * device.
 *
 * The separation is the point of these tests, and it cuts both ways: the credentials file must be
 * excluded, and the settings file must **not** be — otherwise this split moved the tokens for
 * nothing and the user still loses their preferences on a device transfer.
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
    // The reason the files were split. Asserted on the parsed `path` attributes rather than
    // with `contains`, because "ChronicleAuth.xml" contains neither more nor less than itself —
    // a substring check here would be answering a different question than it appears to.
    val excludedPaths =
      (exclusionsIn(extractionRules, "cloud-backup") + exclusionsIn(extractionRules, "device-transfer"))
        .mapNotNull { Regex("""path="([^"]+)"""").find(it)?.groupValues?.get(1) }

    assertTrue(
      "settings must survive a restore; only credentials are withheld",
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

  /**
   * The excluded path must name where the credentials **actually** live.
   *
   * Every other test here compares the two rules files to each other, which cannot notice if both
   * are wrong in the same way. That gap is not hypothetical: an Auto Backup exclusion is scoped to
   * a *domain* — `sharedpref` — and a filename, so moving the credential store to DataStore (which
   * writes under `files/datastore/`, the `file` domain) makes both rules match nothing while every
   * existing assertion here keeps passing, and tokens begin going to the user's Drive.
   *
   * So this reads the production constant. `AUTH_PREFS_NAME` is what `AppModule.provideAuthPrefs`
   * hands `getSharedPreferences`, and Android appends `.xml` — if the store ever stops being a
   * `SharedPreferences` file, this fails and the rules have to be rewritten deliberately rather
   * than silently becoming decorative.
   */
  @Test
  fun `the excluded path is where the credentials are actually stored`() {
    val storedAs = "$AUTH_PREFS_NAME.xml"

    assertEquals(
      "the exclusion names a file the app no longer writes — check whether the credential store " +
        "moved (DataStore lives in the `file` domain, not `sharedpref`, so a `sharedpref` " +
        "exclusion silently stops matching)",
      storedAs,
      AUTH_PREFS_FILE,
    )
    assertTrue(
      "and both rules files must exclude that exact path",
      extractionRules.contains(storedAs) && legacyRules.contains(storedAs),
    )
  }

  /**
   * The credential store is still a `SharedPreferences` file.
   *
   * Paired with the test above: that one proves the *name* matches, this proves the *mechanism*
   * still is what the `domain="sharedpref"` rules assume. A migration to DataStore has to fail
   * here, because the rules cannot be corrected by anyone who does not know they broke.
   */
  @Test
  fun `the credentials live in no_backup, which Android excludes by construction`() {
    // Replaces an assertion that the credential store was still a SharedPreferences file. That
    // guard was right while the XML rules were the only protection, and it did its job: it failed
    // the moment the store moved, which forced this to be a deliberate change rather than a silent
    // one. `noBackupFilesDir` is excluded by Android itself, so there is no longer a rule to keep
    // in sync across two files and two API levels — and therefore none that can quietly lapse.
    val store = File(CREDENTIAL_STORE).readText()

    assertTrue(
      "CredentialStore no longer writes to noBackupFilesDir. That directory is what keeps the " +
        "Plex tokens out of Auto Backup (D8); moving the store elsewhere removes the protection, " +
        "and no XML rule is covering it any more.",
      store.contains("context.noBackupFilesDir"),
    )
  }

  @Test
  fun `the legacy credentials file is still excluded`() {
    // An install that has not launched since the migration still has ChronicleAuth.xml on disk
    // with live tokens in it. Dropping this entry early would back up exactly those users.
    assertTrue(
      "an unmigrated install still has ChronicleAuth.xml with real tokens in it",
      extractionRules.contains(AUTH_PREFS_FILE) && legacyRules.contains(AUTH_PREFS_FILE),
    )
  }

  private companion object {
    /** Relative to the `app` module dir, the unit tests' working directory. */
    const val EXTRACTION_RULES = "src/main/res/xml/data_extraction_rules.xml"
    const val LEGACY_RULES = "src/main/res/xml/backup_rules.xml"
    const val MANIFEST = "src/main/AndroidManifest.xml"
    const val CREDENTIAL_STORE =
      "src/main/java/io/github/mattpvaughn/chronicle/data/sources/plex/CredentialStore.kt"

    /** `APP_NAME` is "Chronicle", so the settings file on disk is Chronicle.xml. */
    const val SETTINGS_PREFS_FILE = "Chronicle.xml"

    /** `AUTH_PREFS_NAME` is "ChronicleAuth". */
    const val AUTH_PREFS_FILE = "ChronicleAuth.xml"
  }
}
