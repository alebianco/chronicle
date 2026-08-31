package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a test dispatcher as `Dispatchers.Main` for the duration of a test.
 *
 * ViewModels reach `Dispatchers.Main` during *construction* — `asLiveData()` and `viewModelScope`
 * both do — so without this a ViewModel cannot even be instantiated on the JVM:
 *
 * > Dispatchers.Main was accessed when the platform dispatcher was absent
 *
 * That, rather than anything about their design, is why none of the twelve ViewModels had a test.
 * Paying it once here makes them all reachable.
 *
 * Note this is separate from the injected [DispatcherProvider] (cu-15), which covers code the
 * project controls; this covers the framework's own use of the main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
  val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
  override fun starting(description: Description) {
    Dispatchers.setMain(testDispatcher)
  }

  override fun finished(description: Description) {
    Dispatchers.resetMain()
  }
}
