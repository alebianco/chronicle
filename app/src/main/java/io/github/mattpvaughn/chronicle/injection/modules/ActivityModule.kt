package io.github.mattpvaughn.chronicle.injection.modules

import android.content.ComponentName
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater
import io.github.mattpvaughn.chronicle.features.player.SimpleProgressUpdater
import io.github.mattpvaughn.chronicle.util.ServiceUtils
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

/**
 * Activity-scoped bindings (cu-185).
 *
 * An `object` taking `@ActivityContext` rather than a class holding the activity: Hilt builds the
 * module, so there is no constructor to pass one to. The cast to [AppCompatActivity] is safe and
 * checked — this app has exactly one activity, and the alternative is threading a second binding
 * through every consumer for no gain.
 */
@Module
@InstallIn(ActivityComponent::class)
object ActivityModule {
  @Provides
  @ActivityScoped
  fun activity(
    @ActivityContext context: Context,
  ): AppCompatActivity = context as AppCompatActivity

  @Provides
  @ActivityScoped
  fun coroutineScope(
    @ActivityContext context: Context,
  ): CoroutineScope = (context as AppCompatActivity).lifecycleScope

  @Provides
  @ActivityScoped
  fun fragmentManager(
    @ActivityContext context: Context,
  ): FragmentManager = (context as AppCompatActivity).supportFragmentManager

  @Provides
  @ActivityScoped
  fun provideProgressUpdater(progressUpdater: SimpleProgressUpdater): ProgressUpdater = progressUpdater

  @Provides
  @ActivityScoped
  fun provideBroadcastManager(
    @ActivityContext context: Context,
  ): LocalBroadcastManager = LocalBroadcastManager.getInstance(context)

  /**
   * Binds to the media service, reconnecting to one that is already running.
   *
   * Uses the **application** context, not the activity's: the connection outlives a rotation, and
   * holding an activity context in a binding that survives it is a leak.
   */
  @Provides
  @ActivityScoped
  fun mediaServiceConnection(
    @ApplicationContext context: Context,
  ): MediaServiceConnection {
    val conn =
      MediaServiceConnection(
        context,
        ComponentName(context, MediaPlayerService::class.java),
      )
    val doesServiceExist =
      ServiceUtils.isServiceRunning(
        context,
        MediaPlayerService::class.java,
      )
    Timber.i("Connecting to existing service? $doesServiceExist")
    if (doesServiceExist) {
      conn.connect()
    }
    return conn
  }
}
