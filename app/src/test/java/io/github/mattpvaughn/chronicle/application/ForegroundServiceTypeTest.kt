package io.github.mattpvaughn.chronicle.application

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every declared `foregroundServiceType` must have its matching permission (cu-103).
 *
 * Since Android 14 a service declaring a foreground type also needs the corresponding
 * `FOREGROUND_SERVICE_*` permission. **Omitting it is silent**: the manifest merges, the build
 * passes, lint says nothing useful, and the service still starts — it simply never receives the
 * exemptions its type is supposed to grant.
 *
 * That cost a listening session. `MediaPlayerService` declared `mediaPlayback` while the manifest
 * held only `FOREGROUND_SERVICE_DATA_SYNC`, so the service ran without the media-playback network
 * exemption. With the phone stationary in a car it entered Doze, and roughly twelve minutes into
 * each idle window the app's DNS was refused —
 * `DNS Requested by ..., isBlocked=true` — and ExoPlayer reported a bare `Source error`. Audio
 * stopped mid-chapter with nothing in the app's own log to explain it.
 *
 * Parsing the manifest as text rather than through Robolectric is deliberate: this must fail on the
 * *source* manifest, which is the thing a person edits, not on a merged one assembled at build time.
 */
class ForegroundServiceTypeTest {
  private val manifest = File("src/main/AndroidManifest.xml").readText()

  @Test
  fun `every foreground service type has its permission`() {
    val declaredTypes =
      Regex("""android:foregroundServiceType="([^"]+)"""")
        .findAll(manifest)
        .flatMap { it.groupValues[1].split("|") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    assertTrue(
      "no foregroundServiceType found — this guard would silently pass forever",
      declaredTypes.isNotEmpty(),
    )

    val missing =
      declaredTypes.mapNotNull { type ->
        val permission = "android.permission.FOREGROUND_SERVICE_${type.toSnakeCaseUpper()}"
        if (manifest.contains(permission)) null else "$type needs $permission"
      }

    assertTrue(
      "a foreground service type without its permission starts anyway and silently loses the " +
        "exemptions it is meant to grant:\n" + missing.joinToString("\n"),
      missing.isEmpty(),
    )
  }

  /** `mediaPlayback` -> `MEDIA_PLAYBACK`, matching the permission naming. */
  private fun String.toSnakeCaseUpper(): String = replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
}
