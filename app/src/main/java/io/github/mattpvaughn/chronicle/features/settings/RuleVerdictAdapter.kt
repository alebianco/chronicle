package io.github.mattpvaughn.chronicle.features.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt
import io.github.mattpvaughn.chronicle.databinding.ListItemRuleVerdictBinding

/**
 * One row per rule, in the order the rules are actually tried (cu-151).
 *
 * **Every** rule is listed, including the ones that did not match and the ones that could not be
 * compiled at all — that is the whole point. A rule dropped at load time is otherwise invisible
 * except for a `Timber.w` line, which is exactly tvnamer's #216.
 */
class RuleVerdictAdapter :
  ListAdapter<PatternAttempt, RuleVerdictAdapter.ViewHolder>(DIFF) {
  /**
   * Whether this row is the one that decided, which the list position alone cannot say.
   *
   * More than one rule routinely succeeds — `"Mistborn, Book 2 - …"` satisfies both `audnexus` and
   * `seanap` — and first-match-wins is the disambiguation mechanism (cu-146). Marking both as
   * "matched" would leave the user unable to tell which reading the app took.
   */
  private fun isWinner(position: Int): Boolean = currentList.indexOfFirst { it.succeeded } == position && getItem(position).succeeded

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ) = ViewHolder(
    ListItemRuleVerdictBinding.inflate(LayoutInflater.from(parent.context), parent, false),
  )

  override fun onBindViewHolder(
    holder: ViewHolder,
    position: Int,
  ) = holder.bind(getItem(position), isWinner(position))

  class ViewHolder(
    private val binding: ListItemRuleVerdictBinding,
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(
      attempt: PatternAttempt,
      isWinner: Boolean,
    ) {
      val context = binding.root.context
      binding.ruleName.text = attempt.patternName

      val origin =
        context.getString(
          if (attempt.isUserDefined) {
            R.string.series_rules_rule_origin_yours
          } else {
            R.string.series_rules_rule_origin_builtin
          },
        )
      binding.ruleOrigin.text = origin

      // The verdict is **text**, not a colour or an icon: a screen reader and a colour-blind reader
      // must both get the same answer (cu-47). `rejectedReason` is the model's own wording, which
      // is written to be actionable — "not a valid regular expression, so it is ignored" rather
      // than "failed".
      val verdict =
        when {
          isWinner -> context.getString(R.string.series_rules_verdict_won)
          attempt.succeeded -> context.getString(R.string.series_rules_verdict_also_matched)
          else -> attempt.rejectedReason.orEmpty()
        }
      binding.ruleVerdict.text = verdict

      // One description for the whole row, so TalkBack reads "rule audnexus, built in, matched and
      // this is the rule that decided" rather than three disconnected fragments.
      binding.root.contentDescription =
        context.getString(
          R.string.series_rules_rule_description,
          attempt.patternName,
          origin,
          verdict,
        )
    }
  }

  private companion object {
    val DIFF =
      object : DiffUtil.ItemCallback<PatternAttempt>() {
        override fun areItemsTheSame(
          oldItem: PatternAttempt,
          newItem: PatternAttempt,
        ) = oldItem.patternName == newItem.patternName

        override fun areContentsTheSame(
          oldItem: PatternAttempt,
          newItem: PatternAttempt,
        ) = oldItem == newItem
      }
  }
}
