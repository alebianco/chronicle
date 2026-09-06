package io.github.mattpvaughn.chronicle.testing

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * A generic `@AndroidEntryPoint` host for `FragmentScenario` (cu-178, reworked in cu-185).
 *
 * **Hilt requires this.** A `@AndroidEntryPoint` Fragment refuses to attach to a host that is not
 * one itself — `launchFragmentInContainer`'s stock `EmptyFragmentActivity` fails with *"Hilt
 * Fragments must be attached to an @AndroidEntryPoint Activity"*. So the suites launch into this
 * instead.
 *
 * It is deliberately **generic**: an `AppCompatActivity` with a content view and nothing else. The
 * point cu-178 established still holds — a Fragment that only works inside `MainActivity` is a
 * Fragment coupled to its host's type, and launching into a bare host is what proves it is not.
 * The difference is that the coupling used to be the DI lookup (which Hilt removes) and is now
 * only the annotation.
 */
@AndroidEntryPoint
class HiltFragmentHostActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(io.github.mattpvaughn.chronicle.R.style.AppTheme)
    super.onCreate(savedInstanceState)
  }
}
