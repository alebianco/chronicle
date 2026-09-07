package io.github.mattpvaughn.chronicle.application

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What survives of the ViewModel-factory guards after the Hilt migration.
 *
 * **The factory/ViewModel agreement test is retired, not deleted by accident.** It existed because
 * a ViewModel and its hand-written `Factory` declared the same dependency list *twice* and only
 * the call between them was compiler-checked, so a `Factory` that quietly stopped taking a
 * parameter still compiled. `@HiltViewModel` removes the second list entirely — Dagger resolves
 * the constructor itself and fails the build on a missing binding — so the hazard the test
 * described cannot occur. A guard kept past the hazard it guards is noise.
 *
 * The exception-handler check below is unrelated to factories and still applies.
 */
class ViewModelFactoryTest {
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
