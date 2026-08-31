package io.github.mattpvaughn.chronicle.application

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the cu-42 outcome: no blanket cleartext, and a network security config that is
 * actually wired to the manifest.
 *
 * `usesCleartextTraffic="true"` permitted plaintext HTTP to *any* host, so a hijacked
 * DNS answer could point the app at an attacker and it would happily send the Plex token
 * in the clear. Plex serves LAN connections over HTTPS via its `*.plex.direct` wildcard
 * certificate, so no exception is needed to keep self-hosted servers working.
 */
class CleartextTrafficTest {
  private val manifest by lazy { File(MANIFEST).readText() }

  @Test
  fun `manifest does not permit blanket cleartext traffic`() {
    assertFalse(
      "usesCleartextTraffic allows plaintext to any host, not just a LAN server",
      manifest.contains("usesCleartextTraffic"),
    )
  }

  @Test
  fun `manifest references the network security config`() {
    assertTrue(
      "the config only takes effect if the manifest points at it",
      manifest.contains("""android:networkSecurityConfig="@xml/network_security_config""""),
    )
  }

  @Test
  fun `release config refuses cleartext and grants no exceptions`() {
    val config = File(MAIN_CONFIG).readText()

    assertTrue(
      "base-config must refuse cleartext",
      config.contains("""<base-config cleartextTrafficPermitted="false">"""),
    )
    assertFalse(
      "a release build must grant no cleartext exception to any host",
      config.contains("""cleartextTrafficPermitted="true""""),
    )
  }

  /**
   * The debug build needs loopback for the mock Plex server, but must not relax anything
   * else — otherwise the mock could hide a real plaintext regression.
   */
  @Test
  fun `debug config relaxes loopback only`() {
    val config = File(DEBUG_CONFIG).readText()

    assertTrue(
      "debug base-config must still refuse cleartext",
      config.contains("""<base-config cleartextTrafficPermitted="false">"""),
    )

    val exemptDomains =
      Regex("""<domain[^>]*>([^<]+)</domain>""")
        .findAll(config)
        .map { it.groupValues[1].trim() }
        .toSet()

    assertTrue(
      "debug exemptions must be loopback/emulator-host only, got $exemptDomains",
      exemptDomains.all { it in ALLOWED_DEBUG_DOMAINS },
    )
  }

  /** Guards the guard: a wrong path would make every assertion above vacuous. */
  @Test
  fun `all inspected files resolve`() {
    listOf(MANIFEST, MAIN_CONFIG, DEBUG_CONFIG).forEach {
      assertTrue("expected $it to exist", File(it).exists())
    }
  }

  private companion object {
    /** Paths are relative to the `app` module dir, the unit tests' working directory. */
    const val MANIFEST = "src/main/AndroidManifest.xml"
    const val MAIN_CONFIG = "src/main/res/xml/network_security_config.xml"
    const val DEBUG_CONFIG = "src/debug/res/xml/network_security_config.xml"

    /** Loopback, its hostname alias, and the emulator's route to the host machine. */
    val ALLOWED_DEBUG_DOMAINS = setOf("127.0.0.1", "localhost", "10.0.2.2")
  }
}
