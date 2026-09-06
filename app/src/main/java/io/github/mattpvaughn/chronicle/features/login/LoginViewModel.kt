package io.github.mattpvaughn.chronicle.features.login

import android.net.Uri
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.OAuthResponse
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.setEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel
  @Inject
  constructor(
    private val plexLoginRepo: IPlexLoginRepo,
    private val exceptionHandler: CoroutineExceptionHandler,
  ) : ViewModel() {
    private val _authEvent = MutableStateFlow<Event<OAuthResponse?>?>(null)
    val authEvent: StateFlow<Event<OAuthResponse?>?>
      get() = _authEvent

    private val _errorEvent = MutableStateFlow<Event<String>?>(null)
    val errorEvent: StateFlow<Event<String>?>
      get() = _errorEvent

    private var hasLaunched = false

    val isLoading =
      plexLoginRepo.loginEvent
        .map { it.peekContent() == IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    fun loginWithOAuth() {
      viewModelScope.launch(exceptionHandler) {
        try {
          val pin = plexLoginRepo.postOAuthPin()
          _authEvent.setEvent(pin)
        } catch (e: Exception) {
          _errorEvent.setEvent("Login failed: ${e.message}")
          timber.log.Timber.e(e, "OAuth login failed")
        }
      }
    }

    fun makeOAuthLoginUrl(
      id: String,
      code: String,
    ): Uri {
      return plexLoginRepo.makeOAuthUrl(id, code)
    }

    /** Whether the custom tab has been launched to login */
    fun setLaunched(b: Boolean) {
      hasLaunched = b
    }

    fun checkForAccess() {
      if (hasLaunched) {
        viewModelScope.launch(exceptionHandler) {
          // Check for access, if the login repo gains access, then our observer in
          // MainActivity will handle navigation
          plexLoginRepo.checkForOAuthAccessToken()
        }
      }
    }
  }
