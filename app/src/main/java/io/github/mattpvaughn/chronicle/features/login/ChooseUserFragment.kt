package io.github.mattpvaughn.chronicle.features.login

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.github.mattpvaughn.chronicle.databinding.OnboardingPlexChooseUserBinding
import io.github.mattpvaughn.chronicle.injection.components.injectFromAppGraph
import io.github.mattpvaughn.chronicle.util.collectEventsWhileStarted
import io.github.mattpvaughn.chronicle.util.collectWhileStarted
import javax.inject.Inject

/** Handles the picking of user profiles. */
class ChooseUserFragment : Fragment() {
  companion object {
    @JvmStatic
    fun newInstance() = ChooseUserFragment()

    const val TAG = "Choose user fragment"
  }

  @Inject
  lateinit var viewModelFactory: ChooseUserViewModel.Factory
  private lateinit var viewModel: ChooseUserViewModel

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  @Inject
  lateinit var plexConfig: PlexConfig

  private lateinit var userListAdapter: UserListAdapter

  private var binding: OnboardingPlexChooseUserBinding? = null

  private val pinListener =
    object : TextWatcher {
      override fun afterTextChanged(s: Editable?) {}

      override fun beforeTextChanged(
        s: CharSequence?,
        start: Int,
        count: Int,
        after: Int,
      ) {}

      override fun onTextChanged(
        s: CharSequence?,
        start: Int,
        before: Int,
        count: Int,
      ) {
        if (s != null && this@ChooseUserFragment::viewModel.isInitialized) {
          viewModel.setPinData(s)
          // Automatically submit on 4 digits entered
          if (s.length >= 4) {
            viewModel.submitPin()
          }
        }
      }
    }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    check(injectFromAppGraph { it.inject(this) }) { "${javaClass.simpleName} needs an AppComponentHost" }
    super.onCreate(savedInstanceState)

    val tempBinding = OnboardingPlexChooseUserBinding.inflate(inflater, container, false)

    viewModel =
      ViewModelProvider(
        viewModelStore,
        viewModelFactory,
      ).get(ChooseUserViewModel::class.java)

    userListAdapter =
      UserListAdapter(
        UserClickListener { user ->
          viewModel.pickUser(user)
        },
      )
    tempBinding.userList.adapter = userListAdapter

    tempBinding.pinEdittext.addTextChangedListener(pinListener)
    tempBinding.refresh.setOnClickListener { viewModel.refresh() }
    tempBinding.pinSubmit.setOnClickListener { viewModel.submitPin() }

    // Was `viewModel.showPin ? ... : ...` on the two container layouts.
    viewLifecycleOwner.collectWhileStarted(viewModel.showPin) { showPin ->
      tempBinding.userChooser.isVisible = !showPin
      tempBinding.pinChooser.isVisible = showPin
    }

    // Was `app:users="@{viewModel.users}"` on the list. Missed when cu-58 converted this screen
    // off DataBinding, so the adapter was set and the list made visible but never given any data —
    // "choose user" rendered permanently empty against a real account, while the mock's
    // single-user path looked fine. Found on the owner's phone during cu-73.
    viewLifecycleOwner.collectWhileStarted(viewModel.users) { users ->
      userListAdapter.submitList(users)
    }

    // Was three `app:loadingStatus` bindings on the user list.
    viewLifecycleOwner.collectWhileStarted(viewModel.usersLoadingStatus) { status ->
      tempBinding.userList.isVisible = status == LoadingStatus.DONE
      tempBinding.noUsersFound.isVisible = status == LoadingStatus.ERROR
      tempBinding.loadingIcon.isVisible = status == LoadingStatus.LOADING
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.pinLoadingStatus) { status ->
      tempBinding.pinLoadingIcon.isVisible = status == LoadingStatus.LOADING
      tempBinding.pinSubmit.isVisible = status != LoadingStatus.LOADING
    }

    viewLifecycleOwner.collectEventsWhileStarted(viewModel.userMessage) { message ->
      Toast.makeText(requireContext(), message, LENGTH_SHORT).show()
    }

    tempBinding.pinToolbar.setNavigationOnClickListener {
      hidePinEntryScreen()
    }

    tempBinding.pinEdittext.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_DONE) {
        viewModel.submitPin()
        return@setOnEditorActionListener true
      }
      return@setOnEditorActionListener false
    }

    viewLifecycleOwner.collectWhileStarted(viewModel.pinErrorMessage) {
      tempBinding.pinEdittext.error = it?.takeIf { message -> message.isNotEmpty() }
    }

    binding = tempBinding
    return tempBinding.root
  }

  override fun onDestroyView() {
    binding?.pinEdittext?.removeTextChangedListener(pinListener)
    binding?.pinEdittext?.setOnEditorActionListener(null)
    binding?.pinToolbar?.setNavigationOnClickListener(null)
    binding = null

    super.onDestroyView()
  }

  fun isPinEntryScreenVisible(): Boolean {
    return viewModel.showPin.value
  }

  fun hidePinEntryScreen() {
    viewModel.hidePinScreen()
  }
}

class UserClickListener(val clickListener: (user: PlexUser) -> Unit) {
  fun onClick(user: PlexUser) = clickListener(user)
}
