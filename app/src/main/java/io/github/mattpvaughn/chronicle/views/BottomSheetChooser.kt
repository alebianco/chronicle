package io.github.mattpvaughn.chronicle.views

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.R

/**
 * The data a chooser sheet renders, and the deferred strings it renders.
 *
 * **This was a `FrameLayout`** with a hand-rolled show/hide animation, an inner `RecyclerView`
 * adapter and a `DiffUtil`; the Compose migration replaced the rendering with
 * `views/compose/BottomChooser.kt` and left the *types* here, because they are not view code and
 * are named `BottomSheetChooser.FormattableString` at some 200 call sites.
 *
 * **Why [FormattableString] survives Compose.** It looks like a workaround for a `View` being
 * unable to resolve a string resource without a `Context` — and Compose's `stringResource()` would
 * indeed remove that need *if the choice of string were made in the composable*. It is not: these
 * strings are chosen in **ViewModels**, which still cannot hold a `Context`. `SettingsViewModel`
 * alone builds 123 of them. Deferring the `Resources` lookup to render time is the right shape,
 * and `BottomChooser` performs it there.
 */
object BottomSheetChooser {
  /**
   * A chooser's contents.
   *
   * [listener] is part of the state rather than a separate callback because the options are
   * dynamic: what a tap *means* depends on which list is showing, and pairing the two makes them
   * impossible to mismatch.
   */
  data class BottomChooserState(
    val title: FormattableString,
    val options: List<FormattableString>,
    val listener: BottomChooserListener,
    val shouldShow: Boolean,
  ) {
    companion object {
      val EMPTY_BOTTOM_CHOOSER =
        BottomChooserState(
          title = FormattableString.EMPTY_STRING,
          options = emptyList(),
          listener = BottomChooserListener.emptyListener,
          shouldShow = false,
        )
    }
  }

  /** A [BottomChooserListener] that only handles item clicks. */
  abstract class BottomChooserItemListener : BottomChooserListener {
    abstract override fun onItemClicked(formattableString: FormattableString)

    override fun onChooserClosed(wasBackgroundClicked: Boolean) {}
  }

  interface BottomChooserListener {
    /** Triggers when an item in the chooser is clicked */
    fun onItemClicked(formattableString: FormattableString)

    /**
     * Triggers when the chooser is closed.
     *
     * [wasBackgroundClicked] distinguishes a *cancellation* from a choice — three callers depend
     * on the difference, so a dismissed sheet must not read as "picked the first option".
     */
    fun onChooserClosed(wasBackgroundClicked: Boolean = false)

    companion object {
      val emptyListener =
        object : BottomChooserListener {
          override fun onItemClicked(formattableString: FormattableString) {}

          override fun onChooserClosed(wasBackgroundClicked: Boolean) {}
        }
    }
  }

  /**
   * A string that resolves against [Resources] at render time.
   *
   * Lets a ViewModel choose *which* string without holding a `Context`. See the class KDoc for why
   * this outlives the View it was written for.
   */
  sealed class FormattableString {
    data class LiteralString(val string: String) : FormattableString() {
      override fun format(resources: Resources): String {
        if (this == EMPTY_STRING) {
          return ""
        }
        return string
      }
    }

    data class ResourceString(
      @StringRes val stringRes: Int,
      val placeHolderStrings: List<String> = emptyList(),
    ) : FormattableString() {
      override fun format(resources: Resources): String {
        return resources.getString(this.stringRes, *this.placeHolderStrings.toTypedArray())
      }
    }

    abstract fun format(resources: Resources): String

    companion object {
      fun from(
        @StringRes stringRes: Int,
      ): FormattableString {
        return ResourceString(stringRes)
      }

      fun from(string: String): FormattableString {
        return LiteralString(string)
      }

      val yes = from(R.string.yes)
      val no = from(R.string.no)

      val EMPTY_STRING = from("")
    }
  }
}

fun Resources.getString(fs: BottomSheetChooser.FormattableString?): String {
  return fs?.format(this) ?: ""
}
