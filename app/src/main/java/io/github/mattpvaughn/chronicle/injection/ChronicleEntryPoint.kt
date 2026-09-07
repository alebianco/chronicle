package io.github.mattpvaughn.chronicle.injection

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import io.ktor.client.HttpClient
import java.io.File
import javax.inject.Named

/**
 * Graph access for code the framework constructs, where constructor injection is impossible.
 *
 * **This is not a service locator, and the distinction is the point**. `Injector`
 * was: a global `Injector.get()` reachable from anywhere, which made any class that used it
 * unconstructable in a unit test — `ChronicleApplication.get()` is `INSTANCE!!`, so the first line
 * touching it threw NPE. `ServiceLocatorUsageTest` was written to keep it from spreading.
 *
 * An `@EntryPoint` is narrow instead of global: it names exactly which bindings may be reached this
 * way, it needs a `Context` at the call site rather than a static singleton, and it is the sanctioned
 * escape hatch for the two places that genuinely have no constructor — the **debug hooks**, which
 * the framework invokes statically from an intent, and the `MockPlexMode` provisioning that runs
 * before any component exists.
 *
 * Nothing in `app/src/main` outside this file should use it. Everything else takes its
 * dependencies as constructor parameters.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChronicleEntryPoint {
  fun plexPrefs(): PlexPrefsRepo

  fun prefsRepo(): PrefsRepo

  fun plexConfig(): PlexConfig

  fun plexLoginRepo(): IPlexLoginRepo

  fun externalDeviceDirs(): List<File>

  @Named(AppModule.OKHTTP_CLIENT_MEDIA)
  fun mediaHttpClient(): HttpClient
}

/** Reaches [ChronicleEntryPoint] from a context. See that interface for when this is legitimate. */
fun Context.chronicleGraph(): ChronicleEntryPoint = EntryPointAccessors.fromApplication(applicationContext, ChronicleEntryPoint::class.java)
