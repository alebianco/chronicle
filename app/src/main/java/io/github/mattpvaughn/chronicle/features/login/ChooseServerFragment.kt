package io.github.mattpvaughn.chronicle.features.login

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseServerBinding
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import javax.inject.Inject

class ChooseServerFragment : Fragment() {
  companion object {
    @JvmStatic
    fun newInstance() = ChooseServerFragment()

    const val TAG = "Choose server fragment"
  }

  @Inject
  lateinit var viewModelFactory: ChooseServerViewModel.Factory
  private lateinit var viewModel: ChooseServerViewModel

  private lateinit var serverAdapter: ServerListAdapter

  override fun onAttach(context: Context) {
    ((activity as Activity).application as ChronicleApplication)
      .appComponent
      .inject(this)
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    super.onCreate(savedInstanceState)

    val binding = OnboardingPlexChooseServerBinding.inflate(inflater, container, false)

    viewModel =
      ViewModelProvider(
        viewModelStore,
        viewModelFactory,
      ).get(ChooseServerViewModel::class.java)

    serverAdapter =
      ServerListAdapter(
        ServerClickListener { serverModel ->
          viewModel.chooseServer(serverModel)
        },
      )

    binding.serverList.adapter = serverAdapter
    binding.refresh.setOnClickListener { viewModel.refresh() }

    viewLifecycleOwner.collectWhileStarted(viewModel.servers) { servers ->
      serverAdapter.submitList(servers)
    }

    // Was three `app:loadingStatus` bindings in XML, one per view type.
    viewLifecycleOwner.collectWhileStarted(viewModel.loadingStatus) { status ->
      binding.serverList.isVisible = status == LoadingStatus.DONE
      binding.noServersFound.isVisible = status == LoadingStatus.ERROR
      binding.loadingIcon.isVisible = status == LoadingStatus.LOADING
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
