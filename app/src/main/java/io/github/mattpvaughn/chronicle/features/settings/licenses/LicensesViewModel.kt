package io.github.mattpvaughn.chronicle.features.settings.licenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the licences screen is showing.
 *
 * A sealed type rather than a data class with an `isLoading` flag and a nullable catalogue, for the
 * reason the `android-ui` skill records: a seed that is a *real-looking value* renders as one. A
 * `LicenseCatalog.EMPTY` seed would draw "0 third-party libraries" for as long as the read takes,
 * which on a compliance page is not a placeholder — it is a false statement. [Loading] makes that
 * state unrepresentable instead.
 */
sealed interface LicensesUiState {
  /** The catalogue has not been read yet. */
  data object Loading : LicensesUiState

  /** The catalogue was read. */
  data class Loaded(val catalog: LicenseCatalog) : LicensesUiState

  /**
   * The catalogue could not be read.
   *
   * Deliberately **not** collapsed into `Loaded(EMPTY)`. An empty licences page and a broken one
   * look identical to a reader, and only one of them is honest.
   */
  data object Failed : LicensesUiState
}

/**
 * State for the third-party licences screen.
 *
 * The list itself is generated at build time from the resolved dependency graph
 * ([GeneratedLicenseCatalogSource]); this reads it once and holds it. There is no refresh: the
 * content cannot change without a new build, so re-reading it would be work with no possible new
 * answer.
 *
 * `MutableStateFlow` + a single `viewModelScope.launch` rather than a `stateIn(WhileSubscribed)`
 * flow, because the read is one-shot. `WhileSubscribed` would re-run the parse every time the user
 * rotated the device past the stop timeout, re-parsing a few hundred KB to produce the same list.
 */
@HiltViewModel
class LicensesViewModel
  @Inject
  constructor(
    private val source: LicenseCatalogSource,
  ) : ViewModel() {
    private val _uiState = MutableStateFlow<LicensesUiState>(LicensesUiState.Loading)

    /** The catalogue, or why there isn't one. */
    val uiState: StateFlow<LicensesUiState>
      get() = _uiState

    init {
      viewModelScope.launch {
        val catalog = source.load()
        _uiState.value =
          if (catalog == null) LicensesUiState.Failed else LicensesUiState.Loaded(catalog)
      }
    }
  }
