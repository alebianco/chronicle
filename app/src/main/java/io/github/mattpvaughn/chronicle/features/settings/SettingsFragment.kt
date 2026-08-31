package io.github.mattpvaughn.chronicle.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.databinding.FragmentSettingsBinding
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.navigation.Navigator
import io.github.mattpvaughn.chronicle.util.applyTopSystemBarInset
import io.github.mattpvaughn.chronicle.views.getString
import io.github.mattpvaughn.chronicle.views.setBottomChooserState
import javax.inject.Inject

class SettingsFragment : Fragment() {
  @Inject
  lateinit var viewModelFactory: SettingsViewModel.Factory

  @Inject
  lateinit var mediaServiceConnection: MediaServiceConnection

  @Inject
  lateinit var navigator: Navigator

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  @Inject
  lateinit var cachedFileManager: ICachedFileManager

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var plexPrefsRepo: PlexPrefsRepo

  @Inject
  lateinit var plexConfig: PlexConfig

  companion object {
    @JvmStatic
    fun newInstance() = SettingsFragment()
  }

  override fun onAttach(context: Context) {
    (requireActivity() as MainActivity).activityComponent!!.inject(this)
    super.onAttach(context)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    val binding = FragmentSettingsBinding.inflate(inflater, container, false)
    val viewModel = ViewModelProvider(this, viewModelFactory).get(SettingsViewModel::class.java)

    // Was `bottomChooserState` / `preferences` binding adapters in fragment_settings.xml.
    viewModel.bottomChooserState.observe(viewLifecycleOwner) { state ->
      setBottomChooserState(binding.bottomSheetChooser, state)
    }

    viewModel.preferences.observe(viewLifecycleOwner) { preferences ->
      binding.settingsList.setPreferences(preferences)
    }

    viewModel.messageForUser.observe(
      viewLifecycleOwner,
      Observer { message ->
        if (!message.hasBeenHandled) {
          val formattableString = message.getContentIfNotHandled()
          Toast.makeText(
            context,
            resources.getString(formattableString),
            Toast.LENGTH_SHORT,
          )
            .show()
        }
      },
    )

    viewModel.webLink.observe(
      viewLifecycleOwner,
      Observer {
        if (!it.hasBeenHandled) {
          startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(it.getContentIfNotHandled())),
          )
        }
      },
    )

    viewModel.showLicenseActivity.observe(
      viewLifecycleOwner,
      Observer {
        if (it) {
          startActivity(Intent(context, OssLicensesMenuActivity::class.java))
          viewModel.setShowLicenseActivity(false)
        }
      },
    )

    // Settings has no toolbar, so the list itself takes the top inset (cu-63).

    binding.settingsList.applyTopSystemBarInset()

    return binding.root
  }
}
