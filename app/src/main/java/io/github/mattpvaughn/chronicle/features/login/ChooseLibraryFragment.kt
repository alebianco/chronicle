package io.github.mattpvaughn.chronicle.features.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseLibraryBinding
import io.github.mattpvaughn.chronicle.features.login.compose.PickerItem
import io.github.mattpvaughn.chronicle.features.login.compose.PickerScreen
import io.github.mattpvaughn.chronicle.injection.components.injectFromAppGraph
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.views.compose.BottomChooser
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

    binding.refresh.setOnClickListener { viewModel.refresh() }

    // The list, the spinner and the error message are `PickerScreen` now (cu-201), shared with the
    // server and user pickers.
    //
    // The empty *message* stays refined by `emptyReason`: it has three causes and used to render
    // one sentence for all of them — "No libraries found", a claim about the server's contents
    // that was wrong in two of the three. The remedies differ, so the wording has to (cu-125).
    binding.libraryPickerCompose.setContent {
      val libraries by viewModel.libraries.collectAsStateWithLifecycle()
      val status by viewModel.loadingStatus.collectAsStateWithLifecycle()
      val reason by viewModel.emptyReason.collectAsStateWithLifecycle()

      ChronicleTheme {
        PickerScreen(
          status = status,
          items = libraries.map { PickerItem(id = it.id, title = it.name, value = it) },
          errorMessage =
            stringResource(
              when (reason) {
                ChooseLibraryViewModel.EmptyReason.NO_LIBRARIES -> R.string.no_libraries_found
                ChooseLibraryViewModel.EmptyReason.CANNOT_CONNECT ->
                  R.string.library_picker_cannot_connect
                ChooseLibraryViewModel.EmptyReason.REQUEST_FAILED ->
                  R.string.library_picker_request_failed
              },
            ),
          // Through the ViewModel, not the repo directly: switching to a *different* library has
          // to drop the previous one's cached catalogue, or the app shows a union of two (cu-126).
          onItemClick = viewModel::chooseLibrary,
        )

        // Asks about the previous library's downloads on a genuine library change (cu-130). Now
        // `BottomChooser` (cu-203), the same renderer Settings and the player use.
        val chooser by viewModel.bottomChooserState.collectAsStateWithLifecycle()
        BottomChooser(chooser)
      }
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.userMessage) { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    return binding.root
  }
}

class LibraryClickListener(val clickListener: (plexLibrary: PlexLibrary) -> Unit) {
  fun onClick(plexLibrary: PlexLibrary) = clickListener(plexLibrary)
}
