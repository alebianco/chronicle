---
id: H2-proguard-rules-plan
title: "Task H2: Missing ProGuard Rules Resolution Plan"
type: analysis
created_date: '2026-09-01'
---

# Task H2: Missing ProGuard Rules Resolution Plan

> **Archived.** Its task [[cu-45]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: H2  
**Priority**: 🟠 High (Release Build Issues)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The `proguard-rules.pro` file is **nearly empty** (only comments), yet the project uses R8 minification for release builds and depends on multiple libraries that require keep rules for reflection and annotations.

**Current State**:
- proguard-rules.pro: ~25 lines of comments, zero actual rules
- Release builds use R8 minification
- Using libraries that REQUIRE keep rules:
  - Room (entities, DAOs, annotations)
  - Retrofit (service interfaces, annotations)
  - Moshi (JSON models, adapters)
  - Dagger (generated code, injected classes)
  - ExoPlayer/Media3 (media handling)
  - Fresco (image loading)
  - Kotlin coroutines

**Risk**: 
- ❌ Release builds **WILL crash** due to stripped reflection/annotations
- ❌ JSON deserialization failures
- ❌ Dependency injection failures
- ❌ Database query failures
- ❌ Difficult to debug minified crashes

---

## Solution Strategy

Add comprehensive ProGuard/R8 rules covering all libraries and app-specific code that relies on reflection or annotations.

---

## Implementation Plan

### Phase 1: Add Comprehensive ProGuard Rules (4 hours)
**Risk**: Low

**Create complete proguard-rules.pro**:

```proguard
# proguard-rules.pro
# Chronicle Audiobook Player - ProGuard Rules

# ============================================================
# General Android & Debugging
# ============================================================

# Keep line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations for crash reporting
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================================
# Room Database
# ============================================================

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Room entity columns
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** DATABASE_NAME;
}

# Keep all entity classes
-keep class io.github.mattpvaughn.chronicle.data.model.** { *; }

# Keep all DAO interfaces
-keep interface io.github.mattpvaughn.chronicle.data.local.*Dao { *; }
-keep class * implements io.github.mattpvaughn.chronicle.data.local.*Dao { *; }

# ============================================================
# Retrofit & OkHttp
# ============================================================

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Retrofit interface methods
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep Retrofit service interfaces
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService { *; }
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService { *; }

# Retrofit uses generic type information
-keepattributes Signature
-keep class retrofit2.** { *; }

# ============================================================
# Moshi JSON Library
# ============================================================

-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# Keep all Plex API models
-keep class io.github.mattpvaughn.chronicle.data.sources.plex.model.** { *; }
-keepclassmembers class io.github.mattpvaughn.chronicle.data.sources.plex.model.** { *; }

# Keep @JsonClass annotated classes
-keep @com.squareup.moshi.JsonClass class * { *; }

# Keep Moshi adapters
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Keep generated Moshi adapters
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}
-keep class * extends com.squareup.moshi.JsonAdapter

# ============================================================
# Dagger Dependency Injection
# ============================================================

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.inject.**
-dontwarn javax.annotation.**

# Keep Dagger generated code
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **Module_** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }

# Keep all Dagger components
-keep interface io.github.mattpvaughn.chronicle.injection.components.** { *; }
-keep class io.github.mattpvaughn.chronicle.injection.components.** { *; }

# Keep all Dagger modules
-keep class io.github.mattpvaughn.chronicle.injection.modules.** { *; }

# Keep injected classes
-keepclasseswithmembernames class * {
    @javax.inject.* <fields>;
}
-keepclasseswithmembernames class * {
    @javax.inject.* <methods>;
}
-keepclasseswithmembernames class * {
    @javax.inject.Inject <init>(...);
}

# ============================================================
# ExoPlayer / Media3
# ============================================================

-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep ExoPlayer extension classes
-keep class * implements androidx.media3.exoplayer.drm.ExoMediaDrm$Provider { *; }
-keep class * implements androidx.media3.datasource.DataSource$Factory { *; }

# Keep Media Session support
-keep class android.support.v4.media.** { *; }
-keep interface android.support.v4.media.** { *; }
-keepclassmembers class android.support.v4.media.** { *; }

# Media metadata
-keep class android.support.v4.media.MediaMetadataCompat { *; }
-keep class android.support.v4.media.session.PlaybackStateCompat { *; }

# ============================================================
# Fresco Image Loading
# ============================================================

-keep class com.facebook.fresco.** { *; }
-keep interface com.facebook.fresco.** { *; }
-keep class com.facebook.imagepipeline.** { *; }
-keep class com.facebook.drawee.** { *; }
-dontwarn com.facebook.**

# Keep Fresco native libraries
-keep,allowobfuscation @interface com.facebook.proguard.annotations.DoNotStrip
-keep,allowobfuscation @interface com.facebook.proguard.annotations.KeepGettersAndSetters
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
    @com.facebook.proguard.annotations.KeepGettersAndSetters *;
}

# ============================================================
# Glide Image Loading
# ============================================================

-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# ============================================================
# Kotlin & Coroutines
# ============================================================

-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Keep Kotlin metadata
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin Result
-keep class com.github.michaelbull.result.** { *; }

# ============================================================
# Android Framework
# ============================================================

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# App Specific
# ============================================================

# Keep Application class
-keep class io.github.mattpvaughn.chronicle.application.ChronicleApplication { *; }
-keep class io.github.mattpvaughn.chronicle.application.MainActivity { *; }

# Keep all ViewModels
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# Keep ViewModel factories
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory {
    <init>(...);
}
-keep class **$Factory {
    <init>(...);
}

# Keep all Fragments
-keep class * extends androidx.fragment.app.Fragment { *; }

# Keep custom views
-keep class io.github.mattpvaughn.chronicle.views.** { *; }

# Keep data models
-keep class io.github.mattpvaughn.chronicle.data.model.** { *; }

# Keep features
-keep class io.github.mattpvaughn.chronicle.features.** { *; }

# ============================================================
# WorkManager
# ============================================================

-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep WorkManager workers
-keep class io.github.mattpvaughn.chronicle.features.download.DownloadNotificationWorker { *; }
-keep class io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker { *; }

# ============================================================
# Fetch Download Library
# ============================================================

-keep class com.tonyodev.fetch2.** { *; }
-keep interface com.tonyodev.fetch2.** { *; }

# ============================================================
# Timber Logging
# ============================================================

-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }

# ============================================================
# Billing Library
# ============================================================

-keep class com.android.billingclient.api.** { *; }

# ============================================================
# Debug & Testing (remove in production)
# ============================================================

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

---

### Phase 2: Test Release Build (4 hours)
**Risk**: Medium

**Test Script** (`test_release_build.sh`):

```bash
#!/bin/bash
# Test release build script

set -e

echo "========================================="
echo "Chronicle Release Build Test"
echo "========================================="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Step 1: Clean build${NC}"
./gradlew clean

echo -e "${YELLOW}Step 2: Building release APK...${NC}"
./gradlew assembleRelease

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Release build FAILED${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Release build succeeded${NC}"

# Check APK exists
APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ APK not found at $APK_PATH${NC}"
    exit 1
fi

# Show APK size
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo -e "${GREEN}APK size: $APK_SIZE${NC}"

# Check if device connected
if ! adb devices | grep -q "device$"; then
    echo -e "${YELLOW}⚠️  No device connected. Skipping installation.${NC}"
    echo -e "${YELLOW}Manual testing required.${NC}"
    exit 0
fi

echo -e "${YELLOW}Step 3: Installing release APK...${NC}"
adb install -r "$APK_PATH"

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Installation FAILED${NC}"
    exit 1
fi

echo -e "${GREEN}✅ APK installed successfully${NC}"

echo ""
echo "========================================="
echo "Manual Testing Checklist:"
echo "========================================="
echo "1. [ ] App launches (no crash on start)"
echo "2. [ ] Login works (OAuth flow)"
echo "3. [ ] Library loads (books display)"
echo "4. [ ] Book details open"
echo "5. [ ] Playback works"
echo "6. [ ] Downloads work"
echo "7. [ ] Settings accessible"
echo "8. [ ] No crashes in logcat"
echo ""
echo "Monitor logcat with:"
echo "  adb logcat | grep Chronicle"
```

**Testing Checklist**:
- [ ] Build completes without errors
- [ ] APK installs on device
- [ ] App launches (cold start)
- [ ] Login flow works (OAuth)
- [ ] User selection works
- [ ] Server connection works
- [ ] Library loads and displays books
- [ ] Book details screen works
- [ ] Playback starts and plays
- [ ] Seek/pause/resume works
- [ ] Download a book
- [ ] Play downloaded book offline
- [ ] Settings open and save
- [ ] No crashes in logcat
- [ ] No "ClassNotFoundException" errors
- [ ] No "MethodNotFoundException" errors

---

### Phase 3: Document & Update Guidelines (1 hour)
**Risk**: Low

**Add to CONTRIBUTING.md**:

```markdown
## Release Builds & ProGuard

Chronicle uses R8 code shrinking and obfuscation for release builds.

### ProGuard Rules

All ProGuard rules are in `app/proguard-rules.pro`. These rules are **critical** for release builds.

**When to update ProGuard rules:**
- Adding a new library that uses reflection
- Adding new data models for JSON/database
- Adding new Dagger components or modules
- If release build crashes but debug works

### Testing Release Builds

Before any release, test the release APK:

```bash
./test_release_build.sh
```

**Test checklist:**
1. Build compiles
2. APK installs
3. App launches
4. Login works
5. All major features work
6. No crashes in logcat

### Common ProGuard Issues

**Problem**: Release build crashes with `ClassNotFoundException`  
**Solution**: Add `-keep` rule for that class

**Problem**: JSON parsing fails in release  
**Solution**: Add `-keep` rule for model classes

**Problem**: Dagger injection fails  
**Solution**: Check Dagger component/module keep rules

### R8 Full Mode

We currently use R8 in compatibility mode. To enable full mode:
- Remove `-dontoptimize` if present
- Test thoroughly (full mode is more aggressive)
```

---

## Success Criteria

### Must Have ✅:
1. [ ] Comprehensive ProGuard rules added
2. [ ] Release build compiles successfully
3. [ ] Release APK installs on device
4. [ ] All major features work in release
5. [ ] No reflection-related crashes
6. [ ] Test script created
7. [ ] Documentation updated

### Should Have ✅:
1. [ ] Release build tested on multiple devices
2. [ ] Release build tested on different Android versions
3. [ ] APK size compared (before/after if changing)
4. [ ] Crash reporting configured to catch issues

### Nice to Have 🎯:
1. [ ] Automated release build testing in CI
2. [ ] ProGuard mapping file uploaded for crash deobfuscation
3. [ ] R8 full mode explored

---

## Rollback Plan

If release build has issues after adding rules:

1. **Quick rollback**: Revert proguard-rules.pro to original (empty)
2. **Selective rollback**: Comment out problematic sections
3. **Debug**: Add `-printconfiguration` to see all applied rules

---

## Common ProGuard Pitfalls

### Issue: JSON Deserialization Fails
**Symptom**: Works in debug, fails in release with parsing errors  
**Cause**: Data model classes stripped  
**Fix**: Add `-keep` for model package

### Issue: Dagger Can't Find Component
**Symptom**: "No component found" crash in release  
**Cause**: Generated Dagger code stripped  
**Fix**: Keep Dagger generated classes

### Issue: Room Queries Fail
**Symptom**: Database queries crash in release  
**Cause**: DAO or Entity stripped  
**Fix**: Keep Room annotations and interfaces

---

## Dependencies

**Depends On**: None

**Blocks**: Safe release builds

**Blocked By**: None

---

## Estimated Effort Breakdown

| Phase | Task | Time |
|-------|------|------|
| 1 | Add ProGuard rules | 4h |
| 2 | Test release build | 4h |
| 3 | Document | 1h |
| **Total** | | **9h (1-2 days)** |

---

## Approval Checklist

Before proceeding:

- [ ] **Rules comprehensive**: Review provided ProGuard rules
- [ ] **Testing acceptable**: Manual testing checklist OK
- [ ] **Can build release**: Have signing keys/setup
- [ ] **Test device available**: Can test on real device
- [ ] **Timeline OK**: 1-2 days acceptable

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/H2-proguard-rules`
2. ✅ Add ProGuard rules to proguard-rules.pro
3. ✅ Test release build locally
4. ✅ Create test script
5. ✅ Update documentation
6. ✅ PR with release build proof

---

**This is critical for production releases** - Without proper ProGuard rules, release builds will crash in production!

---

*Created: 2025-11-28*  
*Owner: Engineering Team*  
*Estimated Completion: 1-2 days*  
*Reviewer: Tech Lead / Release Manager*

