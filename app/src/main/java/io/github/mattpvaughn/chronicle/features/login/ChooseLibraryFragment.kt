package io.github.mattpvaughn.chronicle.features.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseLibraryBinding
import io.github.mattpvaughn.chronicle.injection.components.injectFromAppGraph
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import timber.log.Timber
import javax.inject.Inject

class ChooseLibraryFragment : Fragment() {
  companion object {
    @JvmStatic
    fun newInstance() = ChooseLibraryFragment()

    const val TAG = "choose library fragment"
  }

  @Inject
  lateinit var viewModelFactory: ChooseLibraryViewModel.Factory

  private lateinit var viewModel: ChooseLibraryViewModel

  private lateinit var libraryAdapter: LibraryListAdapter

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var plexPrefs: PlexPrefsRepo

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    check(injectFromAppGraph { it.inject(this) }) { "${javaClass.simpleName} needs an AppComponentHost" }
    super.onCreate(savedInstanceState)

    val binding = OnboardingPlexChooseLibraryBinding.inflate(inflater, container, false)

    viewModel =
      ViewModelProvider(
        viewModelStore,
        viewModelFactory,
      ).get(ChooseLibraryViewModel::class.java)

    libraryAdapter =
      LibraryListAdapter(
        LibraryClickListener { library ->
          Timber.i("Library name: $library")
          // Through the ViewModel, not the repo directly: switching to a *different* library has
          // to drop the previous one's cached catalogue, or the app shows a union of two (cu-126).
          viewModel.chooseLibrary(library)
        },
      )

    binding.libraryList.adapter = libraryAdapter
    binding.refresh.setOnClickListener { viewModel.refresh() }

    // Asks about the previous library's downloads on a genuine library change (cu-130). Reuses the
    // shared renderer so the sheet looks and behaves exactly as it does in Settings.
    viewLifecycleOwner.collectWhileStarted(viewModel.bottomChooserState) { state ->
      setBottomChooserState(binding.bottomSheetChooser, state)
    }

    // Was three `app:loadingStatus` bindings in XML, one per view type.
    viewLifecycleOwner.collectWhileStarted(viewModel.loadingStatus) { status ->
      binding.libraryList.isVisible = status == LoadingStatus.DONE
      binding.noLibrariesFound.isVisible = status == LoadingStatus.ERROR
      binding.loadingIcon.isVisible = status == LoadingStatus.LOADING
    }

    // The empty state has three causes and used to render one sentence for all of them —
    // "No libraries found", which is a claim about the *server's contents* and was wrong in two.
    // The remedies differ completely, so the message has to (cu-125).
    viewLifecycleOwner.collectWhileStarted(viewModel.emptyReason) { reason ->
      binding.noLibrariesFound.setText(
        when (reason) {
          ChooseLibraryViewModel.EmptyReason.NO_LIBRARIES -> R.string.no_libraries_found
          ChooseLibraryViewModel.EmptyReason.CANNOT_CONNECT -> R.string.library_picker_cannot_connect
          ChooseLibraryViewModel.EmptyReason.REQUEST_FAILED -> R.string.library_picker_request_failed
        },
      )
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.userMessage) { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.libraries) { libraries ->
      libraryAdapter.submitList(libraries)
    }

    return binding.root
  }
}

class LibraryClickListener(val clickListener: (plexLibrary: PlexLibrary) -> Unit) {
  fun onClick(plexLibrary: PlexLibrary) = clickListener(plexLibrary)
}
