package io.github.mattpvaughn.chronicle.features.login

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.setEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChooseUserViewModel
  @Inject
  constructor(
    private val plexLoginService: PlexLoginService,
    private val plexLoginRepo: IPlexLoginRepo,
    private val exceptionHandler: CoroutineExceptionHandler,
  ) : ViewModel() {
    private val _userMessage = MutableStateFlow<Event<String>?>(null)
    val userMessage: StateFlow<Event<String>?>
      get() = _userMessage

    private val _showPin = MutableStateFlow(false)
    val showPin: StateFlow<Boolean>
      get() = _showPin

    private val _user = MutableStateFlow<PlexUser?>(null)
    val user: StateFlow<PlexUser?>
      get() = _user

    private val _pinData = MutableStateFlow("")
    private val pinData: StateFlow<String>
      get() = _pinData

    private val _pinErrorMessage = MutableStateFlow<String?>(null)
    val pinErrorMessage: StateFlow<String?>
      get() = _pinErrorMessage

    private val _users = MutableStateFlow(emptyList<PlexUser>())
    val users: StateFlow<List<PlexUser>>
      get() = _users

    private val _usersLoadingStatus = MutableStateFlow(LoadingStatus.LOADING)
    val usersLoadingStatus: StateFlow<LoadingStatus>
      get() = _usersLoadingStatus

    private val _pinLoadingStatus = MutableStateFlow(LoadingStatus.DONE)
    val pinLoadingStatus: StateFlow<LoadingStatus>
      get() = _pinLoadingStatus

    init {
      loadUsers()
    }

    private fun loadUsers(forceLoad: Boolean = false) {
      // Don't override current users unless we explicitly force a reload
      if (users.value.isNotEmpty() && !forceLoad) {
        return
      }
      viewModelScope.launch(exceptionHandler) {
        try {
          _usersLoadingStatus.value = LoadingStatus.LOADING
          val usersResponse = plexLoginService.getUsersForAccount()
          _users.value = usersResponse.users
          _usersLoadingStatus.value = LoadingStatus.DONE
        } catch (e: Throwable) {
          Timber.e(e, "Failed to get users")
          _userMessage.setEvent("Failed to load users: ${e.message}")
          _usersLoadingStatus.value = LoadingStatus.ERROR
        }
      }
    }

    fun refresh() {
      loadUsers(forceLoad = true)
    }

    fun submitPin() {
      viewModelScope.launch {
        user.value?.uuid?.let { uuid ->
          submitPin(uuid, pinData.value)
        }
      }
    }

    fun pickUser(user: PlexUser) {
      // If a pin is required before a query can be sent off, show the pin now
      _user.value = user
      if (user.hasPassword) {
        _showPin.value = true
      } else {
        viewModelScope.launch {
          submitPin(user.uuid, null)
        }
      }
    }

    private suspend fun submitPin(
      uuid: String,
      pin: String?,
    ) {
      try {
        _pinLoadingStatus.value = LoadingStatus.LOADING
        val responseUser: PlexUser = plexLoginService.pickUser(uuid, pin)
        if (responseUser.authToken.isNullOrEmpty()) {
          throw IllegalStateException("Pin submitted but no auth token received")
        }
        plexLoginRepo.chooseUser(responseUser)
        _pinLoadingStatus.value = LoadingStatus.DONE
      } catch (t: HttpException) {
        when (t.code()) {
          403 -> _userMessage.setEvent("Incorrect pin submitted. Try again")
          else -> _userMessage.setEvent("Error submitting pin (${t.code()}). Try again")
        }
        _pinLoadingStatus.value = LoadingStatus.ERROR
      } catch (t: Throwable) {
        _userMessage.setEvent("Error occurred when submitting pin. Try again")
        Timber.e(t, "Failed to submit pin")
        _pinLoadingStatus.value = LoadingStatus.ERROR
      }
    }

    fun setPinData(s: CharSequence) {
      if (s.length != 4) {
        _pinErrorMessage.value = "Too short"
      } else {
        _pinErrorMessage.value = ""
      }
      try {
        _pinData.value = s.toString()
      } catch (t: NumberFormatException) {
        _pinErrorMessage.value = "Pin must be 0000-9999"
        Timber.e("Failed to parse pin to int!")
      }
    }

    fun hidePinScreen() {
      _showPin.value = false
    }
  }
