package io.github.mattpvaughn.chronicle.views

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID

/**
 * What the speed popover should show, and where a chosen speed should be written.
 *
 * Extracted from [ModalBottomSheetSpeedChooser] so the decisions are testable without a Fragment,
 * a Dagger graph or a `Slider`. The popover has three interacting inputs — the global preference,
 * this book's override, and whether a book is loaded at all — and the interesting behaviour is
 * entirely in how they combine.
 */
data class SpeedChooserState(
  /** The speed to display, already snapped to the slider's step grid. */
  val speed: Float,
  /** Whether the per-book switch is on. */
  val isOverrideEnabled: Boolean,
  /** Whether the per-book switch can be used at all — false when nothing is playing. */
  val canOverride: Boolean,
) {
  companion object {
    /**
     * The lowest speed the slider offers. Duplicated from `CurrentlyPlayingViewModel` rather than
     * imported so this file stays free of the feature package; `PerBookSpeedTest` pins them equal.
     */
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3.0f

    /** Must match `android:stepSize` on `speed_slider`; `PerBookSpeedTest` pins the two. */
    const val SPEED_STEP = 0.05f

    /**
     * Builds the state the popover renders.
     *
     * A book with no id — nothing playing — cannot hold an override, so the switch is disabled and
     * the global preference is shown.
     */
    fun of(
      book: Audiobook,
      globalSpeed: Float,
    ): SpeedChooserState {
      val canOverride = book.id.isNotEmpty() && book.id != NO_AUDIOBOOK_FOUND_ID
      val isOverrideEnabled = canOverride && book.hasSpeedOverride
      val effective = if (isOverrideEnabled) book.playbackSpeed else globalSpeed
      return SpeedChooserState(
        speed = snapToStep(effective),
        isOverrideEnabled = isOverrideEnabled,
        canOverride = canOverride,
      )
    }

    /**
     * Rounds [speed] onto the slider's step grid and into its range.
     *
     * `Slider.setValue` **throws** for a value that is not a multiple of `stepSize` away from
     * `valueFrom`. Every speed this popover writes is already on a step, but the global preference
     * is also reachable through a settings import, where the allowlist gates the key and not the
     * value (cu-77).
     */
    fun snapToStep(speed: Float): Float {
      val steps = Math.round((speed - SPEED_MIN) / SPEED_STEP)
      return (SPEED_MIN + steps * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
    }

    /**
     * Where a newly chosen [speed] belongs.
     *
     * Reads the *current* override state rather than taking a boolean, so a caller cannot pass a
     * stale one.
     */
    fun destinationFor(book: Audiobook): SpeedDestination =
      if (book.hasSpeedOverride) SpeedDestination.THIS_BOOK else SpeedDestination.GLOBAL

    /**
     * The speed to store when the override switch is toggled.
     *
     * Turning it **on** adopts the speed already showing, so the switch alone never changes how
     * the book sounds; turning it **off** clears the row so the book follows the global preference
     * again.
     */
    fun speedForToggle(
      isChecked: Boolean,
      shownSpeed: Float,
    ): Float = if (isChecked) shownSpeed else Audiobook.NO_SPEED_OVERRIDE
  }
}

/** Which of the two homes a chosen speed is written to. */
enum class SpeedDestination {
  GLOBAL,
  THIS_BOOK,
}
