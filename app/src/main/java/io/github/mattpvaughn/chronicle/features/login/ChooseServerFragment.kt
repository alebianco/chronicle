package io.github.mattpvaughn.chronicle.features.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseServerBinding
import io.github.mattpvaughn.chronicle.features.login.compose.PickerItem
import io.github.mattpvaughn.chronicle.features.login.compose.PickerScreen
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted

@AndroidEntryPoint
class ChooseServerFragment : Fragment() {
  companion object {
    @JvmStatic
    fun newInstance() = ChooseServerFragment()

    const val TAG = "Choose server fragment"
  }

  private val viewModel: ChooseServerViewModel by viewModels()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    super.onCreate(savedInstanceState)

    val binding = OnboardingPlexChooseServerBinding.inflate(inflater, container, false)

    binding.refresh.setOnClickListener { viewModel.refresh() }

    // The list, the spinner and the error message are `PickerScreen` now (cu-201) — shared with
    // the library and user pickers, which are the same screen. The three `isVisible` writes this
    // replaces were a `LoadingStatus` state machine spelled as booleans, with nothing stopping two
    // being true at once.
    binding.serverPickerCompose.setContent {
      val servers by viewModel.servers.collectAsStateWithLifecycle()
      val status by viewModel.loadingStatus.collectAsStateWithLifecycle()

      ChronicleTheme {
        PickerScreen(
          status = status,
          items =
            servers.map {
              PickerItem(id = it.serverId, title = it.name, value = it)
            },
          errorMessage = stringResource(R.string.no_servers_found),
          onItemClick = viewModel::chooseServer,
        )
      }
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.userMessage) { message ->
      Toast.makeText(requireContext(), message, LENGTH_SHORT).show()
    }

    return binding.root
  }
}

class ServerClickListener(val clickListener: (serverModel: ServerModel) -> Unit) {
  fun onClick(server: ServerModel) = clickListener(server)
}
