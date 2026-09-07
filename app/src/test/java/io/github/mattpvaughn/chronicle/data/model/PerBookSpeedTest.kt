package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingSingleton
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.views.SpeedChooserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The per-book playback-speed override.
 *
 * The interesting behaviour is not "a field stores a float" but the two rules around it: the
 * sentinel that distinguishes "no override" from a speed, and the fact that a *local-only* field
 * must survive a merge with a network copy that cannot carry it.
 */
class PerBookSpeedTest {
  private fun book(speed: Float = Audiobook.NO_SPEED_OVERRIDE) =
    Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune", playbackSpeed = speed)

  @Test
  fun `a book with no override follows the global speed`() {
    assertEquals(1.5f, book().effectiveSpeed(globalSpeed = 1.5f), 0f)
    assertFalse(book().hasSpeedOverride)
  }

  @Test
  fun `a book with an override ignores the global speed`() {
    val overridden = book(speed = 2.0f)
    assertTrue(overridden.hasSpeedOverride)
    assertEquals(2.0f, overridden.effectiveSpeed(globalSpeed = 1.0f), 0f)
  }

  @Test
  fun `a new book defaults to no override`() {
    assertEquals(Audiobook.NO_SPEED_OVERRIDE, Audiobook(id = "1", source = TEST_SOURCE).playbackSpeed, 0f)
  }

  /**
   * The sentinel only works while it stays outside the range the UI can produce. If
   * [Audiobook.MIN_VALID_SPEED] ever drifted below the slider's floor, a legitimately chosen speed
   * would read as "no override" and the book would silently revert to the global value.
   */
  @Test
  fun `the override threshold matches the slider's minimum`() {
    assertEquals(
      "MIN_VALID_SPEED must equal the lowest speed the UI can set",
      CurrentlyPlayingViewModel.PLAYBACK_SPEED_MIN,
      Audiobook.MIN_VALID_SPEED,
      0f,
    )
    assertEquals(
      "the popover's range must match the ViewModel's",
      CurrentlyPlayingViewModel.PLAYBACK_SPEED_MIN,
      SpeedChooserState.SPEED_MIN,
      0f,
    )
    assertEquals(
      "the popover's range must match the ViewModel's",
      CurrentlyPlayingViewModel.PLAYBACK_SPEED_MAX,
      SpeedChooserState.SPEED_MAX,
      0f,
    )
    assertTrue(
      "the sentinel must sit below any real speed",
      Audiobook.NO_SPEED_OVERRIDE < Audiobook.MIN_VALID_SPEED,
    )
  }

  @Test
  fun `the slowest selectable speed counts as an override`() {
    assertTrue(book(speed = CurrentlyPlayingViewModel.PLAYBACK_SPEED_MIN).hasSpeedOverride)
  }

  /**
   * The slider's step grid is derived from [SpeedChooserState.SPEED_STEP], not restated.
   *
   * This read `android:stepSize` out of `modal_bottom_sheet_speed_chooser.xml` until the Compose
   * migration deleted that layout. The hazard it guarded was specific to the View widget — `Slider.setValue`
   * **throws** for a value off its grid — and a Compose `Slider` clamps instead, so the crash is
   * gone. What is still worth pinning is that the popover's `steps` count and the constant
   * `snapToStep` rounds with describe the *same* grid: if they drift, a preset chip lands between
   * two steps and the thumb visibly jumps away from where the user tapped.
   *
   * `steps` is the count *between* the ends, hence the -1 — the same arithmetic
   * `SpeedChooserSheet` performs.
   */
  @Test
  fun `the slider's step count matches the snap step`() {
    val span = SpeedChooserState.SPEED_MAX - SpeedChooserState.SPEED_MIN
    val steps = (span / SpeedChooserState.SPEED_STEP).roundToInt() - 1

    // 0.5..3.0 in 0.05 steps is 50 intervals, so 49 stops between the ends.
    assertEquals("the speed range must divide evenly into SPEED_STEP", 49, steps)
    assertEquals(
      "a value one step above the minimum must itself be on the grid",
      SpeedChooserState.SPEED_MIN + SpeedChooserState.SPEED_STEP,
      SpeedChooserState.snapToStep(SpeedChooserState.SPEED_MIN + SpeedChooserState.SPEED_STEP),
      0.0001f,
    )
  }

  /**
   * A library refresh merges a network copy *without* loading tracks. `playbackSpeed` exists only
   * locally, so the network copy always carries the default — adopting it would wipe the user's
   * per-book speed on every refresh. Both branches of [Audiobook.Companion.merge] are exercised
   * because only one of them is taken for a given pair, and an earlier field (`progress`) was
   * fixed in one arm and missed in the other.
   */
  @Test
  fun `merge keeps a local override when the network copy is newer`() {
    val local = book(speed = 1.8f).copy(lastViewedAt = 1_000L)
    val network = book().copy(lastViewedAt = 2_000L, title = "Dune (remaster)")

    val merged = Audiobook.merge(network = network, local = local)

    assertEquals("the newer network metadata must win", "Dune (remaster)", merged.title)
    assertEquals("but the local speed override must survive", 1.8f, merged.playbackSpeed, 0f)
  }

  @Test
  fun `merge keeps a local override when the local copy is newer`() {
    val local = book(speed = 1.8f).copy(lastViewedAt = 2_000L)
    val network = book().copy(lastViewedAt = 1_000L)

    val merged = Audiobook.merge(network = network, local = local)

    assertEquals(1.8f, merged.playbackSpeed, 0f)
  }

  /**
   * `updateSpeedOverride` exists so a change made **while paused** reaches the player: the only
   * other republisher, `ProgressUpdater`, ticks solely while playing.
   */
  @Test
  fun `updateSpeedOverride republishes the loaded book`() {
    val currentlyPlaying = CurrentlyPlayingSingleton()
    val tracks = listOf(MediaItemTrack(id = "2001", parentKey = "1001", duration = 60_000L))
    currentlyPlaying.update(track = tracks.first(), book = book(), tracks = tracks)

    currentlyPlaying.updateSpeedOverride(bookId = "1001", speed = 1.7f)

    assertEquals(1.7f, currentlyPlaying.book.value.playbackSpeed, 0f)
    assertTrue(currentlyPlaying.book.value.hasSpeedOverride)
  }

  /**
   * A popover left open across a book change must not write its speed onto the new book — the
   * override is per book, so applying it to the wrong one is silently wrong rather than visibly so.
   */
  @Test
  fun `updateSpeedOverride ignores a book that is not loaded`() {
    val currentlyPlaying = CurrentlyPlayingSingleton()
    val tracks = listOf(MediaItemTrack(id = "2001", parentKey = "1001", duration = 60_000L))
    currentlyPlaying.update(track = tracks.first(), book = book(), tracks = tracks)

    currentlyPlaying.updateSpeedOverride(bookId = "9999", speed = 1.7f)

    assertEquals(
      "a stale popover must not override the speed of a different book",
      Audiobook.NO_SPEED_OVERRIDE,
      currentlyPlaying.book.value.playbackSpeed,
      0f,
    )
  }

  @Test
  fun `merge keeps a cleared override cleared`() {
    val local = book(speed = Audiobook.NO_SPEED_OVERRIDE).copy(lastViewedAt = 1_000L)
    val network = book(speed = 2.5f).copy(lastViewedAt = 2_000L)

    val merged = Audiobook.merge(network = network, local = local)

    assertEquals(
      "the network cannot reintroduce an override the user turned off",
      Audiobook.NO_SPEED_OVERRIDE,
      merged.playbackSpeed,
      0f,
    )
  }
}
