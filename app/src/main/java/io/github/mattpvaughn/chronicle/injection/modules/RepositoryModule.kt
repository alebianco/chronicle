package io.github.mattpvaughn.chronicle.injection.modules

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mattpvaughn.chronicle.data.local.BookRepository
import io.github.mattpvaughn.chronicle.data.local.DataStorePrefsRepo
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.TrackRepository
import io.github.mattpvaughn.chronicle.data.sources.plex.APP_NAME
import io.github.mattpvaughn.chronicle.data.sources.plex.CachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.features.download.Downloader
import io.github.mattpvaughn.chronicle.features.download.KtorDownloader
import io.github.mattpvaughn.chronicle.features.settings.licenses.GeneratedLicenseCatalogSource
import io.github.mattpvaughn.chronicle.features.settings.licenses.LicenseCatalogSource
import javax.inject.Singleton

/**
 * The repositories, split out of `AppModule` so a test can replace them.
 *
 * These are the bindings a screen test wants to fake — a screen test needs a book
 * repository returning a known list, not one backed by a real database. `@BindValue` alone cannot
 * do it: a value bound in a test is a *second* binding for the type, which Dagger rejects as a
 * duplicate rather than treating as an override.
 *
 * `@TestInstallIn` replaces this whole module, which is why it holds only the interface bindings
 * and nothing else. The other ~40 providers stay in `AppModule` and are used as they ship — a test
 * that replaced those would be testing its own graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
  @Provides
  @Singleton
  fun providePrefsRepo(prefsImpl: DataStorePrefsRepo): PrefsRepo = prefsImpl

  @Provides
  @Singleton
  fun provideTrackRepo(trackRepository: TrackRepository): ITrackRepository = trackRepository

  @Provides
  @Singleton
  fun provideBookRepo(bookRepository: BookRepository): IBookRepository = bookRepository

  @Provides
  @Singleton
  fun provideCachedFileManager(cacheManager: CachedFileManager): ICachedFileManager = cacheManager

  /**
   * The download engine, bound behind the [Downloader] seam (decision-24).
   *
   * Bound rather than injected concretely so callers depend on the interface — which is the point
   * of the seam: a future engine change replaces this one line.
   */
  @Provides
  @Singleton
  fun provideDownloader(downloader: KtorDownloader): Downloader = downloader

  /**
   * The reader for the generated third-party dependency catalogue.
   *
   * Here rather than in `AppModule` for the reason this module exists: it is a seam a screen test
   * wants to replace. The real one reads a raw resource the Gradle plugin generates, so a test that
   * did not replace it would be asserting against the build's own dependency list — which changes
   * whenever any dependency does, and would make an unrelated bump fail a UI test.
   */
  @Provides
  @Singleton
  fun provideLicenseCatalogSource(source: GeneratedLicenseCatalogSource): LicenseCatalogSource = source

  /**
   * The app's main preferences file.
   *
   * Here rather than in `AppModule` because a screen test needs a **real** fake with a working
   * listener list: a relaxed `SharedPreferences` mock drops the registration, so `preferenceFlow`
   * never emits and every assertion downstream passes against a flow that produced nothing.
   */
  @Provides
  @Singleton
  fun provideSharedPrefs(
    @ApplicationContext context: Context,
  ): SharedPreferences = context.getSharedPreferences(APP_NAME, MODE_PRIVATE)
}
