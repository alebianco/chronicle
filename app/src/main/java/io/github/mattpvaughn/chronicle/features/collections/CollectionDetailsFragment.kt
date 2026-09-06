package io.github.mattpvaughn.chronicle.features.collections

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentCollectionDetailsBinding
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.injection.components.injectFromHost
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
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

  override fun onAttach(context: Context) {
    check(injectFromHost { it.inject(this) }) { "${javaClass.simpleName} needs an ActivityComponentHost" }
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

    // The grid is `BookGrid` now (cu-201) — the same composable the browse-facet screen uses,
    // since both are a grid, an empty message and a tap.
    binding.collectionDetailsCompose.setContent {
      val books by viewModel.booksInCollection.collectAsStateWithLifecycle()
      val style by viewModel.viewStyle.collectAsStateWithLifecycle(initialValue = null)
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

      ChronicleTheme {
        BookGrid(
          books = books,
          emptyMessage = stringResource(R.string.no_books_found),
          serverConnected = isConnected,
          coverUrl = plexConfig::toServerString,
          onBookClick = ::openAudiobookDetails,
          style = style?.let { ViewStyleKind.of(it) } ?: ViewStyleKind.CoverGrid,
        )
      }
    }

    // No `setSupportActionBar` (cu-180): this screen has no menu, so the cast bought
    // nothing and only pinned the fragment to an AppCompat host.

    binding.toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.title) {
      binding.toolbar.title = it?.title ?: ""
    }

    binding.toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    // targetSdk 36 is edge-to-edge; the toolbar must inset itself (cu-63).

    binding.toolbarLayout.applyTopSystemBarInset()

    return binding.root
  }

  private fun openAudiobookDetails(audiobook: Audiobook) {
    navigator.showDetails(audiobook.id, audiobook.title, audiobook.isCached)
  }
}
