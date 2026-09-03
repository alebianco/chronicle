package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The jump-interval mapping (cu-101).
 *
 * The third and fourth `stringRes`-as-identity `when` blocks in `makePreferences()`: the jump
 * forward and jump backward choosers offered the same six options and each carried its **own copy**
 * of the resource→seconds mapping, 70 lines apart. Two copies of one fact, in a file with no tests.
 *
 * Unlike the cover style this held no live bug — both copies agreed. It is filed under the same
 * defect class because nothing made them agree: adding a seventh option means editing two lists and
 * two `when` chains, and missing one is silent.
 *
 * The differing `else` branches are **deliberate and preserved**: forward falls back to 30 s and
 * backward to 10 s, each matching that preference's own default in `SharedPreferencesPrefsRepo`.
 * They are asserted below so the asymmetry cannot be "tidied" into a single wrong constant.
 */
class JumpIntervalTest {
  @Test
  fun `every choice resolves from its own resource`() {
    JumpInterval.choices.forEach { interval ->
      assertEquals(interval, JumpInterval.ofChoice(interval.choiceRes))
    }
  }

  @Test
  fun `an unrelated resource resolves to no choice`() {
    assertNull(JumpInterval.ofChoice(R.string.settings_category_sync))
  }

  @Test
  fun `the offered options are the six the choosers listed`() {
    assertEquals(
      listOf(10L, 15L, 20L, 30L, 60L, 90L),
      JumpInterval.choices.map { it.seconds },
    )
  }

  @Test
  fun `second values are unique`() {
    val seconds = JumpInterval.choices.map { it.seconds }
    assertEquals(seconds.size, seconds.toSet().size)
  }

  /**
   * The two fallbacks differ on purpose — each is its own preference's default. The caller passes
   * the fallback rather than a `forward` flag, so the asymmetry stays visible at the call site
   * instead of hiding behind a boolean.
   */
  @Test
  fun `an unknown resource falls back to the value the caller supplies`() {
    assertEquals(
      JumpInterval.DEFAULT_FORWARD_SECONDS,
      JumpInterval.secondsOfChoice(
        R.string.settings_category_sync,
        orElse = JumpInterval.DEFAULT_FORWARD_SECONDS,
      ),
    )
    assertEquals(
      JumpInterval.DEFAULT_BACKWARD_SECONDS,
      JumpInterval.secondsOfChoice(
        R.string.settings_category_sync,
        orElse = JumpInterval.DEFAULT_BACKWARD_SECONDS,
      ),
    )
  }

  /** The two defaults are the ones the original `else` branches used. */
  @Test
  fun `the defaults match the preferences they back`() {
    assertEquals(30L, JumpInterval.DEFAULT_FORWARD_SECONDS)
    assertEquals(10L, JumpInterval.DEFAULT_BACKWARD_SECONDS)
  }

  @Test
  fun `a real choice ignores the fallback`() {
    assertEquals(
      90L,
      JumpInterval.secondsOfChoice(R.string.settings_jump_90_seconds, orElse = 1L),
    )
  }
}
