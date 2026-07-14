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
-keep class retrofit2.** { *; }
# Service interfaces (adjust paths if different)
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexService { *; }
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.** { *; }

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
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class android.support.v4.media.** { *; }
-keep interface android.support.v4.media.** { *; }
-keepclassmembers class android.support.v4.media.** { *; }
-keep class android.support.v4.media.MediaMetadataCompat { *; }
-keep class android.support.v4.media.session.PlaybackStateCompat { *; }

# ============= Fresco =============
-keep class com.facebook.fresco.** { *; }
-keep interface com.facebook.fresco.** { *; }
-keep class com.facebook.imagepipeline.** { *; }
-keep class com.facebook.drawee.** { *; }
-dontwarn com.facebook.**
-keep,allowobfuscation @interface com.facebook.proguard.annotations.DoNotStrip
-keep,allowobfuscation @interface com.facebook.proguard.annotations.KeepGettersAndSetters
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
    @com.facebook.proguard.annotations.KeepGettersAndSetters *;
}

# ============= Glide =============
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }

# ============= Kotlin & Coroutines =============
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }
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
-keep class * extends androidx.fragment.app.Fragment { *; }
-keep class io.github.mattpvaughn.chronicle.views.** { *; }
-keep class io.github.mattpvaughn.chronicle.features.** { *; }
-keep class io.github.mattpvaughn.chronicle.data.model.** { *; }

# ============= WorkManager =============
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# ============= Fetch (Download) =============
-keep class com.tonyodev.fetch2.** { *; }
-keep interface com.tonyodev.fetch2.** { *; }

# ============= Timber =============
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }

# ============= Billing =============
-keep class com.android.billingclient.api.** { *; }

# ============= Release Logging Removal =============
-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }
-assumenosideeffects class timber.log.Timber { public static *** d(...); public static *** v(...); public static *** i(...); }
