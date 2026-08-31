package io.github.mattpvaughn.chronicle.features.download

import java.io.File
import java.io.FileFilter

/**
 * What a scan of the download directory found, keeping "nothing there" distinct from "cannot tell".
 *
 * `File.listFiles()` returns `null` when the directory does not exist or cannot be read, and the
 * scan used to coalesce that to an empty list with `?: emptyList()`. Every track then looked absent
 * and was marked uncached — so an unmounted SD card, a sync directory that had moved, or a volume
 * not yet mounted at launch silently wiped the cached status of a whole library while the files were
 * still on disk. That is the owner's "book reports no cache even if I'm sure I have downloaded it",
 * including the "long time after I downloaded it" variant (cu-85).
 *
 * The distinction is the fix: an [Unavailable] scan must change nothing. Only a directory that is
 * genuinely readable and genuinely empty may un-cache anything.
 */
sealed interface CacheScanOutcome {
  /** The directory was read. [files] may legitimately be empty. */
  data class Scanned(val files: List<File>) : CacheScanOutcome

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
 * Checks [File.isDirectory] as well as the `listFiles` result: a path that exists as a *file*, or
 * does not exist at all, both yield null from `listFiles` but deserve to be reported as unavailable
 * rather than empty.
 */
fun scanCachedMediaDir(
  dir: File,
  filter: FileFilter,
): CacheScanOutcome {
  if (!dir.exists()) {
    return CacheScanOutcome.Unavailable("sync directory does not exist: $dir")
  }
  if (!dir.isDirectory) {
    return CacheScanOutcome.Unavailable("sync directory is not a directory: $dir")
  }
  val listed =
    dir.listFiles(filter)
      ?: return CacheScanOutcome.Unavailable("sync directory is not readable: $dir")
  return CacheScanOutcome.Scanned(listed.toList())
}
