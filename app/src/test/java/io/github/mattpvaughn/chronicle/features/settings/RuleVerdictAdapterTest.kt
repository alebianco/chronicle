package io.github.mattpvaughn.chronicle.features.settings

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The verdict rows say what happened in **words**, and mark only one winner (cu-151).
 *
 * Two properties matter and neither is "the row shows the rule's name". First, more than one rule
 * routinely succeeds and only the first counts, so exactly one row may claim to have decided —
 * marking both leaves the user unable to tell which reading the app took. Second, the verdict is
 * carried by text rather than by colour or an icon, because a screen reader and a colour-blind
 * reader must get the same answer (cu-47).
 */
@RunWith(RobolectricTestRunner::class)
class RuleVerdictAdapterTest {
  /**
   * Themed, not the bare application context.
   *
   * The row layouts use `?attr/selectableItemBackground`, which Robolectric's default theme does
   * not define — inflation throws `InflateException` rather than falling back. `AppTheme` is what
   * the manifest applies, so this is also the theme the rows actually render under.
   */
  private val context =
    android.view.ContextThemeWrapper(
      ApplicationProvider.getApplicationContext(),
      io.github.mattpvaughn.chronicle.R.style.AppTheme,
    )
  private val parent = FrameLayout(context)

  private fun attempt(
    name: String,
    succeeded: Boolean,
    reason: String? = null,
    isUserDefined: Boolean = false,
  ) = PatternAttempt(
    patternName = name,
    matched = succeeded,
    capturedIndex = if (succeeded) "2" else null,
    rejectedReason = if (succeeded) null else (reason ?: "did not match"),
    isUserDefined = isUserDefined,
  )

  /** Binds every row and returns the verdict text each one displays. */
  private fun verdictsFor(attempts: List<PatternAttempt>): List<String> {
    val adapter = RuleVerdictAdapter()
    adapter.submitList(attempts)
    return attempts.indices.map { position ->
      val holder = adapter.onCreateViewHolder(parent, 0)
      adapter.onBindViewHolder(holder, position)
      holder.itemView.findViewById<android.widget.TextView>(
        io.github.mattpvaughn.chronicle.R.id.rule_verdict,
      ).text.toString()
    }
  }

  @Test
  fun `only the first successful rule is marked as the one that decided`() {
    val verdicts =
      verdictsFor(
        listOf(
          attempt("audnexus", succeeded = true),
          attempt("seanap", succeeded = true),
        ),
      )

    val decided = context.getString(io.github.mattpvaughn.chronicle.R.string.series_rules_verdict_won)
    assertEquals(
      "exactly one row may claim to have decided, however many rules succeeded",
      1,
      verdicts.count { it == decided },
    )
    assertEquals(decided, verdicts.first())
  }

  /**
   * A rule that matched but lost still says so, rather than looking like a failure.
   *
   * "My rule matched but something else won" and "my rule did not match" need different fixes —
   * reordering versus rewriting — so collapsing them would send the user down the wrong path.
   */
  @Test
  fun `a rule that succeeded but lost says an earlier rule decided`() {
    val verdicts =
      verdictsFor(
        listOf(
          attempt("audnexus", succeeded = true),
          attempt("seanap", succeeded = true),
        ),
      )

    assertEquals(
      context.getString(io.github.mattpvaughn.chronicle.R.string.series_rules_verdict_also_matched),
      verdicts[1],
    )
  }

  /** The model's own wording is shown, because it is written to be actionable. */
  @Test
  fun `a rule that could not compile shows the reason rather than a generic failure`() {
    val reason = "not a valid regular expression, so it is ignored"

    val verdicts = verdictsFor(listOf(attempt("broken", succeeded = false, reason = reason)))

    assertEquals(reason, verdicts.single())
  }

  /**
   * The whole row is one label for a screen reader, and it carries the verdict.
   *
   * Three separate views read as three disconnected fragments; and a verdict conveyed only by the
   * row's styling would not reach TalkBack at all.
   */
  @Test
  fun `the row is described as a whole, including its verdict`() {
    val adapter = RuleVerdictAdapter()
    adapter.submitList(listOf(attempt("mine", succeeded = true, isUserDefined = true)))
    val holder = adapter.onCreateViewHolder(parent, 0)

    adapter.onBindViewHolder(holder, 0)

    val description = holder.itemView.contentDescription.toString()
    assertTrue("the rule's name must be spoken: $description", description.contains("mine"))
    assertTrue(
      "a user's own rule must be identifiable by ear: $description",
      description.contains(
        context.getString(io.github.mattpvaughn.chronicle.R.string.series_rules_rule_origin_yours),
      ),
    )
    assertTrue(
      "the verdict must be spoken, not only shown: $description",
      description.contains(
        context.getString(io.github.mattpvaughn.chronicle.R.string.series_rules_verdict_won),
      ),
    )
  }
}
