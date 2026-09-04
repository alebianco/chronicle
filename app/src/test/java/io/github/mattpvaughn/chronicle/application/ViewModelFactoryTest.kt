package io.github.mattpvaughn.chronicle.application

import androidx.lifecycle.ViewModel
import io.github.mattpvaughn.chronicle.features.browse.BrowseViewModel
import io.github.mattpvaughn.chronicle.features.collections.CollectionsViewModel
import io.github.mattpvaughn.chronicle.features.home.HomeViewModel
import io.github.mattpvaughn.chronicle.features.library.LibraryViewModel
import io.github.mattpvaughn.chronicle.features.login.ChooseServerViewModel
import io.github.mattpvaughn.chronicle.features.login.ChooseUserViewModel
import io.github.mattpvaughn.chronicle.features.login.LoginViewModel
import io.github.mattpvaughn.chronicle.features.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Every ViewModel's `Factory` supplies everything its ViewModel asks for.
 *
 * This is the failure mode cu-33 introduced the opportunity for. A ViewModel and its `Factory`
 * declare the same dependency list *twice*, and the compiler only checks the call between them —
 * so a `Factory` that quietly stopped taking a parameter, and passed a default or a stale field
 * instead, would still compile. Nine ViewModels grew a parameter in this task; asserting the two
 * lists agree is cheap and catches the copy that drifts.
 *
 * Reflective on purpose: naming the parameters would just be a third copy of the same list.
 */
class ViewModelFactoryTest {
  private fun assertFactoryMatches(
    viewModel: Class<out ViewModel>,
    factory: Class<*>,
  ) {
    val vmParams =
      viewModel.kotlin.primaryConstructor
        ?.parameters
        ?.mapNotNull { it.name }
        ?.toSet()
        ?: error("${viewModel.simpleName} has no primary constructor")
    val factoryParams =
      factory.kotlin.primaryConstructor
        ?.parameters
        ?.mapNotNull { it.name }
        ?.toSet()
        ?: error("${factory.simpleName} has no primary constructor")

    assertEquals(
      "${factory.simpleName} does not supply everything ${viewModel.simpleName} asks for. " +
        "A ViewModel and its Factory declare the same list twice and only the call between " +
        "them is compiler-checked, so the two can drift.",
      emptySet<String>(),
      vmParams - factoryParams,
    )
  }

  @Test
  fun `every factory supplies its view model's constructor parameters`() {
    // The `inputAudiobook` on AudiobookDetailsViewModel is a `lateinit` set on the Factory rather
    // than a constructor parameter, and CurrentlyPlaying/MainActivity's factories are covered by
    // their own tests, so this lists the ones a carve touched and nothing more.
    assertFactoryMatches(HomeViewModel::class.java, HomeViewModel.Factory::class.java)
    assertFactoryMatches(LibraryViewModel::class.java, LibraryViewModel.Factory::class.java)
    assertFactoryMatches(SettingsViewModel::class.java, SettingsViewModel.Factory::class.java)
    assertFactoryMatches(CollectionsViewModel::class.java, CollectionsViewModel.Factory::class.java)
    assertFactoryMatches(LoginViewModel::class.java, LoginViewModel.Factory::class.java)
    assertFactoryMatches(ChooseUserViewModel::class.java, ChooseUserViewModel.Factory::class.java)
    assertFactoryMatches(ChooseServerViewModel::class.java, ChooseServerViewModel.Factory::class.java)
    assertFactoryMatches(BrowseViewModel::class.java, BrowseViewModel.Factory::class.java)
    assertFactoryMatches(MainActivityViewModel::class.java, MainActivityViewModel.Factory::class.java)
  }

  /**
   * No ViewModel takes a `CoroutineExceptionHandler` it does not use, and none launches without
   * one.
   *
   * The handler is the parameter this task added to nine ViewModels; a new one written by copying
   * an existing file would inherit the parameter and quietly not pass it to `launch`, which reads
   * as correct and silently drops every failure in that coroutine.
   */
  @Test
  fun `a view model taking an exception handler actually installs it`() {
    val sourceDir = java.io.File("src/main/java/io/github/mattpvaughn/chronicle")
    val offenders =
      sourceDir.walkTopDown()
        .filter { it.extension == "kt" && it.name.endsWith("ViewModel.kt") }
        .filter { it.readText().contains("exceptionHandler: CoroutineExceptionHandler") }
        .filterNot { it.readText().contains("launch(exceptionHandler)") }
        .map { it.name }
        .sorted()
        .toList()

    assertEquals(
      "a ViewModel takes an exception handler but never passes it to launch — every failure " +
        "in its coroutines would be dropped silently",
      emptyList<String>(),
      offenders,
    )
  }
}
