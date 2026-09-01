package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The refresh-rate mapping, both directions (cu-101).
 *
 * `SettingsViewModel.makePreferences()` held this twice: one `when` turned stored minutes into a
 * label, another turned a chosen option back into minutes. Neither was reachable by a test — both
 * resolved their strings through `Injector.get().applicationContext()` — and nothing checked that
 * they agreed.
 *
 * The round-trip case below is the one that could not be written at all before, and it is the whole
 * point of the extraction: an option the chooser offers but the formatter cannot describe renders
 * as the wrong thing, silently.
 */
class RefreshRateTest {
  /**
   * The property the two hand-written `when` chains never had: every value the chooser can set
   * must be one the formatter can describe.
   */
  @Test
  fun `every offered choice round-trips through the formatter`() {
    RefreshRate.choices.forEach { rate ->
      val label = refreshRateLabel(rate.minutes)
      when (label) {
        is RefreshRateLabel.Named ->
          assertEquals(
            "only Always and Manual have no number; ${rate.name} is not one of them",
            true,
            rate == RefreshRate.Always || rate == RefreshRate.Manual,
          )
        is RefreshRateLabel.Quantity ->
          assertEquals(
            "${rate.name} must describe a positive amount of time",
            true,
            label.count > 0,
          )
      }
    }
  }

  @Test
  fun `every choice resolves from its own resource`() {
    RefreshRate.choices.forEach { rate ->
      assertEquals(rate, RefreshRate.ofChoice(rate.choiceRes))
    }
  }

  @Test
  fun `an unrelated resource resolves to no choice`() {
    assertNull(RefreshRate.ofChoice(R.string.settings_category_sync))
  }

  @Test
  fun `minute values are unique`() {
    val minutes = RefreshRate.choices.map { it.minutes }
    assertEquals(
      "two options meaning the same interval would make the setting ambiguous",
      minutes.size,
      minutes.distinct().size,
    )
  }

  /** Zero means "sync whenever possible", not "every zero minutes". */
  @Test
  fun `zero is always`() {
    assertEquals(
      RefreshRateLabel.Named(R.string.settings_refresh_rate_always),
      refreshRateLabel(0L),
    )
  }

  @Test
  fun `under an hour is counted in minutes`() {
    assertEquals(RefreshRateLabel.Quantity(15L, R.string.minutes), refreshRateLabel(15L))
    assertEquals(RefreshRateLabel.Quantity(59L, R.string.minutes), refreshRateLabel(59L))
  }

  /**
   * The minute/hour boundary. 60 is *not* "60 minutes" — the original chain used `< 60`, so an
   * hour crosses into the hour branch, and a boundary mutant here would read "60 minutes".
   */
  @Test
  fun `exactly an hour is one hour`() {
    assertEquals(RefreshRateLabel.Quantity(1L, R.string.hours), refreshRateLabel(60L))
  }

  @Test
  fun `under a day is counted in hours`() {
    assertEquals(RefreshRateLabel.Quantity(3L, R.string.hours), refreshRateLabel(180L))
    assertEquals(RefreshRateLabel.Quantity(23L, R.string.hours), refreshRateLabel(60L * 23))
  }

  @Test
  fun `exactly a day is one day`() {
    assertEquals(RefreshRateLabel.Quantity(1L, R.string.days), refreshRateLabel(60L * 24))
  }

  /**
   * The week boundary is inclusive (`<= 60 * 24 * 7`), so a week is still a day count. One more
   * minute is manual. This is the pair a "changed conditional boundary" mutant flips.
   */
  @Test
  fun `exactly a week is seven days`() {
    assertEquals(RefreshRateLabel.Quantity(7L, R.string.days), refreshRateLabel(60L * 24 * 7))
  }

  @Test
  fun `beyond a week is manual`() {
    assertEquals(
      RefreshRateLabel.Named(R.string.settings_refresh_rate_manual),
      refreshRateLabel(60L * 24 * 7 + 1),
    )
  }

  /**
   * `Manual` stores `Long.MAX_VALUE`. The original formatter's chain ended at
   * `minutes > 60 * 24 * 7` with **no `else`**, so this depended on that final branch matching; any
   * value it failed to match threw out of the settings screen. There is an `else` now.
   */
  @Test
  fun `the manual sentinel is manual`() {
    assertEquals(
      RefreshRateLabel.Named(R.string.settings_refresh_rate_manual),
      refreshRateLabel(RefreshRate.Manual.minutes),
    )
  }

  /**
   * A stored negative can only come from corrupt prefs, but it must not fall through to a branch
   * that never matches. The original's first test was `== 0L`, so -1 reached the end of the chain
   * and threw.
   */
  @Test
  fun `a negative stored value does not throw`() {
    assertNotNull(refreshRateLabel(-1L))
    assertEquals(
      RefreshRateLabel.Named(R.string.settings_refresh_rate_always),
      refreshRateLabel(-1L),
    )
  }
}
