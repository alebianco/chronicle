package io.github.mattpvaughn.chronicle.data.sources.plex

import de.jensklingenberg.ktorfit.Ktorfit
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every base URL this app hands Ktorfit is one Ktorfit will accept.
 *
 * **This test exists because its absence shipped a 100% launch crash.** `PLACEHOLDER_URL` had no
 * trailing slash — Retrofit tolerated that for years — and `Ktorfit.Builder.baseUrl` validates it
 * and throws `IllegalStateException: Base URL needs to end with /`. The clients are `@Singleton`,
 * so Hilt builds them inside `Application.onCreate`: no window is ever created and the user bounces
 * to the launcher with no crash dialog. Unconditional, on every launch.
 *
 * **1,678 unit tests passed.** The reason is worth stating plainly, because it is a trap and not
 * bad luck: all four tests that built a Ktorfit instance passed `checkUrl = false`, to accommodate
 * a `FakePlexServer.url` that deliberately trims its trailing slash. They therefore switched off
 * *precisely* the validation that fires in production. A test that disables a production check is
 * not testing production.
 *
 * So this asserts both halves:
 *
 * 1. The constants satisfy Ktorfit's rule, by handing them to the real builder.
 * 2. **No test re-introduces `checkUrl = false`.** The first is useless without the second — the
 *    constants were only ever wrong because nothing exercised the check.
 */
class BaseUrlContractTest {
  @Test
  fun `the placeholder base url is one Ktorfit accepts`() {
    // The real builder, not a regex on the string: the rule belongs to Ktorfit, and a local copy
    // of it could drift from the version in the build.
    Ktorfit.Builder().baseUrl(PLACEHOLDER_URL).build()
  }

  @Test
  fun `the plex login base url is one Ktorfit accepts`() {
    // Not passed to a `baseUrl` today — the login endpoints are absolute — but it is the same
    // shape of constant next to the one that broke, so it is pinned before someone uses it.
    Ktorfit.Builder().baseUrl(PLEX_LOGIN_SERVICE_URL).build()
  }

  @Test
  fun `the placeholder host stays unresolvable`() {
    // A request that escapes `plexHeadersPlugin`'s rewrite must fail loudly rather than reach
    // something real. The `.yyy` TLD does not exist, which is the point of the name.
    assertTrue(
      "PLACEHOLDER_URL must not be a resolvable host: $PLACEHOLDER_URL",
      PLACEHOLDER_URL.contains("should-never-be-called") && PLACEHOLDER_URL.endsWith(".yyy/"),
    )
  }

  @Test
  fun `no test disables Ktorfit's base url validation`() {
    // The guard that actually matters. `checkUrl = false` is how the crash reached a device with a
    // green suite, so a test may not opt out of the check production runs.
    val offenders =
      File(TEST_SOURCE_ROOT).walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.name != "BaseUrlContractTest.kt" }
        .filter { file ->
          file.readLines().any { line ->
            val body = line.trimStart()
            !body.startsWith("//") && !body.startsWith("*") && body.contains("checkUrl = false")
          }
        }
        .map { it.name }
        .sorted()
        .toList()

    assertTrue(
      "these tests disable the base-url validation that production relies on, which is exactly " +
        "how `Base URL needs to end with /` reached a device with 1,678 tests green: $offenders. " +
        "Append a trailing slash to the fixture URL instead — `.baseUrl(\"\${server.url}/\")`.",
      offenders.isEmpty(),
    )
  }

  @Test
  fun `the module's own base urls build`() {
    // Guards the guard: if `AppModule` ever stops using these constants, the assertions above stop
    // covering production. Reading the source is crude but it is the only thing that ties the two
    // together without standing up the Hilt graph.
    val module = File(APP_MODULE_PATH).readText()
    assertTrue(
      "AppModule no longer builds its Ktorfit instances from PLACEHOLDER_URL, so this test has " +
        "stopped covering what production does",
      module.contains(".baseUrl(PLACEHOLDER_URL)"),
    )
    // Referenced so the import is real and the constant cannot be renamed out from under this.
    assertTrue(AppModule.OKHTTP_CLIENT_MEDIA.isNotEmpty())
  }

  private companion object {
    const val TEST_SOURCE_ROOT = "src/test/java/io/github/mattpvaughn/chronicle"
    const val APP_MODULE_PATH =
      "src/main/java/io/github/mattpvaughn/chronicle/injection/modules/AppModule.kt"
  }
}
