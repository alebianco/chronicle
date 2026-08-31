package io.github.mattpvaughn.chronicle.features.collections

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
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCollectionDetailsBinding
import io.github.mattpvaughn.chronicle.features.library.AudiobookAdapter
import io.github.mattpvaughn.chronicle.features.library.LibraryFragment
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import javax.inject.Inject

@ExperimentalCoroutinesApi
class CollectionDetailsFragment : Fragment() {
  companion object {
    fun newInstance(collectionId: String): CollectionDetailsFragment {
      val newFrag = CollectionDetailsFragment()
      val args = Bundle()
      args.putString(ARG_COLLECTION_ID, collectionId)
      newFrag.arguments = args
      return newFrag
    }

    const val TAG = "collection details tag"
    const val ARG_COLLECTION_ID = "collection_id"
  }

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var plexConfig: PlexConfig

  lateinit var viewModel: CollectionDetailsViewModel

  @Inject
  lateinit var viewModelFactory: CollectionDetailsViewModel.Factory

  var adapter: AudiobookAdapter? = null

  override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    Timber.i("CollectionDetailsFragment onAttach()")
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    Timber.i("AudiobookDetailsFragment onCreateView()")

    val binding = FragmentCollectionDetailsBinding.inflate(inflater, container, false)

    val inputId = requireArguments().getString(ARG_COLLECTION_ID)

    viewModelFactory.collectionId = inputId
    viewModel =
      ViewModelProvider(this, viewModelFactory)
        .get(CollectionDetailsViewModel::class.java)

    adapter =
      AudiobookAdapter(
        prefsRepo.libraryBookViewStyle,
        true,
        prefsRepo.bookCoverStyle == PrefsRepo.BOOK_COVER_STYLE_SQUARE,
        object : LibraryFragment.AudiobookClick {
          override fun onClick(audiobook: Audiobook) {
            openAudiobookDetails(audiobook)
          }
        },
      ).apply {
        stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
      }

    viewModel.viewStyle.observe(viewLifecycleOwner) { style ->
      Timber.i("View style is: $style")
      val isGrid =
        when (style) {
          PrefsRepo.VIEW_STYLE_COVER_GRID -> true
          PrefsRepo.VIEW_STYLE_DETAILS_LIST, PrefsRepo.VIEW_STYLE_TEXT_LIST -> false
          else -> throw IllegalStateException("Unknown view style")
        }
      binding.collectionsGrid.layoutManager =
        if (isGrid) {
          GridLayoutManager(requireContext(), 3)
        } else {
          LinearLayoutManager(requireContext())
        }
      adapter!!.viewStyle = style
    }

    binding.collectionsGrid.adapter = adapter

    viewModel.booksInCollection.observe(viewLifecycleOwner) {
      adapter!!.submitList(it)
      // Was an `android:visibility` binding expression in fragment_collection_details.xml.
      binding.noBooksMessage.isVisible = it.isEmpty()
    }

    (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)

    binding.toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    viewModel.title.observe(viewLifecycleOwner) {
      binding.toolbar.title = it?.title ?: ""
    }

    binding.toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  override fun onDestroyView() {
    adapter = null
    super.onDestroyView()
  }

  private fun openAudiobookDetails(audiobook: Audiobook) {
    navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
  }
}
