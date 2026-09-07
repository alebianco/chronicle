package io.github.mattpvaughn.chronicle.features.download

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What a scan of the download directory found, keeping "nothing there" distinct from "cannot tell".
 *
 * Listing a directory fails when it does not exist or cannot be read, and the scan used to coalesce
 * that to an empty list with `?: emptyList()`. Every track then looked absent and was marked
 * uncached — so an unmounted SD card, a sync directory that had moved, or a volume not yet mounted
 * at launch silently wiped the cached status of a whole library while the files were still on disk.
 * That is the owner's "book reports no cache even if I'm sure I have downloaded it", including the
 * "long time after I downloaded it" variant.
 *
 * The distinction is the fix: an [Unavailable] scan must change nothing. Only a directory that is
 * genuinely readable and genuinely empty may un-cache anything.
 */
sealed interface CacheScanOutcome {
  /** The directory was read. [files] may legitimately be empty. */
  data class Scanned(val files: List<Path>) : CacheScanOutcome

  /**
   * The directory could not be read, so its contents are unknown.
   *
   * Not an error to report to the user on its own — a removable volume being absent is ordinary —
   * but never a reason to conclude that downloads are gone.
   */
  data class Unavailable(val reason: String) : CacheScanOutcome
}

/**
 * Lists the cached-media files in [dir], distinguishing an empty directory from an unreadable one.
 *
 * Checks that the path is a directory as well as that it can be listed: a path that exists as a
 * *file*, or does not exist at all, both fail to list but deserve to be reported as unavailable
 * rather than empty.
 *
 * Takes the [fileSystem] rather than reaching for a global one, which is the point of using Okio
 * here: `FakeFileSystem` can present a directory that exists, is a directory, and still throws on
 * being listed — the permission-denied case, which is the one shape of this bug that could not be
 * built portably out of real temp directories.
 */
fun scanCachedMediaDir(
  dir: Path,
  fileSystem: FileSystem = FileSystem.SYSTEM,
  filter: (Path) -> Boolean,
): CacheScanOutcome {
  val metadata =
    fileSystem.metadataOrNull(dir)
      ?: return CacheScanOutcome.Unavailable("sync directory does not exist: $dir")
  if (!metadata.isDirectory) {
    return CacheScanOutcome.Unavailable("sync directory is not a directory: $dir")
  }
  val listed =
    try {
      fileSystem.list(dir)
    } catch (e: IOException) {
      // Reached when the directory exists and is a directory but cannot be read. `list` throws
      // rather than returning null, so this is a `catch` and not an elvis — the same outcome by a
      // different route.
      return CacheScanOutcome.Unavailable("sync directory is not readable: $dir (${e.message})")
    }
  return CacheScanOutcome.Scanned(listed.filter(filter))
}
