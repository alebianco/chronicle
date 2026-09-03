package io.github.mattpvaughn.chronicle.features.player

import android.app.Notification
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
import android.support.v4.media.session.PlaybackStateCompat.STATE_CONNECTING
import android.support.v4.media.session.PlaybackStateCompat.STATE_ERROR
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
import android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
import android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What each playback state does to the notification, the foreground service and the
 * becoming-noisy receiver — the state machine in `OnMediaChangedCallback.updateNotification`.
 *
 * Written to unblock DRAFT-72. That task was deferred with the reasoning "converting untested
 * playback code has no safety net — a wrong scope leaks or drops work rather than failing to
 * compile, and nothing would catch it." This is that safety net: it pins the *observable*
 * consequences of each state so a dispatcher change that reorders or drops the work fails here.
 *
 * The class is easier to test than its 11 constructor parameters suggest. Every collaborator is an
 * interface or a support-library type mockk can stand in for, and `updateNotification` touches none
 * of the Android framework directly — the notification is built by an injected builder.
 *
 * Runs on Robolectric, not plain JVM: `MediaControllerCompat.Callback`'s constructor builds an
 * `IMediaControllerCallback.Stub`, which calls `android.os.Binder.attachInterface` — unmocked in the
 * unit-test android.jar. Nothing about the state machine needs a device; it is the superclass
 * constructor that does. That also means this class must be listed in `excludedTestClasses` for
 * PIT, which reports false SURVIVED for Robolectric tests (cu-57).
 *
 * These are deliberately *behavioural* assertions, not a transcription of the `when`. The subtle
 * ones are the pairs: PAUSED both notifies **and** calls `stopForegroundService(false)`, which is
 * what makes a paused notification swipe-dismissable; STOPPED cancels the notification and stops
 * the service outright. Getting either half wrong leaves a stuck notification or an
 * undismissable one, and neither shows up as a crash.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationStateMachineTest {
  // A real Notification, not a mock: mocking framework classes under Robolectric trips mockk's
  // inline instrumentation ("class redefinition failed: attempted to change the class modifiers").
  private val notification: Notification =
    NotificationCompat.Builder(
      ApplicationProvider.getApplicationContext(),
      "test-channel",
    ).build()

  private val notificationBuilder = mockk<NotificationBuilder>(relaxed = true)
  private val notificationManager = mockk<NotificationManagerCompat>(relaxed = true)

  @Before
  fun stubNotificationBuilder() {
    // Both halves of the cu-137 split return the same instance, so these assertions stay about
    // the state machine rather than about which build ran. The deadline property itself —
    // that the foreground path never awaits the artwork — is pinned in ForegroundDeadlineTest.
    every { notificationBuilder.buildNotificationWithoutArtwork(any()) } returns notification
    coEvery { notificationBuilder.buildNotification(any()) } returns notification
  }

  private val becomingNoisyReceiver = mockk<BecomingNoisyReceiver>(relaxed = true)
  private val foregroundServiceController = mockk<ForegroundServiceController>(relaxed = true)
  private val serviceController = mockk<ServiceController>(relaxed = true)

  /**
   * Drives `updateNotification` for [state] and returns the collaborators to assert on.
   *
   * Reaches the private method through the public `onChapterChange` entry point rather than by
   * reflection: a test that pokes at internals stops proving anything the moment the class is
   * refactored, and this path is the one a chapter boundary actually takes.
   */
  private fun onState(state: Int) {
    val callback = callbackWithPlaybackState(state)
    callback.onChapterChange(Chapter())
  }

  @Test
  fun `playing registers the noisy receiver and goes foreground`() {
    onState(STATE_PLAYING)

    verify { becomingNoisyReceiver.register() }
    verify { notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification) }
    verify { foregroundServiceController.startForeground(NOW_PLAYING_NOTIFICATION, notification) }
  }

  /**
   * Buffering behaves exactly as playing. It has to: audio is about to resume, and dropping the
   * foreground notification here is what lets the system kill the service mid-buffer.
   */
  @Test
  fun `buffering is treated as playing`() {
    onState(STATE_BUFFERING)

    verify { becomingNoisyReceiver.register() }
    verify { foregroundServiceController.startForeground(NOW_PLAYING_NOTIFICATION, notification) }
  }

  /**
   * The pair that makes a paused notification dismissable: it still posts the notification *and*
   * releases the foreground state without removing it. Losing the second call leaves a
   * notification the user cannot swipe away.
   */
  @Test
  fun `pausing keeps the notification but releases the foreground state`() {
    onState(STATE_PAUSED)

    verify { becomingNoisyReceiver.unregister() }
    verify { notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification) }
    verify { foregroundServiceController.stopForegroundService(false) }
  }

  /**
   * The cu-137 second phase: the artwork build runs *after* the state machine, and re-posts
   * through `notify` only.
   *
   * It must not call `startForeground` again — PAUSED deliberately releases the foreground state
   * to stay swipe-dismissable, and re-promoting would undo that. So a paused notification gets
   * exactly one `startForeground` (from the state machine) and two `notify` calls: the immediate
   * one and the one carrying art.
   */
  @Test
  fun `the artwork phase updates the notification without re-promoting the service`() {
    onState(STATE_PAUSED)

    verify(exactly = 2) { notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification) }
    verify(exactly = 1) {
      foregroundServiceController.startForeground(NOW_PLAYING_NOTIFICATION, notification)
    }
    verify { foregroundServiceController.stopForegroundService(false) }
  }

  /** A stopped notification was just cancelled, so nothing should re-post art over it. */
  @Test
  fun `the artwork phase does not resurrect a cancelled notification`() {
    onState(STATE_STOPPED)

    verify(exactly = 0) { notificationManager.notify(any(), any<Notification>()) }
  }

  /** Stopping tears everything down — notification cancelled, service stopped. */
  @Test
  fun `stopping cancels the notification and stops the service`() {
    onState(STATE_STOPPED)

    verify { notificationManager.cancel(NOW_PLAYING_NOTIFICATION) }
    verify { foregroundServiceController.stopForegroundService(true) }
    verify { serviceController.stopService() }
  }

  /**
   * A stopped session must not leave a posted notification behind. Asserting the *absence* here
   * because the failure mode is a notification for a book that is no longer playing.
   */
  @Test
  fun `stopping posts no notification`() {
    onState(STATE_STOPPED)

    verify(exactly = 0) { notificationManager.notify(any(), any<Notification>()) }
  }

  @Test
  fun `an error state releases the foreground service and stops listening`() {
    onState(STATE_ERROR)

    verify { becomingNoisyReceiver.unregister() }
    verify { foregroundServiceController.stopForegroundService(true) }
  }

  @Test
  fun `idle and connecting states also release the foreground service`() {
    listOf(STATE_NONE, STATE_CONNECTING).forEach { state ->
      val noisy = mockk<BecomingNoisyReceiver>(relaxed = true)
      val foreground = mockk<ForegroundServiceController>(relaxed = true)
      callbackWithPlaybackState(state, noisy = noisy, foreground = foreground)
        .onChapterChange(Chapter())

      verify { noisy.unregister() }
      verify { foreground.stopForegroundService(true) }
    }
  }

  /*
   * Deliberately not tested here: the "no session token, so no notification" branch.
   *
   * It keys off `mediaController.sessionToken`, and a real `MediaControllerCompat` built from a
   * live session always has one — releasing the session afterwards does not null it. Reaching that
   * branch means mocking `MediaControllerCompat`, which is what collides with Robolectric's
   * instrumentation (see the class note). Faking it with a stub controller would test the stub.
   *
   * The branch is one `if` guarding a null notification, and the service-lifecycle half it shares
   * with STATE_STOPPED *is* covered above. Left for the instrumented suite, where a real released
   * session is reachable.
   */

  private fun callbackWithPlaybackState(
    state: Int,
    noisy: BecomingNoisyReceiver = becomingNoisyReceiver,
    foreground: ForegroundServiceController = foregroundServiceController,
    service: ServiceController = serviceController,
  ): OnMediaChangedCallback {
    // Built for real rather than mocked. `MediaControllerCompat` and `PlaybackStateCompat` are
    // final support-library classes, and mocking them under Robolectric fails with
    // "class redefinition failed: attempted to change the class modifiers" — mockk's inline
    // instrumentation and Robolectric's instrumentation collide. Robolectric can construct a real
    // session, which is simpler and closer to production anyway.
    val session =
      MediaSessionCompat(ApplicationProvider.getApplicationContext(), "NotificationStateMachineTest")
    session.setPlaybackState(
      PlaybackStateCompat.Builder().setState(state, 0L, 1f).build(),
    )
    session.isActive = true
    val controller =
      MediaControllerCompat(ApplicationProvider.getApplicationContext(), session)

    val callback =
      OnMediaChangedCallback(
        mediaController = controller,
        serviceScope = CoroutineScope(Dispatchers.Unconfined),
        notificationBuilder = notificationBuilder,
        mediaSession = session,
        becomingNoisyReceiver = noisy,
        notificationManager = notificationManager,
        foregroundServiceController = foreground,
        serviceController = service,
        // `onChapterChange` logs `currentlyPlaying.chapter.value`, and a relaxed mock hands back a
        // bare Object for the generic StateFlow, which fails to cast. Stub it with a real one.
        currentlyPlaying =
          mockk(relaxed = true) {
            every { chapter } returns MutableStateFlow(Chapter())
          },
        trackRepo = mockk(relaxed = true),
        bookRepo = mockk(relaxed = true),
        dispatchers = TestDispatcherProvider(),
      )
    return callback
  }
}
