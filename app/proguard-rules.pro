# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Chronicle Audiobook Player - ProGuard/R8 Rules

# Keep line numbers and source file for better stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations and signatures
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============= Room =============
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-keep class io.github.mattpvaughn.chronicle.data.model.** { *; }
-keep interface io.github.mattpvaughn.chronicle.data.local.*Dao { *; }
-keep class * implements io.github.mattpvaughn.chronicle.data.local.*Dao { *; }

# ============= Retrofit & OkHttp =============
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Retrofit reads annotations off the service interfaces reflectively; the
# implementations behind them do not need keeping.
# Note: there is no `PlexService` interface despite the file name — PlexService.kt
# declares PlexLoginService and PlexMediaService. The old rule kept a phantom class.
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService { *; }
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService { *; }

# ============= Moshi =============
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keep class **JsonAdapter { *; }
-keep class * extends com.squareup.moshi.JsonAdapter
-keep class io.github.mattpvaughn.chronicle.data.sources.plex.model.** { *; }

# ============= Dagger 2 =============
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.inject.**
-dontwarn javax.annotation.**
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **Module_** { *; }
-keep interface io.github.mattpvaughn.chronicle.injection.components.** { *; }
-keep class io.github.mattpvaughn.chronicle.injection.components.** { *; }
-keep class io.github.mattpvaughn.chronicle.injection.modules.** { *; }
-keepclasseswithmembernames class * { @javax.inject.* <fields>; }
-keepclasseswithmembernames class * { @javax.inject.* <methods>; }
-keepclasseswithmembernames class * { @javax.inject.Inject <init>(...); }

# ============= Media3 / ExoPlayer =============
# Media3 ships its own consumer ProGuard rules; a blanket keep here pinned ~700
# extractor/renderer classes that R8 can otherwise strip per-format.
-dontwarn androidx.media3.**
-keep class android.support.v4.media.** { *; }
-keep interface android.support.v4.media.** { *; }
-keepclassmembers class android.support.v4.media.** { *; }
-keep class android.support.v4.media.MediaMetadataCompat { *; }
-keep class android.support.v4.media.session.PlaybackStateCompat { *; }

# ============= Coil =============
# Coil is Kotlin-first and ships its own rules; only the OkHttp fetcher wiring
# is reflection-adjacent enough to be worth naming here.
-dontwarn coil3.**

# ============= Kotlin & Coroutines =============
# A blanket `-keep class kotlin.** { *; }` pinned ~1845 kotlin.reflect.jvm.internal
# classes that nothing here reflects over. Keep only the metadata R8 and Moshi
# actually read.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
-keep class com.github.michaelbull.result.** { *; }

# ============= Android Framework Patterns =============
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keepclasseswithmembernames class * { native <methods>; }

# ============= App Specific =============
-keep class io.github.mattpvaughn.chronicle.application.ChronicleApplication { *; }
-keep class io.github.mattpvaughn.chronicle.application.MainActivity { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { <init>(...); }
-keep class **$Factory { <init>(...); }
# Fragments are instantiated by name by the framework, so their no-arg
# constructor must survive — but their members need not.
-keep class * extends androidx.fragment.app.Fragment { <init>(...); }
# Custom views are inflated from XML by name, with the (Context, AttributeSet)
# constructor.
-keep class * extends android.view.View { <init>(android.content.Context, android.util.AttributeSet); }
# data.model is kept by the Room section above; blanket keeps on features.** and
# views.** exempted 572 app classes from R8 for no reason.

# ============= WorkManager =============
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# ============= Fetch (Download) =============
-keep class com.tonyodev.fetch2.** { *; }
-keep interface com.tonyodev.fetch2.** { *; }

# ============= Timber =============
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }

# ============= Release Logging Removal =============
-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }
-assumenosideeffects class timber.log.Timber { public static *** d(...); public static *** v(...); public static *** i(...); }
