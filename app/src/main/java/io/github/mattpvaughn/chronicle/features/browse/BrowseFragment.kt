package io.github.mattpvaughn.chronicle.features.browse

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.tabs.TabLayout
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList
import io.github.mattpvaughn.chronicle.databinding.FragmentBrowseBinding
import io.github.mattpvaughn.chronicle.navigation.Navigator
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

  private var binding: FragmentBrowseBinding? = null

  override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentBrowseBinding.inflate(inflater, container, false)
    this.binding = binding
    viewModel = ViewModelProvider(this, viewModelFactory)[BrowseViewModel::class.java]

    binding.browseToolbar.setNavigationOnClickListener {
      parentFragmentManager.popBackStack()
    }

    val adapter =
      FacetListAdapter { facet ->
        val kind = viewModel.kind.value ?: FacetKind.Author
        navigator.showFacetBooks(kind, facet.value)
      }
    binding.facetList.adapter = adapter

    // Tabs in the same order as the enum, so the index maps without a lookup table that could
    // drift from it.
    FacetKind.entries.forEach { kind ->
      binding.facetTabs.addTab(
        binding.facetTabs.newTab().setText(getString(kind.labelRes())),
      )
    }
    binding.facetTabs.addOnTabSelectedListener(
      object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
          viewModel.showFacet(FacetKind.entries[tab.position])
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
      },
    )

    viewModel.facets.observe(viewLifecycleOwner) { facets ->
      adapter.submitList(facets.facets)
      render(facets)
    }

    return binding.root
  }

  private fun render(facets: FacetList) {
    val binding = binding ?: return
    // The empty message and the list are mutually exclusive: showing both reads as a bug, and
    // showing neither leaves a blank screen with no explanation.
    val isEmpty = facets.facets.isEmpty()
    binding.facetList.isVisible = !isEmpty
    binding.facetEmpty.isVisible = isEmpty
    binding.facetEmpty.text = getString(facets.kind.emptyRes())

    // Only qualified when it needs to be — a complete index must not carry a caveat, or the
    // caveat stops being read.
    binding.facetCoverage.isVisible = facets.isPartial
    if (facets.isPartial) {
      binding.facetCoverage.text =
        resources.getQuantityString(
          R.plurals.browse_coverage,
          facets.unknownCount,
          facets.unknownCount,
        )
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding?.facetList?.adapter = null
    binding = null
  }

  companion object {
    const val TAG = "BrowseFragment"

    fun newInstance() = BrowseFragment()
  }
}

/** The tab label for a facet. Kept next to the enum's use rather than in a `when` per call site. */
private fun FacetKind.labelRes(): Int =
  when (this) {
    FacetKind.Author -> R.string.browse_by_author
    FacetKind.Narrator -> R.string.browse_by_narrator
    FacetKind.Series -> R.string.browse_by_series
  }

/** What to say when a facet has nothing — different per facet, since the *reason* differs. */
private fun FacetKind.emptyRes(): Int =
  when (this) {
    FacetKind.Author -> R.string.browse_no_authors
    FacetKind.Narrator -> R.string.browse_no_narrators
    FacetKind.Series -> R.string.browse_no_series
  }
