package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.testing.testSettingsDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `cachedMediaDir` — where downloaded audio lives, and the only preference whose *getter* has a
 * side effect.
 *
 * The implementation was uncovered. Every existing suite that mentions `cachedMediaDir` mocks the
 * repository and asserts against the value it was told to return, which cannot see the two
 * decisions this getter actually makes:
 *
 *  1. **A stored path always wins.** This is what stops the sync location from drifting when
 *     `getExternalFilesDirs` reorders its result — which it does when an SD card is unmounted.
 *     the rule is that an absent volume must read as unavailable rather than silently
 *     resolving somewhere else; persisting the choice is how that is enforced.
 *  2. **First run picks a default and persists it immediately**, so the ordering is consulted
 *     exactly once in the install's lifetime.
 *
 * Getting either wrong strands downloaded files: the prune only ever scans the *active*
 * `cachedMediaDir`, so a directory the app stops pointing at is never cleaned and never played.
 *
 * Robolectric because the last-resort branch reads `appContext.filesDir`.
 */
@RunWith(RobolectricTestRunner::class)
class CachedMediaDirTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

  private fun repo(
    name: String,
    externalDirs: List<File>,
  ) = DataStorePrefsRepo(
    settings = testSettingsDataStore(name),
    appContext = context,
    externalDeviceDirs = externalDirs,
  )

  @Test
  fun `a stored sync location is returned verbatim`() {
    val repo = repo("stored", listOf(File("/storage/emulated/0/Android/data")))
    val chosen = File("/storage/1234-5678/Android/data/files")

    repo.cachedMediaDir = chosen

    assertEquals(chosen.absolutePath, repo.cachedMediaDir.absolutePath)
  }

  /**
   * The unmounted-SD-card case: the SD card the user chose is gone, so `externalDeviceDirs` no longer lists
   * it. The stored path must still be returned — reading as unavailable — rather than silently
   * falling back to internal storage, which would leave the downloads on the card orphaned and
   * invisible to the prune.
   */
  @Test
  fun `a stored location survives its volume disappearing from the device list`() {
    val sdCard = File("/storage/1234-5678/Android/data/files")
    // One store, read by both instances: the second stands for a relaunch after the card was
    // pulled, so it must see what the first persisted. A second `testSettingsDataStore` call
    // would create a fresh temp file and quietly test nothing.
    val store = testSettingsDataStore("unmounted")
    val repo =
      DataStorePrefsRepo(
        settings = store,
        appContext = context,
        externalDeviceDirs = listOf(File("/storage/emulated/0/Android/data")),
      )
    repo.cachedMediaDir = sdCard

    val afterUnmount =
      DataStorePrefsRepo(
        settings = store,
        appContext = context,
        // The card is no longer enumerated.
        externalDeviceDirs = listOf(File("/storage/emulated/0/Android/data")),
      )

    assertEquals(sdCard.absolutePath, afterUnmount.cachedMediaDir.absolutePath)
  }

  @Test
  fun `first run picks the first external directory`() {
    val first = File("/storage/emulated/0/Android/data/files")
    val second = File("/storage/1234-5678/Android/data/files")

    assertEquals(first.absolutePath, repo("firstrun", listOf(first, second)).cachedMediaDir.absolutePath)
  }

  /**
   * The choice is persisted on read, not merely returned — otherwise a later reordering of
   * `getExternalFilesDirs` would silently move the sync location under the user's downloads.
   */
  @Test
  fun `the first-run choice is persisted so later reordering cannot move it`() {
    val first = File("/storage/emulated/0/Android/data/files")
    val second = File("/storage/1234-5678/Android/data/files")
    // One store shared by both instances: the point is that the *persisted* choice survives a
    // change in volume ordering, so they must read the same settings.
    val shared = testSettingsDataStore("persisted")

    val initial =
      DataStorePrefsRepo(shared, listOf(first, second), context).cachedMediaDir

    // The platform now enumerates the volumes the other way round.
    val reordered =
      DataStorePrefsRepo(shared, listOf(second, first), context).cachedMediaDir

    assertEquals(first.absolutePath, initial.absolutePath)
    assertEquals(
      "the persisted choice must win over the new ordering",
      first.absolutePath,
      reordered.absolutePath,
    )
  }

  /**
   * `filesDir` is the last resort because it is the one directory always present. An emulator or a
   * device with no external volumes at all must still get a writable location rather than throwing
   * on a `first()`.
   */
  @Test
  fun `with no external directories it falls back to internal storage`() {
    val dir = repo("nodirs", emptyList()).cachedMediaDir

    assertEquals(context.filesDir.absolutePath, dir.absolutePath)
    assertTrue("the fallback must be a real, writable location", dir.absolutePath.isNotEmpty())
  }
}
