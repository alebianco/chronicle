package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Keeps "the directory is empty" distinct from "the directory cannot be read".
 *
 * The cache scan used to do `listFiles(...) ?: emptyList()`. `listFiles` returns null for a missing
 * or unreadable directory, so both cases became "no files found" — and the scan then marked every
 * track uncached. An unmounted SD card, a sync directory that had moved, or a volume not yet mounted
 * at launch therefore wiped the cached status of a whole library while the audio was still on disk
 * (*"book reports no cache even if I'm sure I have downloaded it"*).
 */
class CacheScanOutcomeTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val anyFile: (okio.Path) -> Boolean = { true }
  private val cachedMedia: (okio.Path) -> Boolean = { MediaItemTrack.cachedFilePattern.matches(it.name) }

  /** The legitimate case that *must* still un-cache: readable, and really empty. */
  @Test
  fun `an empty readable directory scans as empty, not unavailable`() {
    val outcome = scanCachedMediaDir(folder.newFolder("empty").toOkioPath(), filter = anyFile)

    assertTrue("an empty directory is a real answer", outcome is CacheScanOutcome.Scanned)
    assertEquals(emptyList<File>(), (outcome as CacheScanOutcome.Scanned).files)
  }

  /** The regression: a directory that is not there must never read as "nothing is downloaded". */
  @Test
  fun `a missing directory is unavailable, not empty`() {
    val missing = File(folder.root, "not-mounted")

    val outcome = scanCachedMediaDir(missing.toOkioPath(), filter = anyFile)

    assertTrue(
      "a missing sync directory must not look like an empty one",
      outcome is CacheScanOutcome.Unavailable,
    )
  }

  /**
   * A path that exists but is a *file* also returns null from `listFiles`. Reported as unavailable
   * rather than empty, because it means the sync location is wrong, not that downloads are gone.
   */
  @Test
  fun `a path that is a file rather than a directory is unavailable`() {
    val notADir = folder.newFile("i-am-a-file")

    assertTrue(scanCachedMediaDir(notADir.toOkioPath(), filter = anyFile) is CacheScanOutcome.Unavailable)
  }

  @Test
  fun `an unavailable outcome carries a reason naming the path`() {
    val missing = File(folder.root, "not-mounted")

    val outcome = scanCachedMediaDir(missing.toOkioPath(), filter = anyFile) as CacheScanOutcome.Unavailable

    assertTrue(
      "the reason must name the path, or a bug report cannot be acted on",
      outcome.reason.contains("not-mounted"),
    )
  }

  /**
   * The pattern's job is "is this filename shaped like one of ours?", and that now
   * includes non-numeric ids — so a stray `notes.txt` is shaped like a track whose id is `notes`
   * and does match. That is deliberate and safe: the caller
   * (`CachedFileManager.refreshTrackDownloadedStatus`) looks the id up in the database and
   * requires the file's length to equal the stored size, so an unknown id is dropped a moment
   * later. See `CachedFilePatternTest` for why narrowing this to digits was worse — it made an
   * Audiobookshelf id invisible to the scan, so it was deleted and re-downloaded forever.
   *
   * What must still be excluded is anything that cannot be an id at all: a dotfile (no id before
   * the dot) and a partial download (a second extension).
   */
  @Test
  fun `only files shaped like a cached track are returned`() {
    val dir = folder.newFolder("media")
    File(dir, "3001.mp3").writeText("audio")
    File(dir, "3002.m4b").writeText("audio")
    File(dir, "li_8x2h9fk3.m4b").writeText("audio")
    File(dir, ".hidden").writeText("not a track")
    File(dir, "3003.mp3.part").writeText("partial download")

    val outcome = scanCachedMediaDir(dir.toOkioPath(), filter = cachedMedia) as CacheScanOutcome.Scanned

    assertEquals(
      listOf("3001.mp3", "3002.m4b", "li_8x2h9fk3.m4b"),
      outcome.files.map { it.name }.sorted(),
    )
  }

  /**
   * A `.part` file must not be mistaken for a finished download — the pattern rejects a second
   * extension. Pinned here as well as in `CachedFilePatternTest` because this is the call site
   * where a loose pattern would promote a partial file to "available offline".
   */
  @Test
  fun `a partial download is not returned by the scan`() {
    val dir = folder.newFolder("partials")
    File(dir, "3001.mp3.part").writeText("half")

    val outcome = scanCachedMediaDir(dir.toOkioPath(), filter = cachedMedia) as CacheScanOutcome.Scanned

    assertTrue(outcome.files.isEmpty())
  }

  /**
   * Each guard must be distinguishable, not merely "unavailable".
   *
   * All three conditions returned `Unavailable`, and the existing tests only asserted the *type* —
   * so any one of the three could be removed and the next guard would produce an indistinguishable
   * result. All three mutants survived. Asserting on the reason pins which branch actually fired.
   */
  @Test
  fun `each unavailable reason identifies its own cause`() {
    val missing = File(folder.root, "definitely-absent")
    val notADir = folder.newFile("a-file.txt")

    val missingReason = (scanCachedMediaDir(missing.toOkioPath(), filter = anyFile) as CacheScanOutcome.Unavailable).reason
    val notADirReason = (scanCachedMediaDir(notADir.toOkioPath(), filter = anyFile) as CacheScanOutcome.Unavailable).reason

    assertTrue("a missing directory says so: $missingReason", missingReason.contains("does not exist"))
    assertTrue("a plain file says so: $notADirReason", notADirReason.contains("not a directory"))
  }

  /** An existing, readable directory must not be reported as missing. */
  @Test
  fun `a real directory does not report the missing-directory reason`() {
    val outcome = scanCachedMediaDir(folder.root.toOkioPath(), filter = anyFile)

    assertTrue("a real directory scans", outcome is CacheScanOutcome.Scanned)
  }

  /**
   * The branch that was the bug: a directory that exists, is a directory, and still cannot be read.
   *
   * **This is the case Okio was adopted for.** With real temp directories it could only be built by
   * chmod, which silently does nothing as root or on a filesystem that ignores permission bits — so
   * the test was guarded by two `assumeTrue`s and skipped itself exactly where it mattered. Its own
   * comment said "a test that cannot fail is worse than no test", and that was the honest reading.
   *
   * `FakeFileSystem` makes the condition first-class: the directory is present and listing it
   * throws, deterministically, on every machine.
   */
  @Test
  fun `an unreadable directory is unavailable, not empty`() {
    val locked = "/sync".toPath()
    // A filesystem that is fine in every respect except that listing this directory throws — which
    // is exactly what a revoked read looks like to the scan, and what chmod could not be relied on
    // to produce. `FakeFileSystem.close()` was tried first and models something different: it
    // throws IllegalStateException ("filesystem closed"), not an IOException, so it would have
    // tested the wrong failure.
    val fs =
      object : ForwardingFileSystem(FakeFileSystem()) {
        override fun list(dir: Path): List<Path> = throw IOException("Permission denied")
      }
    fs.createDirectories(locked)
    fs.write(locked / "cached.mp3") { writeUtf8("x") }

    val outcome = scanCachedMediaDir(locked, fs, anyFile)

    assertTrue(
      "an unreadable directory must not read as an empty one; that un-caches the library",
      outcome is CacheScanOutcome.Unavailable,
    )
  }
}
