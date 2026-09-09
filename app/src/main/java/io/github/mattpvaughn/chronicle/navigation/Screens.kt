package io.github.mattpvaughn.chronicle.navigation

import com.slack.circuit.runtime.screen.Screen
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import kotlinx.serialization.Serializable

/**
 * Every screen the app can navigate to, as Circuit screen keys.
 *
 * This replaces the sealed `Destination` interface and its route strings. The change is not
 * cosmetic: Navigation Compose addressed destinations by **string route**, so every argument made a
 * round trip through a URL path segment and had to be percent-encoded on the way in and decoded on
 * the way out. A raw `/` or `?` in a book title or a facet value like `"Tolkien, J.R.R."` silently
 * failed to match the route pattern, landing the user nowhere with no error at all — which is why
 * `encodeArg`/`decodeArg` existed and why they were the most carefully tested thing in the file.
 *
 * A Circuit screen key is an ordinary Kotlin object. `BookDetails("a/b?c")` carries the string
 * itself, so there is no encoding to get wrong and no pattern to fail to match. The whole class of
 * defect is gone rather than guarded against, and that is the single largest thing this migration
 * buys.
 *
 * ### Still framework-free
 *
 * `Screen` at Circuit 0.38.0 extends `CircuitSaveable`, a **marker interface with no members** —
 * no `Parcelable`, no `@Parcelize`, no Android types. (At 0.31.x `Screen` *was* `Parcelable`; an
 * earlier attempt here added `@Parcelize` on that assumption and failed. The API moved between
 * minors, which is the `0.x` risk decision-27 records.)
 *
 * A marker interface is not enough to *save* a key, though: Circuit's default saver hands the
 * back stack to Compose's `SaveableStateRegistry`, which rejects a type it cannot put in a
 * `Bundle` — on a device that is a crash on launch, not a silent loss. The keys are `@Serializable`
 * instead of `Parcelable`, and `ScreenKeySaver` converts them to and from JSON. That keeps this
 * file **framework-free** — the gate `FrameworkFreeCoreTest` enforces, which `Destination.kt` was
 * also under — and reuses the serializer the project already ships for 24 other models.
 */
@Serializable
sealed interface ChronicleScreen : Screen

/** The four bottom-navigation roots. */
@Serializable
data object HomeScreenKey : ChronicleScreen

@Serializable
data object LibraryScreenKey : ChronicleScreen

@Serializable
data object CollectionsScreenKey : ChronicleScreen

@Serializable
data object SettingsScreenKey : ChronicleScreen

/** Reached from Library. */
@Serializable
data object BrowseScreenKey : ChronicleScreen

/** Reached from Settings. */
@Serializable
data object SeriesIndexTesterScreenKey : ChronicleScreen

/** The third-party licences list, reached from Settings. */
@Serializable
data object LicensesScreenKey : ChronicleScreen

/** The four onboarding screens, driven by `IPlexLoginRepo.loginEvent`. */
@Serializable
data object LoginScreenKey : ChronicleScreen

@Serializable
data object ChooseUserScreenKey : ChronicleScreen

@Serializable
data object ChooseServerScreenKey : ChronicleScreen

@Serializable
data object ChooseLibraryScreenKey : ChronicleScreen

/**
 * One book's details.
 *
 * Carries only the id. Title and cached-state were Fragment arguments (`ARG_AUDIOBOOK_TITLE`,
 * `ARG_IS_AUDIOBOOK_CACHED`) purely so the toolbar could render before the DB read returned; the
 * screen reads all three from the repository keyed on id, so carrying them here would duplicate
 * state that can go stale.
 */
@Serializable
data class BookDetailsScreenKey(val bookId: String) : ChronicleScreen

@Serializable
data class CollectionDetailsScreenKey(val collectionId: String) : ChronicleScreen

/**
 * The books carrying one facet value — a narrator, a genre, a series.
 *
 * [value] is arbitrary server text and reaches the toolbar as the title. Under the route-string
 * scheme it was encoded into a path segment and decoded back out inside the nav graph; here it is
 * simply the field.
 */
@Serializable
data class FacetBooksScreenKey(val kind: FacetKind, val value: String) : ChronicleScreen

/**
 * The four screens reachable from the bottom navigation bar, in bar order.
 *
 * Navigating to one **resets the root** rather than pushing: tabs are roots, not a stack. That was
 * `popUpTo(startDestination)` under Navigation Compose and, before that, a
 * `while (backStackEntryCount > 0) popBackStackImmediate()` loop in the old `Navigator`.
 */
val topLevelScreens = listOf(HomeScreenKey, LibraryScreenKey, CollectionsScreenKey, SettingsScreenKey)

/**
 * Where a login state should navigate to, or null when it should not navigate at all.
 *
 * This was the `when` inside the old `Navigator`'s init block, which committed a `FragmentManager`
 * transaction per branch. Pulled out as a pure function so the routing is testable without an
 * Activity — and because two of its branches are deliberately *nothing*, which is the kind of thing
 * a test should pin rather than a reader infer.
 *
 * `FAILED_TO_LOG_IN` and `AWAITING_LOGIN_RESULTS` stay on the current screen on purpose: the login
 * screen reports both itself, and navigating away from it would discard the message.
 *
 * The `else ->` branch that used to `throw NoWhenBranchMatchedException` is gone. An unknown state
 * crashing the app was never the right response to a login event, and the enum is exhaustive here.
 */
fun screenForLogin(state: IPlexLoginRepo.LoginState): ChronicleScreen? =
  when (state) {
    IPlexLoginRepo.LoginState.LOGGED_IN_NO_USER_CHOSEN -> ChooseUserScreenKey
    IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN -> ChooseServerScreenKey
    IPlexLoginRepo.LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN -> ChooseLibraryScreenKey
    IPlexLoginRepo.LoginState.LOGGED_IN_FULLY -> HomeScreenKey
    IPlexLoginRepo.LoginState.NOT_LOGGED_IN -> LoginScreenKey
    IPlexLoginRepo.LoginState.FAILED_TO_LOG_IN -> null
    IPlexLoginRepo.LoginState.AWAITING_LOGIN_RESULTS -> null
  }
