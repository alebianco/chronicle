package io.github.mattpvaughn.chronicle.features.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.databinding.FragmentFacetBooksBinding
import io.github.mattpvaughn.chronicle.features.library.compose.BookGrid
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import javax.inject.Inject

/**
 * The books under one facet value (cu-24).
 *
 * Reuses `AudiobookAdapter` and the library's view-style preference, so a book looks the same
 * however the user arrived at it — the alternative is a third book list that drifts from the other
 * two.
 */
@AndroidEntryPoint
class FacetBooksFragment : Fragment() {
  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexConfig: PlexConfig

  private val viewModel: FacetBooksViewModel by viewModels()

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

    // The grid is `BookGrid` now (cu-201) — shared with the collection-detail screen, which is
    // the same shape: a grid, an empty message, and a tap.
    binding.facetBooksCompose.setContent {
      val books by viewModel.books.collectAsStateWithLifecycle(initialValue = null)
      val style by viewModel.viewStyle.collectAsStateWithLifecycle(initialValue = null)
      val isConnected by plexConfig.isConnected.collectAsStateWithLifecycle()

      ChronicleTheme {
        BookGrid(
          books = books,
          emptyMessage = stringResource(R.string.no_books_found),
          serverConnected = isConnected,
          coverUrl = plexConfig::toServerString,
          onBookClick = { navigator.showDetails(it.id, it.title, it.isCached) },
          style = style?.let { ViewStyleKind.of(it) } ?: ViewStyleKind.CoverGrid,
        )
      }
    }

    // No `setSupportActionBar` (cu-180): this screen has no menu, so the cast bought
    // nothing and only pinned the fragment to an AppCompat host.
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
