package io.github.mattpvaughn.chronicle.features.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import androidx.work.NetworkType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity.Companion.FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID
import io.github.mattpvaughn.chronicle.application.MainActivity.Companion.REQUEST_CODE_PREFIX_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/**
 * Renders download progress and completion notifications.
 *
 * **Observes rather than polls.** It used to loop every 500 ms asking Fetch2's queue what was
 * happening — the engine owned the state, so the only way to render it was to keep asking. The
 * [Downloader] seam publishes [DownloadEvent]s instead, so this worker accumulates them and
 * renders on change. That removes the poll, the 10-second "wait for downloads to start" guess, and
 * the `suspendCancellableCoroutine` bridge that turned Fetch2's callback API into something
 * awaitable.
 *
 * The file's own long-standing TODO — *"write extension functions to turn fetch calls into suspend
 * functions"* — is resolved by there being no callbacks left to wrap.
 */
@HiltWorker
class DownloadNotificationWorker
  @AssistedInject
  constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val downloader: Downloader,
  ) : CoroutineWorker(context, parameters) {
    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    private val cancelAllDesc =
      applicationContext.getString(R.string.download_notification_cancel_all)
    private val cancelAllIntent = Intent(ACTION_CANCEL_ALL_DOWNLOADS)
    private val cancelAllPendingIntent =
      PendingIntent.getBroadcast(
        applicationContext,
        ACTION_CANCEL_ALL_DOWNLOADS_ID,
        cancelAllIntent,
        PendingIntent.FLAG_IMMUTABLE,
      )
    private val actionCancelAll =
      NotificationCompat.Action.Builder(
        R.drawable.ic_broken_image,
//            R.drawable.fetch_notification_cancel,
        cancelAllDesc,
        cancelAllPendingIntent,
      ).build()

    /** How long to keep rendering after the last event before deciding the batch is done. */
    private val quietPeriodMs = 3_000L

    /**
     * Renders until downloads go quiet, then reports what happened.
     *
     * The end condition is the interesting part. Polling could ask "is anything active?"; an event
     * stream cannot be asked, only listened to. So the worker tracks what is in flight and stops
     * when nothing is — with a [quietPeriodMs] grace window, because `enqueue` returns before the
     * first byte arrives and a worker that exited on "nothing in flight yet" would miss the whole
     * batch. That window replaces a 10-second fixed wait, so the common case is also faster.
     */
    override suspend fun doWork() =
      withContext(Dispatchers.IO) {
        createNotificationChannelAsNeeded()

        val inFlight = mutableMapOf<String, TrackProgress>()
        val finished = mutableListOf<TrackDownloadResult>()

        // A timeout around the collection rather than a poll: each event resets the clock, so the
        // worker lives exactly as long as downloads are talking to it.
        while (true) {
          val event =
            withTimeoutOrNull(quietPeriodMs) {
              downloader.events.first()
            } ?: break

          when (event) {
            is DownloadEvent.Progress -> {
              inFlight[event.trackId] =
                TrackProgress(
                  bookId = event.bookId,
                  bookTitle = event.bookTitle,
                  percent =
                    event.totalBytes
                      ?.takeIf { it > 0L }
                      ?.let { total -> ((event.bytesDownloaded * 100) / total).toInt() }
                      ?: INDETERMINATE,
                )
              updateNotifications(inFlight)
            }

            is DownloadEvent.Completed -> {
              inFlight.remove(event.trackId)
              finished += result(event, TrackDownloadResult.TrackStatus.Completed)
              updateNotifications(inFlight)
            }

            is DownloadEvent.Failed -> {
              inFlight.remove(event.trackId)
              finished += result(event, TrackDownloadResult.TrackStatus.Failed, event.cause)
              updateNotifications(inFlight)
            }

            is DownloadEvent.Cancelled -> {
              inFlight.remove(event.trackId)
              finished += result(event, TrackDownloadResult.TrackStatus.Cancelled)
              updateNotifications(inFlight)
            }
          }
        }

        notificationManager.cancelAll()

        // Rendered before returning, not launched. This used to be
        // `fetch.getDownloads { CoroutineScope(coroutineContext).launch { … } }` followed
        // immediately by the return — two independent bugs: the callback had not necessarily
        // fired, and `CoroutineWorker` cancels its context the moment `doWork` returns, so the
        // launched block raced its own teardown. Collecting synchronously above removes both.
        showDownloadsCompleteNotification(finished)

        return@withContext Result.success()
      }

    /** A track's share of its book's progress bar. */
    private data class TrackProgress(
      val bookId: String,
      val bookTitle: String,
      val percent: Int,
    )

    /** A finished track, taking the title straight off the event that reported it. */
    private fun result(
      event: DownloadEvent,
      status: TrackDownloadResult.TrackStatus,
      error: String? = null,
    ) = TrackDownloadResult(
      trackId = event.trackId,
      bookId = event.bookId,
      bookTitle = event.bookTitle,
      status = status,
      error = error,
    )

    /**
     * Show notifications for completed/failed downloads, allowing the user to retry failed
     * downloads if they wish to
     */
    private fun showDownloadsCompleteNotification(results: List<TrackDownloadResult>) {
      // The decision — which books to report and as what — is pure and lives in
      // `DownloadOutcomes.kt`, so it can be tested without a worker or a NotificationManager.
      // This function keeps only the rendering.
      val bookStatuses = results.toOutcomes()
      Timber.i("Finished downloads: ${results.size} track(s), ${bookStatuses.size} book(s) to report")

      if (bookStatuses.isEmpty()) {
        return
      }

      val showInGroup = bookStatuses.size > 1
      bookStatuses.forEach { result ->
        val notification = makeFinishedNotification(result, showInGroup)
        if (notification != null) {
          notificationManager.notify(result.bookName.hashCode(), notification)
        }
      }
      if (showInGroup) {
        val summary = makeFinishedSummary(bookStatuses)
        if (summary != null) {
          notificationManager.notify(DOWNLOADS_FINISHED_NOTIF_SUMMARY_ID, summary)
        }
      }
      // No `removeGroup` equivalent: Fetch2 kept a queue that had to be emptied or the next run
      // would re-report the same finished downloads. There is no engine-side queue now — the
      // events are consumed as they arrive, so there is nothing to clean up.
    }

    /** Creates a notification channel if required by the given version of Android SDK */
    private fun createNotificationChannelAsNeeded() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationChannel =
          NotificationChannel(
            DOWNLOAD_CHANNEL,
            applicationContext.getString(R.string.download_notification_title),
            NotificationManager.IMPORTANCE_LOW,
          )
        notificationChannel.description =
          applicationContext.getString(R.string.download_channel_description)

        notificationManager.createNotificationChannel(notificationChannel)
      }
    }

    /** Make a group summary for all completed downloads */
    private fun makeFinishedSummary(bookStatuses: List<DownloadOutcome>): Notification? {
      val failCount = bookStatuses.count { it.status == BookDownloadStatus.Failed }
      val successCount = bookStatuses.count { it.status == BookDownloadStatus.Completed }
      if (failCount + successCount == 0) {
        // Don't make a notification for zero book statuses
        return null
      }

      // For one download, show name + status
      val res = applicationContext.resources
      val downloadFailed = bookStatuses.any { it.status == BookDownloadStatus.Failed }
      val finishedTitle =
        if (downloadFailed) {
          res.getString(R.string.download_failed_notification_title)
        } else {
          res.getString(R.string.download_successful_notification_title)
        }

      val finishedContent =
        when {
          bookStatuses.all { it.status == BookDownloadStatus.Failed } ->
            res.getQuantityString(
              R.plurals.downloads_failed_summary,
              failCount,
            )
          bookStatuses.all { it.status == BookDownloadStatus.Completed } ->
            res.getQuantityString(
              R.plurals.downloads_complete_summary,
              successCount,
            )
          else -> {
            applicationContext.getString(
              R.string.downloads_completed_summary_mixed,
              bookStatuses.count { it.status == BookDownloadStatus.Completed },
              bookStatuses.count { it.status == BookDownloadStatus.Failed },
            )
          }
        }

      val downloadSummaries =
        bookStatuses.map { (bookTitle, _, status) ->
          when (status) {
            BookDownloadStatus.Completed ->
              applicationContext.getString(
                R.string.download_successful_notification_content,
                bookTitle.take(30),
              )
            BookDownloadStatus.Failed ->
              applicationContext.getString(
                R.string.download_failed_notification_content,
                bookTitle.take(30),
              )
            else -> return null
          }
        }

      val finishedDownloadList =
        NotificationCompat.InboxStyle()
          .setBigContentTitle(finishedTitle)
      downloadSummaries.forEach { line -> finishedDownloadList.addLine(line) }

      val resultIcon =
        if (downloadFailed) {
          R.drawable.ic_cloud_download_failed
        } else {
          R.drawable.ic_cloud_done_white
        }

      return NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL)
        .setContentTitle(finishedTitle)
        // set content text to support devices running API level < 24
        .setContentText(finishedContent)
        .setSmallIcon(resultIcon)
        .setStyle(finishedDownloadList)
        .setOnlyAlertOnce(true)
        .setGroup(DOWNLOADS_FINISHED_NOTIF_GROUP)
        .setGroupSummary(true)
        .build()
    }

    /**
     * Makes a notification indicating that a book with [Audiobook] == [bookId] has finished
     * downloading
     */
    private fun makeFinishedNotification(
      downloadResult: DownloadOutcome,
      showInGroup: Boolean,
    ): Notification? {
      val status = downloadResult.status
      val bookName = downloadResult.bookName

      val title =
        applicationContext.getString(
          when (status) {
            BookDownloadStatus.Failed -> R.string.download_failed_notification_content
            BookDownloadStatus.Completed -> R.string.download_successful_notification_content
            else -> return null
          },
          bookName,
        )

      val content =
        if (downloadResult.errors.isEmpty()) {
          null
        } else {
          downloadResult.errors.joinToString { it }
        }
      val icon =
        when (status) {
          BookDownloadStatus.Failed -> R.drawable.ic_cloud_download_failed
          BookDownloadStatus.Completed -> R.drawable.ic_cloud_done_white
          else -> return null
        }

      val openBookPendingIntent = makeOpenBookPendingIntent(downloadResult.bookId)
      val builder =
        NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL)
          .setContentTitle(title)
          .setContentIntent(openBookPendingIntent)
          .setContentText(content)
          .setSmallIcon(icon)
          .setGroup(if (showInGroup) DOWNLOADS_FINISHED_NOTIF_GROUP else null)

      return builder.build()
    }

    private fun updateNotifications(inFlight: Map<String, TrackProgress>) {
      if (inFlight.isEmpty()) {
        notificationManager.cancelAll()
        return
      }

      val byBook = inFlight.values.groupBy { it.bookId }
      val bookNotifications =
        byBook.map { (bookId, tracks) ->
          // Averaged over the book's *in-flight* tracks, as before. A track reporting
          // INDETERMINATE (no Content-Length) contributes 0 rather than skewing the average
          // upward — a progress bar that overstates itself and then stalls is worse than one that
          // catches up.
          val avgCompletion =
            tracks.sumOf { min(100, max(0, it.percent.coerceAtLeast(0))) } / tracks.size

          bookId to
            createDownloadNotificationForBook(
              bookId = bookId,
              bookTitle = tracks.first().bookTitle,
              avgCompletion = avgCompletion,
              showInGroup = byBook.size > 1,
            )
        }
      val summaryNotification = makeActiveDownloadsSummary(byBook)
      showDownloadNotifications(bookNotifications, summaryNotification)
    }

    private fun showDownloadNotifications(
      downloadNotifications: List<Pair<String, Notification>>,
      summaryNotification: Notification,
    ) {
      val size = downloadNotifications.size
      when {
        size == 0 -> notificationManager.cancelAll()
        size == 1 ->
          showNotificationForeground(
            downloadNotifications[0].second,
            DOWNLOAD_NOTIF_SUMMARY_ID,
          )
        size >= 2 -> {
          showNotificationForeground(summaryNotification, DOWNLOAD_NOTIF_SUMMARY_ID)
          downloadNotifications.forEach { (bookId, notification) ->
            // A notification id must be an Int; downloadGroupId gives a stable one per book,
            // so a book's progress notification keeps replacing itself rather than stacking.
            notificationManager.notify(downloadGroupId(bookId), notification)
          }
        }
      }
    }

    /** Adds additional metadata about foreground service type if available */
    private fun showNotificationForeground(
      notification: Notification,
      notificationId: Int,
    ) {
      setForegroundAsync(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ForegroundInfo(notificationId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
          ForegroundInfo(notificationId, notification)
        },
      )
    }

    private fun makeActiveDownloadsSummary(bookGroups: Map<String, List<TrackProgress>>): Notification {
      // Show up to 5 downloads on legacy devices.
      //
      // Ordered by book title rather than by an engine timestamp. Fetch2's records carried
      // `created`, which this sorted by; an event stream has no equivalent, and inventing one
      // would mean timestamping arrivals just to order a list of at most five lines. A stable
      // alphabetical order is better than an arbitrary map order and does not pretend to be
      // chronological.
      val downloadsToShow =
        bookGroups.toList().sortedBy { (_, tracks) ->
          tracks.firstOrNull()?.bookTitle.orEmpty()
        }.take(5).mapNotNull { (_, tracks) ->
          val bookName = tracks.firstOrNull()?.bookTitle?.takeIf { it.isNotEmpty() }
          val progress = min(max(tracks.sumOf { it.percent.coerceAtLeast(0) } / tracks.size, 0), 100)
          if (bookName != null) {
            applicationContext.getString(
              R.string.download_starting,
              bookName.take(30),
              progress.toString(),
            )
          } else {
            null
          }
        }
      val downloadSummary =
        applicationContext.resources.getQuantityString(
          R.plurals.download_books_summary,
          bookGroups.size,
          bookGroups.size,
        )
      // build summary info into InboxStyle template
      val downloadsList =
        NotificationCompat.InboxStyle()
          .setBigContentTitle(downloadSummary)
      downloadsToShow.forEach { line -> downloadsList.addLine(line) }

      return NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL)
        .setContentTitle(downloadSummary)
        // set content text to support devices running API level < 24
        .setContentText(downloadsToShow.firstOrNull() ?: "")
        .setSmallIcon(R.drawable.ic_cloud_download_white)
        .setStyle(downloadsList)
        .setGroup(DOWNLOAD_NOTIF_GROUP)
        .addAction(actionCancelAll)
        .setGroupSummary(true)
        .build()
    }

    /** Creates a [Notification] for a book download */
    private fun createDownloadNotificationForBook(
      bookId: String,
      bookTitle: String,
      avgCompletion: Int,
      showInGroup: Boolean,
    ): Notification {
      val notificationTitle =
        applicationContext.getString(
          R.string.download_starting,
          bookTitle,
          avgCompletion.toString(),
        )

      val cancelPendingIntent =
        PendingIntent.getBroadcast(
          applicationContext,
          requestCodeFor(ACTION_CANCEL_BOOK_DOWNLOAD_ID, bookId),
          Intent(ACTION_CANCEL_BOOK_DOWNLOAD).apply {
            putExtra(KEY_BOOK_ID, bookId)
          },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val openBookPendingIntent = makeOpenBookPendingIntent(bookId)

      val cancel = applicationContext.getString(R.string.download_notification_cancel)
      return NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL)
        .setContentTitle(notificationTitle)
        .setContentIntent(openBookPendingIntent)
        .setProgress(100, avgCompletion, false)
        .setSmallIcon(R.drawable.ic_cloud_download_white)
        .setGroup(if (showInGroup) DOWNLOAD_NOTIF_GROUP else null)
        .setOngoing(true)
        .addAction(android.R.drawable.ic_delete, cancel, cancelPendingIntent)
        .build()
    }

    private fun makeOpenBookPendingIntent(bookId: String): PendingIntent? {
      val intent = Intent()
      val activity =
        applicationContext.packageManager.getPackageInfo(
          applicationContext.packageName,
          PackageManager.GET_ACTIVITIES,
        ).activities?.find { it.name.contains("MainActivity") }
      intent.setPackage(applicationContext.packageName)
      intent.putExtra(FLAG_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, bookId)
      intent.component = ComponentName(applicationContext.packageName, activity?.name ?: "")
      return PendingIntent.getActivity(
        applicationContext,
        requestCodeFor(REQUEST_CODE_PREFIX_OPEN_ACTIVITY_TO_AUDIOBOOK_WITH_ID, bookId),
        intent,
        PendingIntent.FLAG_IMMUTABLE,
      )
    }

    companion object {
      const val DOWNLOAD_WORKER_ID: String =
        "io.github.mattpvaughn.chronicle.features.download\$DOWNLOAD_WORKER_ID"

      /** Progress for a download whose total size the server did not declare. */
      const val INDETERMINATE = 0

      const val DOWNLOAD_CHANNEL: String =
        "io.github.mattpvaughn.chronicle.features.download\$DOWNLOAD_CHANNEL"
      const val KEY_BOOK_ID = "KEY_BOOK_ID"

      const val DOWNLOAD_NOTIF_GROUP =
        "io.github.mattpvaughn.chronicle.features.download\$DOWNLOAD_NOTIF_GROUP"
      const val DOWNLOAD_NOTIF_SUMMARY_ID = 999

      const val DOWNLOADS_FINISHED_NOTIF_GROUP =
        "io.github.mattpvaughn.chronicle.features.download\$DOWNLOADS_FINISHED_NOTIF_GROUP"
      const val DOWNLOADS_FINISHED_NOTIF_SUMMARY_ID = 1024

      const val ACTION_CANCEL_BOOK_DOWNLOAD =
        "io.github.mattpvaughn.chronicle.features.download\$ACTION_CANCEL_BOOK_DOWNLOAD"
      const val ACTION_CANCEL_BOOK_DOWNLOAD_ID = 79211

      const val ACTION_CANCEL_ALL_DOWNLOADS =
        "io.github.mattpvaughn.chronicle.features.download\$ACTION_CANCEL_ALL"
      const val ACTION_CANCEL_ALL_DOWNLOADS_ID = 9212

      /**
       * Start [DownloadNotificationWorker] if it is not already running.
       *
       * Both dependencies arrive as parameters. The [Context] came first, when this stopped
       * reaching `Injector.get().workManager()` — a companion function has no injection point, so
       * the caller, which *is* constructor-injected, passes what it already holds.
       *
       * The [WorkManager] followed for the same reason: `WorkManager.getInstance(context)` is a
       * static lookup, and it made every caller untestable in turn.
       * `CachedFileManagerResumeTest` could not construct a download at all without standing up
       * WorkManager, which is the "fetches its own dependency" problem convention 5 exists to
       * prevent — and the instance is already in the graph.
       */
      fun start(
        context: Context,
        workManager: WorkManager,
      ) {
        val syncWorkerConstraints =
          Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val worker =
          OneTimeWorkRequestBuilder<DownloadNotificationWorker>()
            .setConstraints(syncWorkerConstraints)
            .build()

        workManager.beginUniqueWork(
          DOWNLOAD_WORKER_ID,
          ExistingWorkPolicy.KEEP,
          worker,
        ).enqueue()
      }
    }
  }
