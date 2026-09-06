package io.github.mattpvaughn.chronicle.features.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.mattpvaughn.chronicle.databinding.FragmentSeriesIndexTesterBinding
import io.github.mattpvaughn.chronicle.features.settings.compose.SeriesIndexTesterScreen
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset

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
@AndroidEntryPoint
class SeriesIndexTesterFragment : Fragment() {
  private val viewModel: SeriesIndexTesterViewModel by viewModels()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentSeriesIndexTesterBinding.inflate(inflater, container, false)

    binding.toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

    // The whole body is `SeriesIndexTesterScreen` now (cu-202) — five flows and eight `isVisible`
    // decisions become one state, and the guarded two-way text sync goes with them: hoisted state
    // has one direction, so nothing can move the caret while the user types.
    binding.testerCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()

      ChronicleTheme {
        SeriesIndexTesterScreen(
          state = state,
          onTitleSortChanged = viewModel::onTitleSortChanged,
          onSampleChosen = viewModel::onSampleChosen,
        )
      }
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).
    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  companion object {
    const val TAG = "SeriesIndexTesterFragment"

    fun newInstance() = SeriesIndexTesterFragment()
  }
}
