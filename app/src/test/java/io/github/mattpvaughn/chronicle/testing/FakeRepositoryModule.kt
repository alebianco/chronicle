package io.github.mattpvaughn.chronicle.testing

import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.injection.modules.RepositoryModule
import io.mockk.every
import io.mockk.mockk
import javax.inject.Singleton

/**
 * Replaces [RepositoryModule] for every `@HiltAndroidTest`.
 *
 * **Why a module rather than `@BindValue` per suite.** A `@BindValue` field adds a *second*
 * binding for its type, and Dagger rejects that as a duplicate — it is an addition, not an
 * override. `@TestInstallIn` is the sanctioned replacement, and it is why `RepositoryModule` was
 * split out of `AppModule`: only the bindings a test genuinely wants to fake move, so the other
 * ~40 providers are exercised as they ship.
 *
 * The fakes are deliberately **inert** rather than useful. A suite that needs a repository to
 * answer something specific stubs it there; what this module guarantees is only that no test
 * reaches a real database or the network by accident. `relaxed = true` is safe here for the same
 * reason — nothing collects a flow off these without the suite having stubbed it first, and a
 * relaxed mock of a flow-shaped member is exactly the trap this module exists to avoid.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
object FakeRepositoryModule {
  @Provides
  @Singleton
  fun providePrefsRepo(): PrefsRepo =
    mockk(relaxed = true) {
      every { libraryBookViewStyle } returns "COVER_GRID"
    }

  @Provides
  @Singleton
  fun provideTrackRepo(): ITrackRepository = mockk(relaxed = true)

  @Provides
  @Singleton
  fun provideBookRepo(): IBookRepository = mockk(relaxed = true)

  @Provides
  @Singleton
  fun provideCachedFileManager(): ICachedFileManager = mockk(relaxed = true)

  /** A real fake, not a mock — see the class KDoc. */
  @Provides
  @Singleton
  fun provideSharedPrefs(): SharedPreferences = FakePrefs()
}
