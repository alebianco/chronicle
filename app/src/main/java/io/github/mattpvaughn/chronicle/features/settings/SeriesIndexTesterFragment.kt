package io.github.mattpvaughn.chronicle.features.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.PatternOrder
import io.github.mattpvaughn.chronicle.databinding.FragmentSeriesIndexTesterBinding
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import javax.inject.Inject

/**
 * Shows how the series-numbering rules read a title (cu-151).
 *
 * The half of cu-147/cu-148 that lets a user see what a rule *does* before trusting it. tvnamer has
 * the config file and not this, and its open issue #216 is a user who could not tell whether their
 * pattern was wrong or the tool was broken.
 *
 * Reachable from Settings **without editing the file first**, which is the sixth acceptance
 * criterion: the summary and the sample list answer "does my library even need a rule?" from the
 * user's own data.
 */
class SeriesIndexTesterFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: SeriesIndexTesterViewModel.Factory

  private lateinit var viewModel: SeriesIndexTesterViewModel

  override fun onAttach(context: Context) {
    check(injectFromHost { it.inject(this) }) { "${javaClass.simpleName} needs an ActivityComponentHost" }
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentSeriesIndexTesterBinding.inflate(inflater, container, false)
    viewModel = ViewModelProvider(this, viewModelFactory)[SeriesIndexTesterViewModel::class.java]

    binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

    val verdictAdapter = RuleVerdictAdapter()
    binding.ruleVerdicts.layoutManager = LinearLayoutManager(requireContext())
    binding.ruleVerdicts.adapter = verdictAdapter

    val sampleAdapter = SampleTitleAdapter { viewModel.onSampleChosen(it) }
    binding.sampleTitles.layoutManager = LinearLayoutManager(requireContext())
    binding.sampleTitles.adapter = sampleAdapter

    // `doAfterTextChanged`, not an observer on `titleSort`: writing the field back from its own
    // observer would move the cursor while the user types. The one-way flow is input -> ViewModel,
    // and only `onSampleChosen` writes the other way (below).
    binding.titleSortInput.doAfterTextChanged { text ->
      val typed = text?.toString().orEmpty()
      if (typed != viewModel.titleSort.value) {
        viewModel.onTitleSortChanged(typed)
      }
    }

    // A tapped sample has to reach the box, and that is the *only* case where the ViewModel drives
    // the input. The guard above stops the resulting text change from looping back.
    viewLifecycleOwner.collectWhileStarted(viewModel.titleSort) { titleSort ->
      if (binding.titleSortInput.text?.toString() != titleSort) {
        binding.titleSortInput.setText(titleSort)
        binding.titleSortInput.setSelection(titleSort.length)
      }
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.attempts) { attempts ->
      verdictAdapter.submitList(attempts)
      binding.rulesTitle.isVisible = attempts.isNotEmpty()
      binding.ruleVerdicts.isVisible = attempts.isNotEmpty()
    }

    // Driven from `attempts`, **not** from `winningRule`. `winningRule` is a `StateFlow`, so it
    // conflates: testing two different titles that both fail to parse emits `null` twice, the
    // second is dropped as an unchanged value, and the headline never appears. As a `LiveData`
    // transformation it re-emitted regardless, which is why this only broke on the cu-52 merge.
    // `attempts` carries the rule list and changes whenever the input does.
    viewLifecycleOwner.collectWhileStarted(viewModel.attempts) {
      val hasInput = viewModel.titleSort.value.isNotBlank()
      val winner = viewModel.winningRule.value
      binding.parseResult.isVisible = hasInput
      binding.parseResult.text =
        when {
          !hasInput -> ""
          winner == null -> getString(R.string.series_rules_result_none)
          else ->
            getString(
              R.string.series_rules_result_found,
              winner.capturedIndex.orEmpty(),
              winner.patternName,
            )
        }
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.summary) { summary ->
      // Worded as an upper bound, never a defect count: a standalone novel legitimately has no
      // series position, so a perfectly tagged library still reports a large number (58 of 196 on
      // the owner's own library).
      binding.librarySummary.text =
        summary?.let {
          getString(
            R.string.series_rules_summary,
            it.withTitleSort,
            it.total,
            it.parsed,
            it.unparsed,
          )
        }.orEmpty()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.samples) { samples ->
      sampleAdapter.submitList(samples)
      // "Nothing left to fix" is a real state and a reassuring one, so it is said rather than
      // shown as an empty space.
      val loaded = viewModel.summary.value != null
      binding.samplesEmpty.isVisible = loaded && samples.isEmpty()
      binding.sampleTitles.isVisible = samples.isNotEmpty()
    }

    binding.ruleOrder.text =
      getString(
        if (viewModel.userRuleCount == 0) {
          R.string.series_rules_order_none
        } else {
          when (viewModel.ruleOrder) {
            PatternOrder.BEFORE -> R.string.series_rules_order_before
            PatternOrder.AFTER -> R.string.series_rules_order_after
            PatternOrder.REPLACE -> R.string.series_rules_order_replace
          }
        },
      )

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).
    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  companion object {
    const val TAG = "SeriesIndexTesterFragment"

    fun newInstance() = SeriesIndexTesterFragment()
  }
}
