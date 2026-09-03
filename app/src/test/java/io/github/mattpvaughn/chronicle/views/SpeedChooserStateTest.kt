package io.github.mattpvaughn.chronicle.views

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed popover's decisions (cu-20).
 *
 * Three inputs interact — the global preference, this book's override, and whether a book is
 * loaded at all — and every combination has a different correct answer for what the controls show
 * and where a write goes. Extracted from the Fragment precisely so those combinations are
 * enumerable here.
 */
class SpeedChooserStateTest {
  private fun book(speed: Float = Audiobook.NO_SPEED_OVERRIDE) = Audiobook(id = "1001", source = 1L, title = "Dune", playbackSpeed = speed)

  @Test
  fun `a book with no override shows the global speed with the switch off`() {
    val state = SpeedChooserState.of(book(), globalSpeed = 1.5f)

    assertEquals(1.5f, state.speed, 0f)
    assertFalse(state.isOverrideEnabled)
    assertTrue("a real book can always take an override", state.canOverride)
  }

  @Test
  fun `a book with an override shows its own speed with the switch on`() {
    val state = SpeedChooserState.of(book(speed = 2.0f), globalSpeed = 1.0f)

    assertEquals(2.0f, state.speed, 0f)
    assertTrue(state.isOverrideEnabled)
  }

  /**
   * With nothing playing there is no row to store an override on, so the switch must be disabled
   * rather than silently dropping the write.
   */
  @Test
  fun `no book playing disables the override switch`() {
    val state = SpeedChooserState.of(EMPTY_AUDIOBOOK, globalSpeed = 1.2f)

    assertFalse(state.canOverride)
    assertFalse(state.isOverrideEnabled)
    assertEquals("the global speed is still shown", 1.2f, state.speed, 0f)
  }

  @Test
  fun `writes go to the global preference when there is no override`() {
    assertEquals(SpeedDestination.GLOBAL, SpeedChooserState.destinationFor(book()))
  }

  @Test
  fun `writes go to the book when it has an override`() {
    assertEquals(
      SpeedDestination.THIS_BOOK,
      SpeedChooserState.destinationFor(book(speed = 1.4f)),
    )
  }

  /**
   * Turning the switch on must not change how the book sounds — it adopts the speed already
   * showing. A version that defaulted to 1.0x would silently reset the speed on toggle.
   */
  @Test
  fun `enabling the override adopts the speed already shown`() {
    assertEquals(1.65f, SpeedChooserState.speedForToggle(isChecked = true, shownSpeed = 1.65f), 0f)
  }

  @Test
  fun `disabling the override clears the stored speed`() {
    assertEquals(
      Audiobook.NO_SPEED_OVERRIDE,
      SpeedChooserState.speedForToggle(isChecked = false, shownSpeed = 1.65f),
      0f,
    )
  }

  /**
   * `Slider.setValue` throws for a value off its step grid, and the global preference is reachable
   * through a settings import that validates keys but not values (cu-77).
   */
  @Test
  fun `an off-grid speed is snapped onto the step grid`() {
    assertEquals(1.65f, SpeedChooserState.snapToStep(1.6712f), 0.0001f)
    assertEquals(1.0f, SpeedChooserState.snapToStep(1.0f), 0.0001f)
  }

  @Test
  fun `a speed outside the range is clamped`() {
    assertEquals(SpeedChooserState.SPEED_MIN, SpeedChooserState.snapToStep(0.01f), 0.0001f)
    assertEquals(SpeedChooserState.SPEED_MAX, SpeedChooserState.snapToStep(99f), 0.0001f)
    assertEquals(SpeedChooserState.SPEED_MIN, SpeedChooserState.snapToStep(-5f), 0.0001f)
  }

  /**
   * Every snapped value must be a legal `Slider` input, i.e. an exact number of steps above
   * `valueFrom`. Checked across the range rather than at a couple of points, because float
   * accumulation is exactly where this would go wrong.
   */
  @Test
  fun `every snapped value lands on a legal slider step`() {
    var raw = SpeedChooserState.SPEED_MIN
    while (raw <= SpeedChooserState.SPEED_MAX) {
      val snapped = SpeedChooserState.snapToStep(raw)
      val steps = (snapped - SpeedChooserState.SPEED_MIN) / SpeedChooserState.SPEED_STEP
      assertEquals(
        "snapToStep($raw) = $snapped is not a whole number of steps above the minimum",
        Math.round(steps).toFloat(),
        steps,
        0.001f,
      )
      raw += 0.017f
    }
  }
}
