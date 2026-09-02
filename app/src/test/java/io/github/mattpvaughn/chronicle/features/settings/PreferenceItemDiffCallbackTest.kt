package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings list's `DiffUtil` comparison.
 *
 * Found by cu-77's device test: after importing a backup, the switches kept their old state on
 * screen even though the preferences had been written. `areContentsTheSame` compared only title
 * and explanation, so a row whose *value* changed was considered unchanged and never rebound.
 *
 * It went unnoticed for so long because the two ordinary ways a value changes both repaint
 * anyway — a tapped switch is set by its own click handler, and a clickable row renders its value
 * into the title, which was compared. Import is the first path that changes several values at once
 * without touching their views.
 */
class PreferenceItemDiffCallbackTest {
  private val diff = SettingsList.PreferenceItemDiffCallback()

  private fun switchRow(
    key: String,
    value: Boolean,
  ) = PreferenceModel(
    type = PreferenceType.BOOLEAN,
    title = FormattableString.LiteralString("Offline Mode"),
    key = key,
    defaultValue = value,
  )

  @Test
  fun `a switch whose value changed is not considered unchanged`() {
    val before = switchRow("key_offline_mode", false)
    val after = switchRow("key_offline_mode", true)

    // Still the same row...
    assertTrue(diff.areItemsTheSame(before, after))
    // ...but its contents differ, so the adapter must rebind it.
    assertFalse(
      "a value change must force a rebind, or an imported setting shows its old state",
      diff.areContentsTheSame(before, after),
    )
  }

  @Test
  fun `an unchanged switch is left alone`() {
    val before = switchRow("key_offline_mode", true)
    val after = switchRow("key_offline_mode", true)

    assertTrue(diff.areContentsTheSame(before, after))
  }

  @Test
  fun `a clickable row whose value moved into its title still differs`() {
    // The case that always worked: the value is part of the title.
    val before =
      PreferenceModel(
        type = PreferenceType.CLICKABLE,
        title = FormattableString.LiteralString("Refresh frequency: 1 hours"),
      )
    val after =
      PreferenceModel(
        type = PreferenceType.CLICKABLE,
        title = FormattableString.LiteralString("Refresh frequency: 6 hours"),
      )

    assertFalse(diff.areContentsTheSame(before, after))
  }

  @Test
  fun `a changed explanation still forces a rebind`() {
    val before =
      PreferenceModel(
        type = PreferenceType.CLICKABLE,
        title = FormattableString.LiteralString("Sync location"),
        explanation = FormattableString.LiteralString("12 GB free"),
      )
    val after = before.copy(explanation = FormattableString.LiteralString("4 GB free"))

    assertFalse(diff.areContentsTheSame(before, after))
  }
}
