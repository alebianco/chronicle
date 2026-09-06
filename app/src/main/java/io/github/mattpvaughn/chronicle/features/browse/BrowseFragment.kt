package io.github.mattpvaughn.chronicle.features.browse

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.databinding.FragmentBrowseBinding
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseScreen
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import javax.inject.Inject

/**
 * Browse the library by author, narrator or series (cu-24).
 *
 * Narrator and series come from Plex's `Style`/`Mood` tags, which arrive only on the **per-book**
 * detail response — so the index fills in as books are opened rather than on a library refresh.
 * The coverage line says so: a facet list showing 12 narrators out of 196 books without qualifying
 * itself reads as "these are all the narrators I have", which is worse than showing nothing.
 */
class BrowseFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: BrowseViewModel.Factory

  @Inject
  lateinit var navigator: Navigator

  private lateinit var viewModel: BrowseViewModel

  override fun onAttach(context: Context) {
    check(injectFromHost { it.inject(this) }) { "${javaClass.simpleName} needs an ActivityComponentHost" }
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentBrowseBinding.inflate(inflater, container, false)
    viewModel = ViewModelProvider(this, viewModelFactory)[BrowseViewModel::class.java]

    binding.browseToolbar.setNavigationOnClickListener {
      parentFragmentManager.popBackStack()
    }

    // The tabs, the list, the coverage line and the empty message are `BrowseScreen` now (cu-202).
    // The `FacetList.EMPTY` seed used to reach the screen as a real value, so "No narrators yet"
    // showed before the first grouping ran; `BrowseContent.Loading` makes that unrepresentable.
    binding.browseCompose.setContent {
      val state by viewModel.uiState.collectAsStateWithLifecycle()

      ChronicleTheme {
        BrowseScreen(
          state = state,
          onSelectFacet = viewModel::showFacet,
          onFacetClick = { navigator.showFacetBooks(state.selected, it.value) },
        )
      }
    }

    return binding.root
  }

  companion object {
    const val TAG = "BrowseFragment"

    fun newInstance() = BrowseFragment()
  }
}
