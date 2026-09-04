package io.github.mattpvaughn.chronicle.features.login

import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.asServer
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.setEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class ChooseServerViewModel
  @Inject
  constructor(
    private val plexLoginService: PlexLoginService,
    private val plexLoginRepo: PlexLoginRepo,
    private val exceptionHandler: CoroutineExceptionHandler,
  ) : ViewModel() {
    class Factory
      @Inject
      constructor(
        private val plexLoginService: PlexLoginService,
        private val plexLoginRepo: PlexLoginRepo,
        private val exceptionHandler: CoroutineExceptionHandler,
      ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(ChooseServerViewModel::class.java)) {
            return ChooseServerViewModel(plexLoginService, plexLoginRepo, exceptionHandler) as T
          }
          throw IllegalArgumentException("Unknown ViewHolder class")
        }
      }

    private val _userMessage = MutableStateFlow<Event<String>?>(null)
    val userMessage: StateFlow<Event<String>?>
      get() = _userMessage

    private val _servers = MutableStateFlow(emptyList<ServerModel>())
    val servers: StateFlow<List<ServerModel>>
      get() = _servers

    private val _loadingStatus = MutableStateFlow(LoadingStatus.LOADING)
    val loadingStatus: StateFlow<LoadingStatus>
      get() = _loadingStatus

    init {
      loadServers()
    }

    private fun loadServers() {
      viewModelScope.launch(exceptionHandler) {
        try {
          _loadingStatus.value = LoadingStatus.LOADING
          val serverContainer = plexLoginService.resources()
          Timber.i("Server: $serverContainer")
          _loadingStatus.value = LoadingStatus.DONE
          _servers.value =
            serverContainer
              .filter { it.provides.contains("server") }
              .map { it.asServer() }
        } catch (e: Throwable) {
          Timber.e(e, "Failed to get servers")
          _userMessage.setEvent("Failed to load servers: ${e.message}")
          _loadingStatus.value = LoadingStatus.ERROR
        }
      }
    }

    fun refresh() {
      loadServers()
    }

    fun chooseServer(serverModel: ServerModel) {
      plexLoginRepo.chooseServer(serverModel)
    }
  }
