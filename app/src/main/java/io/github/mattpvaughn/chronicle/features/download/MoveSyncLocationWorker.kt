package io.github.mattpvaughn.chronicle.features.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import kotlinx.coroutines.*
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * Moves downloaded audio when the sync location changes.
 *
 * Dependencies arrive through the constructor via [ChronicleWorkerFactory]. They used to
 * be `Injector.get()` calls in field initialisers, which made this class unconstructable in a unit
 * test — `Injector.get()` is `ChronicleApplication.get()`, whose `INSTANCE!!` throws before the
 * constructor finishes.
 */
@HiltWorker
class MoveSyncLocationWorker
  @AssistedInject
  constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val prefsRepo: PrefsRepo,
    private val externalDeviceDirs: List<@JvmSuppressWildcards File>,
    private val fileSystem: FileSystem,
  ) : CoroutineWorker(context, parameters) {
    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    /** Moves all previously downloaded files to [PrefsRepo.cachedMediaDir] */
    override suspend fun doWork() =
      withContext(Dispatchers.IO) {
        // Create a notification channel if possible
        createChannel()

        val activeDownloadDir = prefsRepo.cachedMediaDir
        val inactiveSyncLocations =
          externalDeviceDirs.filter {
            it.path != activeDownloadDir.path
          }

        val fileMoveFailures =
          inactiveSyncLocations.flatMap { inactiveDir ->
            moveFilesBetweenDirectories(inactiveDir.toOkioPath(), activeDownloadDir.toOkioPath())
          }.filter { it.isFailure }

        notificationManager.cancelAll()

        if (fileMoveFailures.isNotEmpty()) {
          showFailureNotification(fileMoveFailures)
        }

        return@withContext Result.success()
      }

    private fun showFailureNotification(fileMoveFailures: List<kotlin.Result<Unit>>) {
      val notificationTitle =
        applicationContext.getString(
          R.string.moving_files_failed_title,
        )
      val errorReasons =
        fileMoveFailures.mapNotNull {
          it.exceptionOrNull()?.localizedMessage
        }.joinToString(separator = ", ")

      val notif =
        NotificationCompat.Builder(applicationContext, TRANSFER_CHANNEL)
          .setContentTitle(notificationTitle)
          .setContentText(errorReasons)
          .setSmallIcon(R.drawable.ic_sync)
          .build()

      notificationManager.notify(TRANSFER_ERROR_NOTIF_ID, notif)
    }

    private fun createChannel() {
      val notificationChannel =
        NotificationChannel(
          TRANSFER_CHANNEL,
          applicationContext.getString(R.string.moving_files_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
      notificationChannel.description =
        applicationContext.getString(R.string.download_channel_description)
      notificationManager.createNotificationChannel(notificationChannel)
    }

    private fun moveFilesBetweenDirectories(
      from: Path,
      to: Path,
    ): List<kotlin.Result<Unit>> {
      // Unavailable is not empty: if the source cannot be listed there is nothing to move, and
      // reporting zero successes is honest — quietly treating it as "done" would let the caller
      // record a move that never happened.
      val toMove =
        when (val outcome = scanCachedMediaDir(from, fileSystem) { MediaItemTrack.cachedFilePattern.matches(it.name) }) {
          is CacheScanOutcome.Unavailable -> {
            Timber.w("Cannot list the source directory, nothing moved: ${outcome.reason}")
            return emptyList()
          }
          is CacheScanOutcome.Scanned -> outcome.files
        }
      return toMove.mapIndexed { i, cachedFile ->
        showFileTransferNotification(i, toMove.size)
        val destFile = to / cachedFile.name
        Timber.i("Moving file $cachedFile to $destFile")
        try {
          moveFile(cachedFile, destFile)
          kotlin.Result.success(Unit)
        } catch (io: IOException) {
          Timber.i("Failed to move file: $io")
          kotlin.Result.failure<Unit>(io)
        }
      }
    }

    /**
     * Moves one cached file, falling back to copy-and-delete when a rename cannot cross the two
     * volumes.
     *
     * This is the tablet's real behaviour: internal storage and the SD card are different
     * filesystems, so a rename fails and the bytes have to be copied. Okio's [FileSystem.atomicMove]
     * throws in that case, and the `catch` is the fallback — the same two branches the previous
     * `Files.move` / `copyTo` pair expressed, minus the SDK check, since Okio provides both on
     * every level this app supports (minSdk 27).
     *
     * The delete only happens once the copy is on disk. Reversing that order is how a move loses a
     * user's download when the destination volume is full.
     */
    private fun moveFile(
      cachedFile: Path,
      destFile: Path,
    ) {
      try {
        fileSystem.atomicMove(cachedFile, destFile)
      } catch (e: IOException) {
        Timber.i("Rename across volumes failed for ${cachedFile.name}, copying instead: ${e.message}")
        fileSystem.copy(cachedFile, destFile)
        if (fileSystem.exists(destFile)) {
          fileSystem.delete(cachedFile)
        }
        Timber.i("Moved file ${cachedFile.name}? ${fileSystem.exists(destFile)}")
      }
    }

    /** * Creates a [Notification] for a book download */
    private fun showFileTransferNotification(
      filesTransferred: Int,
      totalFiles: Int,
    ) {
      val notificationTitle =
        applicationContext.getString(
          R.string.moving_files_title,
          filesTransferred,
          totalFiles,
        )

      val notif =
        NotificationCompat.Builder(applicationContext, TRANSFER_CHANNEL)
          .setContentTitle(notificationTitle)
          .setTicker(notificationTitle)
          .setProgress(100, (filesTransferred * 100f / totalFiles).roundToInt(), false)
          .setContentText(notificationTitle)
          .setSmallIcon(R.drawable.ic_sync)
          .setOngoing(true)
          .build()

      show(notif)
    }

    // Add additional metadata about foreground service type if available
    private fun show(notif: Notification) {
      setForegroundAsync(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ForegroundInfo(TRANSFER_NOTIF_ID, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
          ForegroundInfo(TRANSFER_NOTIF_ID, notif)
        },
      )
    }

    companion object {
      const val WORKER_ID: String =
        "io.github.mattpvaughn.chronicle.features.download\$WORKER_ID"
      const val TRANSFER_CHANNEL: String =
        "io.github.mattpvaughn.chronicle.features.download\$TRANSFER_CHANNEL"
      const val TRANSFER_NOTIF_ID = 1012
      const val TRANSFER_ERROR_NOTIF_ID = 1013
    }
  }
