package io.github.mattpvaughn.chronicle.injection.modules

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater
import io.github.mattpvaughn.chronicle.features.player.SimpleProgressUpdater

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
  fun fragmentManager(
    @ActivityContext context: Context,
  ): FragmentManager = (context as AppCompatActivity).supportFragmentManager

  @Provides
  @ActivityScoped
  fun provideProgressUpdater(progressUpdater: SimpleProgressUpdater): ProgressUpdater = progressUpdater
}
