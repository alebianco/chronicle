package io.github.mattpvaughn.chronicle.features.browse

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.viewStyleIsGrid
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.databinding.FragmentFacetBooksBinding
import io.github.mattpvaughn.chronicle.features.library.AudiobookAdapter
import io.github.mattpvaughn.chronicle.features.library.LibraryFragment
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import javax.inject.Inject

/**
 * The books under one facet value (cu-24).
 *
 * Reuses `AudiobookAdapter` and the library's view-style preference, so a book looks the same
 * however the user arrived at it — the alternative is a third book list that drifts from the other
 * two.
 */
class FacetBooksFragment : Fragment() {
  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var viewModelFactory: FacetBooksViewModel.Factory

  private lateinit var viewModel: FacetBooksViewModel
  private var adapter: AudiobookAdapter? = null

  override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentFacetBooksBinding.inflate(inflater, container, false)

    val kindName = requireArguments().getString(ARG_KIND).orEmpty()
    val value = requireArguments().getString(ARG_VALUE).orEmpty()
    // By name, not ordinal: an ordinal in a Bundle survives a process death and would silently
    // mean a different facet if the enum ever gained a member.
    viewModelFactory.kind =
      FacetKind.entries.firstOrNull { it.name == kindName } ?: FacetKind.Author
    viewModelFactory.value = value
    viewModel =
      ViewModelProvider(this, viewModelFactory)[FacetBooksViewModel::class.java]

    val bookAdapter =
      AudiobookAdapter(
        prefsRepo.libraryBookViewStyle,
        true,
        prefsRepo.bookCoverStyle == PrefsRepo.BOOK_COVER_STYLE_SQUARE,
        object : LibraryFragment.AudiobookClick {
          override fun onClick(audiobook: Audiobook) {
            navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
          }
        },
      ).apply {
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
      }
    adapter = bookAdapter
    binding.facetBooksGrid.adapter = bookAdapter

    viewModel.viewStyle.observe(viewLifecycleOwner) { style ->
      binding.facetBooksGrid.layoutManager =
        if (viewStyleIsGrid(style)) {
          GridLayoutManager(requireContext(), 3)
        } else {
          LinearLayoutManager(requireContext())
        }
      bookAdapter.viewStyle = style
    }

    viewModel.books.observe(viewLifecycleOwner) { books ->
      bookAdapter.submitList(books)
      binding.noBooksMessage.isVisible = books.isEmpty()
    }

    (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
    binding.toolbar.title = value
    binding.toolbar.setNavigationOnClickListener {
      parentFragmentManager.popBackStack()
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).
    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  override fun onDestroyView() {
    super.onDestroyView()
    adapter = null
  }

  companion object {
    const val TAG = "FacetBooksFragment"

    private const val ARG_KIND = "facet_kind"
    private const val ARG_VALUE = "facet_value"

    fun newInstance(
      kind: FacetKind,
      value: String,
    ) = FacetBooksFragment().apply {
      arguments =
        Bundle().apply {
          putString(ARG_KIND, kind.name)
          putString(ARG_VALUE, value)
        }
    }
  }
}
