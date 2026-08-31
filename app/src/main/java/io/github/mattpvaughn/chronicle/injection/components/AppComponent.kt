package io.github.mattpvaughn.chronicle.injection.components

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.tonyodev.fetch2.Fetch
import dagger.Component
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.login.ChooseLibraryFragment
import io.github.mattpvaughn.chronicle.features.login.ChooseServerFragment
import io.github.mattpvaughn.chronicle.features.login.ChooseUserFragment
import io.github.mattpvaughn.chronicle.features.login.LoginFragment
import io.github.mattpvaughn.chronicle.injection.modules.AppModule
import io.github.mattpvaughn.chronicle.injection.modules.AppModule.Companion.OKHTTP_CLIENT_MEDIA
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
  fun applicationContext(): Context

  fun internalFilesDir(): File

  fun externalDeviceDirs(): List<File>

  fun sharedPrefs(): SharedPreferences

  fun trackDao(): TrackDao

  fun bookDao(): BookDao

  fun collectionsDao(): CollectionsDao

  fun moshi(): Moshi

  fun plexLoginRepo(): IPlexLoginRepo

  fun plexPrefs(): PlexPrefsRepo

  fun prefsRepo(): PrefsRepo

  fun trackRepo(): ITrackRepository

  fun librarySyncRepo(): LibrarySyncRepository

  fun collectionsRepo(): CollectionsRepository

  fun bookRepo(): IBookRepository

  fun chapterRepo(): IChapterRepository

  fun bookRepos(): BookRepository

  fun workManager(): WorkManager

  /**
   * Exposed because ServiceComponent and ActivityComponent depend on this component
   * rather than being subcomponents, so they can only inject what is declared here.
   */
  fun dispatchers(): DispatcherProvider

  fun unhandledExceptionHandler(): CoroutineExceptionHandler

  fun plexConfig(): PlexConfig

  fun plexLoginService(): PlexLoginService

  fun plexMediaService(): PlexMediaService

  fun cachedFileManager(): ICachedFileManager

  fun currentlyPlaying(): CurrentlyPlaying

  fun fetch(): Fetch

  @Named(OKHTTP_CLIENT_MEDIA)
  fun mediaOkHttpClient(): OkHttpClient

  //    fun plexMediaSource(): PlexMediaSource

  // Inject
  fun inject(chronicleApplication: ChronicleApplication)

  fun inject(loginFragment: LoginFragment)

  fun inject(chooseLibraryFragment: ChooseLibraryFragment)

  fun inject(chooseUserFragment: ChooseUserFragment)

  fun inject(chooseServerActivity: ChooseServerFragment)
}
