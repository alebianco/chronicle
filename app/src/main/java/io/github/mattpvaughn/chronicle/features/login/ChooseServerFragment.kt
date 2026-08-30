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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseServerBinding
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

    viewModel.servers.observe(
      viewLifecycleOwner,
      Observer { servers ->
        servers?.let {
          serverAdapter.submitList(it)
        }
      },
    )

    // Was three `app:loadingStatus` bindings in XML, one per view type.
    viewModel.loadingStatus.observe(viewLifecycleOwner) { status ->
      binding.serverList.isVisible = status == LoadingStatus.DONE
      binding.noServersFound.isVisible = status == LoadingStatus.ERROR
      binding.loadingIcon.isVisible = status == LoadingStatus.LOADING
    }

    viewModel.userMessage.observe(
      viewLifecycleOwner,
      Observer {
        if (it.hasBeenHandled) {
          return@Observer
        }
        Toast.makeText(requireContext(), it.getContentIfNotHandled(), LENGTH_SHORT).show()
      },
    )

    return binding.root
  }
}

class ServerClickListener(val clickListener: (serverModel: ServerModel) -> Unit) {
  fun onClick(server: ServerModel) = clickListener(server)
}
