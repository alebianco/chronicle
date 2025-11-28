# High Priority Tasks (H1-H8) - Resolution Plans

**Document Status**: Planning - Awaiting Approval  
**Created**: 2025-11-28  
**Purpose**: Comprehensive plans for resolving High Priority technical debt items

---

## Overview

This document contains detailed resolution plans for all 8 High Priority tasks identified in the project analysis. These tasks affect code quality, maintainability, and reliability.

**Total Estimated Effort**: 6-8 weeks  
**Recommended Approach**: Execute in order (H1→H8) as some have dependencies

---

# H1: Inadequate Test Coverage

**Severity**: High (Quality Assurance)  
**Effort**: 3-4 weeks (ongoing)  
**Priority**: 🥇 Start this ASAP (parallel with critical fixes)

## Problem Analysis

**Current State**:
- Unit tests: Only 2 test files (`AudiobookDetailsViewModelTest.kt`, `TrackListStateManagerTest.kt`)
- Instrumented tests: ~5 test files
- **Line 137 in build.gradle.kts disables all DebugAndroidTest tasks!**
- No repository tests
- No DAO tests  
- Most ViewModels untested

**Risk**: High regression risk, difficult to refactor safely

## Strategy

**Phased Approach** - Don't try to get 70% coverage immediately. Build gradually:

### Phase 1: Infrastructure (Week 1)
**Goal**: Set up testing infrastructure

**Tasks**:
1. Re-enable Android tests (investigate why disabled)
2. Add JaCoCo for coverage reporting
3. Create test utilities and base classes
4. Set up CI test execution
5. Create testing documentation

**Deliverables**:
```kotlin
// testShared/TestBase.kt
abstract class ViewModelTestBase {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    protected val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setupBase() {
        Dispatchers.setMain(testDispatcher)
    }
    
    @After
    fun tearDownBase() {
        Dispatchers.resetMain()
    }
}

// testShared/TestData.kt
object TestData {
    val sampleAudiobook = Audiobook(id = 1, title = "Test Book")
    val sampleTrack = MediaItemTrack(id = 1, title = "Track 1")
}
```

```gradle
// build.gradle.kts additions
plugins {
    id("jacoco")
}

tasks.withType<Test> {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
```

### Phase 2: ViewModel Tests (Week 2)
**Goal**: Cover critical ViewModels (30% coverage target)

**Priority ViewModels** (start here):
1. `LibraryViewModel` - Core feature
2. `AudiobookDetailsViewModel` - Expand existing tests
3. `CurrentlyPlayingViewModel` - Playback critical
4. `SettingsViewModel` - Many settings
5. `MainActivityViewModel` - Entry point

**Test Template**:
```kotlin
@ExperimentalCoroutinesApi
class LibraryViewModelTest : ViewModelTestBase() {
    
    private lateinit var viewModel: LibraryViewModel
    private val mockBookRepository: IBookRepository = mockk()
    private val mockCachedFileManager: ICachedFileManager = mockk()
    
    @Before
    fun setup() {
        // Setup mocks
        every { mockBookRepository.getAllBooks() } returns MutableLiveData(emptyList())
        
        viewModel = LibraryViewModel(
            mockBookRepository,
            mockCachedFileManager,
            // ... other dependencies
        )
    }
    
    @Test
    fun `when books loaded then displayed`() = runTest {
        // Given
        val books = listOf(TestData.sampleAudiobook)
        every { mockBookRepository.getAllBooks() } returns MutableLiveData(books)
        
        // When
        val result = viewModel.books.getOrAwaitValue()
        
        // Then
        assertThat(result).isEqualTo(books)
    }
    
    @Test
    fun `when sort changed then books re-sorted`() = runTest {
        // Test sort functionality
    }
    
    @Test
    fun `when download clicked then download starts`() = runTest {
        // Test download initiation
    }
}
```

### Phase 3: Repository Tests (Week 3)
**Goal**: Test data layer (45% coverage target)

**Priority Repositories**:
1. `BookRepository` - Core data
2. `TrackRepository` - Track management
3. `PlexLoginRepo` - Auth critical

**Test Pattern**:
```kotlin
@ExperimentalCoroutinesApi
class BookRepositoryTest {
    
    private lateinit var repository: BookRepository
    private lateinit var bookDao: BookDao
    private lateinit var database: BookDatabase
    
    @Before
    fun setup() {
        // In-memory database for testing
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookDao = database.bookDao
        
        repository = BookRepository(
            bookDao,
            mockPrefsRepo,
            mockPlexPrefsRepo,
            mockPlexMediaService
        )
    }
    
    @After
    fun tearDown() {
        database.close()
    }
    
    @Test
    fun `when books fetched then stored in database`() = runTest {
        // Test database operations
    }
}
```

### Phase 4: Integration Tests (Week 4)
**Goal**: Test critical flows (60% coverage target)

**Critical Flows**:
1. Login → Library → Play
2. Download → Offline Play
3. Progress sync
4. Search → Result → Play

### Phase 5: Ongoing (Beyond Week 4)
**Goal**: Maintain and improve coverage

**Practices**:
- Every new feature requires tests
- Bug fixes require regression tests
- Monthly coverage review
- Aim for 70%+ on critical paths

## Implementation Details

### Re-enable Android Tests

**Current Issue** (build.gradle.kts:137):
```kotlin
tasks.matching { it.name.contains("DebugAndroidTest") && !it.name.contains("Lint") }.configureEach {
  enabled = false
}
```

**Investigation Needed**:
1. Why were these disabled?
2. Check git history for context
3. Are they failing or too slow?

**Options**:
- A) Re-enable if no issues
- B) Fix underlying issues then re-enable
- C) Keep disabled but document why

### JaCoCo Configuration

```kotlin
jacoco {
    toolVersion = "0.8.10"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files("build/intermediates/javac/debug/classes"))
    executionData.setFrom(files("build/jacoco/testDebugUnitTest.exec"))
    
    // Exclude generated code
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/R.class",
                    "**/R\$*.class",
                    "**/BuildConfig.*",
                    "**/Manifest*.*",
                    "**/*_Factory.*",
                    "**/*_MembersInjector.*",
                    "**/Dagger*Component*.*",
                    "**/*Module_*Factory.*",
                    "**/*Binding*.*",
                    "**/databinding/*",
                    "**/android/databinding/*"
                )
            }
        })
    )
}
```

## Success Criteria

- [ ] JaCoCo configured and reporting
- [ ] Android tests re-enabled or documented
- [ ] 5+ ViewModel tests added
- [ ] 3+ Repository tests added
- [ ] CI runs tests and reports coverage
- [ ] Coverage badge in README
- [ ] Testing guide documented
- [ ] 30% coverage Week 2, 45% Week 3, 60% Week 4

## Risks & Mitigation

| Risk | Mitigation |
|------|------------|
| Tests take too long to write | Start with happy paths only |
| Flaky tests | Use proper test dispatchers |
| Mocking complexity | Create test utilities |
| CI slowdown | Parallel test execution |

## Dependencies

- None (can start immediately)
- Benefits: H4 (CI Test Execution)

---

# H2: Missing ProGuard Rules

**Severity**: High (Release Build Issues)  
**Effort**: 2-3 days  
**Priority**: 🔥 **Critical for Release Builds**

## Problem Analysis

**Current State**:
- `proguard-rules.pro` is nearly empty (only comments)
- Using R8 minification in release builds
- Libraries that NEED keep rules: Room, Retrofit, Moshi, Dagger, ExoPlayer, Fresco

**Risk**: Release builds will crash due to reflection/annotation stripping

## Implementation Plan

### ProGuard Rules to Add

```proguard
# proguard-rules.pro

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Crash reporting
-keepattributes *Annotation*

# ======== Room ========
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Room entity columns
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** DATABASE_NAME;
}

# Entities
-keep class io.github.mattpvaughn.chronicle.data.model.** { *; }
-keep class * extends io.github.mattpvaughn.chronicle.data.model.** { *; }

# DAOs
-keep interface io.github.mattpvaughn.chronicle.data.local.*Dao { *; }

# ======== Retrofit & OkHttp ========
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Retrofit service interfaces
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService { *; }
-keep interface io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService { *; }

# ======== Moshi ========
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# Moshi models
-keep class io.github.mattpvaughn.chronicle.data.sources.plex.model.** { *; }
-keepclassmembers class io.github.mattpvaughn.chronicle.data.sources.plex.model.** { *; }

# Keep @JsonClass classes
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Moshi adapters
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}
-keep class * extends com.squareup.moshi.JsonAdapter

# ======== Dagger ========
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.inject.**
-dontwarn javax.annotation.**

# Keep Dagger generated code
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **Module_** { *; }
-keep class dagger.** { *; }

# Components
-keep interface io.github.mattpvaughn.chronicle.injection.components.** { *; }

# Modules  
-keep class io.github.mattpvaughn.chronicle.injection.modules.** { *; }

# Injected classes
-keepclasseswithmembernames class * {
    @javax.inject.* <fields>;
}
-keepclasseswithmembernames class * {
    @javax.inject.* <methods>;
}

# ======== ExoPlayer / Media3 ========
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ExoPlayer specific
-keep class * implements androidx.media3.exoplayer.drm.ExoMediaDrm$Provider { *; }
-keep class * implements androidx.media3.datasource.DataSource$Factory { *; }

# Media session
-keep class android.support.v4.media.** { *; }
-keep interface android.support.v4.media.** { *; }

# ======== Fresco ========
-keep class com.facebook.fresco.** { *; }
-keep interface com.facebook.fresco.** { *; }
-keep class com.facebook.imagepipeline.** { *; }
-keep class com.facebook.drawee.** { *; }
-dontwarn com.facebook.**

# ======== Glide ========
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# ======== Kotlin ========
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# ======== Android ========
# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
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

# ======== App Specific ========
# Keep application class
-keep class io.github.mattpvaughn.chronicle.application.ChronicleApplication { *; }

# ViewModels
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory {
    <init>(...);
}

# Keep all ViewModel factories
-keep class **$Factory {
    <init>(...);
}
```

### Testing Release Builds

**Test Script**:
```bash
#!/bin/bash
# test_release_build.sh

echo "Building release APK..."
./gradlew assembleRelease

if [ $? -ne 0 ]; then
    echo "❌ Release build failed"
    exit 1
fi

echo "✅ Release build succeeded"
echo "Installing release APK..."

# Install and test manually
adb install -r app/build/outputs/apk/release/app-release.apk

echo "Test checklist:"
echo "1. [ ] App launches"
echo "2. [ ] Login works"
echo "3. [ ] Library loads"
echo "4. [ ] Playback works"
echo "5. [ ] Downloads work"
echo "6. [ ] No crashes in logcat"
```

## Success Criteria

- [ ] ProGuard rules added for all libraries
- [ ] Release build compiles
- [ ] Release build tested on device
- [ ] All features work in release
- [ ] No reflection crashes
- [ ] Documented in CONTRIBUTING.md

## Time Estimate

- Add rules: 4 hours
- Test release build: 4 hours
- Document: 1 hour
- **Total: 2 days**

---

# H3: SDK Version Mismatch

**Severity**: High (Compatibility)  
**Effort**: 2-3 days  
**Priority**: 🎯 Quick Fix

## Problem

**Documentation mismatch**:
- build.gradle.kts: `targetSdk = 34`
- copilot-instructions.md: Claims `targetSdk 36`

**Solution**: Update docs to match reality (34 is correct, 36 doesn't exist yet)

## Implementation

```markdown
// .github/copilot-instructions.md
- Android app in Kotlin, single module `:app` (minSdk 27, targetSdk 34).
```

## Testing

- [ ] Test on Android 14 device
- [ ] Review Android 14 behavior changes
- [ ] Check permissions (especially notifications)
- [ ] Verify no deprecation warnings

## Time Estimate

- Update docs: 1 hour
- Testing: 4 hours
- **Total: 1 day**

---

# H4: No CI Test Execution

**Severity**: High (CI/CD)  
**Effort**: 1-2 days  
**Priority**: 🔧 Infrastructure

## Problem

Current CI (`.github/workflows/ci.yml`):
- ✅ Runs ktlintCheck
- ✅ Runs assembleDebug
- ❓ Has test job but unclear if actually runs tests
- ❌ No test reporting
- ❌ No coverage metrics

## Implementation

```yaml
# .github/workflows/ci.yml - Update test job

test:
  name: Unit Tests
  runs-on: ubuntu-latest
  steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Cache Gradle packages
      uses: actions/cache@v4
      with:
        path: |
          ~/.gradle/caches
          ~/.gradle/wrapper
        key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
        restore-keys: |
          ${{ runner.os }}-gradle-

    - name: Grant execute permission for gradlew
      run: chmod +x gradlew

    - name: Run unit tests
      run: ./gradlew testDebugUnitTest

    - name: Generate test report
      if: always()
      run: ./gradlew jacocoTestReport

    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: test-results
        path: app/build/test-results/**/*.xml

    - name: Upload coverage report
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: coverage-report
        path: app/build/reports/jacoco/**/*

    - name: Comment PR with coverage
      if: github.event_name == 'pull_request'
      uses: madrapps/jacoco-report@v1.6.1
      with:
        paths: app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
        token: ${{ secrets.GITHUB_TOKEN }}
        min-coverage-overall: 30
        min-coverage-changed-files: 50
```

### Add Coverage Badge

```markdown
// README.md
[![CI](https://github.com/user/chronicle/actions/workflows/ci.yml/badge.svg)](https://github.com/user/chronicle/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/codecov/c/github/user/chronicle)](https://codecov.io/gh/user/chronicle)
```

## Success Criteria

- [ ] CI runs unit tests
- [ ] Test results published
- [ ] Coverage reports generated
- [ ] Coverage badge in README
- [ ] PR comments with coverage
- [ ] Tests required for merge

## Time Estimate

- Update CI config: 2 hours
- Test CI: 2 hours
- Add badge: 1 hour
- **Total: 1 day**

---

# H5: Hardcoded Dispatcher Usage

**Severity**: High (Testability)  
**Effort**: 3-4 days  
**Priority**: 🧪 Testing Infrastructure

## Problem

Direct use of `Dispatchers.IO`, `Dispatchers.Main` throughout codebase makes testing difficult.

## Solution

**Create DispatcherProvider**:

```kotlin
// util/DispatcherProvider.kt
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main = Dispatchers.Main
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
    override val unconfined = Dispatchers.Unconfined
}

class TestDispatcherProvider(
    private val testDispatcher: TestDispatcher
) : DispatcherProvider {
    override val main = testDispatcher
    override val io = testDispatcher
    override val default = testDispatcher
    override val unconfined = testDispatcher
}
```

**Inject via Dagger**:

```kotlin
// injection/modules/AppModule.kt
@Provides
@Singleton
fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
```

**Use in code**:

```kotlin
// Before
viewModelScope.launch(Dispatchers.IO) {
    // work
}

// After
viewModelScope.launch(dispatchers.io) {
    // work
}
```

## Migration Strategy

1. Create DispatcherProvider (2h)
2. Inject in ViewModels (8h)
3. Inject in Repositories (4h)
4. Update tests (4h)
5. Verify all tests pass (2h)

## Success Criteria

- [ ] DispatcherProvider created
- [ ] Injected in all components
- [ ] Tests use TestDispatcherProvider
- [ ] No direct Dispatchers.* usage
- [ ] TODO marked complete

## Time Estimate: 3-4 days

---

# H6: Delicate API Usage Without Suppression

**Severity**: High (Code Safety)  
**Effort**: 1-2 days  
**Priority**: 🔗 Related to C4

## Problem

`CachedFileManager.kt` uses delicate coroutine APIs without proper documentation or suppression.

## Solution

**Addressed by C4 (GlobalScope removal)** - When we inject ApplicationScope, the delicate API warnings will be resolved.

## Actions

1. Complete C4 first
2. Verify delicate API warnings gone
3. If any remain, add @OptIn with documentation

## Time Estimate: 1 day (after C4)

---

# H7: TODO Items in Critical Paths

**Severity**: High (Incomplete Features)  
**Effort**: 2-3 days (audit + planning)  
**Priority**: 📝 Documentation

## Problem

20+ TODO items in production code with no tracking.

## Implementation

**Phase 1: Audit (1 day)**

Create spreadsheet of all TODOs:
```bash
# Find all TODOs
grep -r "TODO" app/src/main --include="*.kt" > todos.txt
```

Categorize:
1. **Remove** - Obsolete or done
2. **Implement** - Quick fixes
3. **Track** - Create GitHub issues
4. **Document** - Explain why deferred

**Phase 2: Execute (1-2 days)**

- Remove obsolete TODOs
- Fix quick items
- Create issues for rest
- Add linting rule

**Linting Rule**:
```kotlin
// .editorconfig or ktlint config
# Require TODO format: TODO(#123): Description
[*.{kt,kts}]
# Custom rule to enforce TODO format
```

## Success Criteria

- [ ] All TODOs audited
- [ ] Obsolete TODOs removed
- [ ] Issues created for remaining
- [ ] Linting rule added
- [ ] Documentation updated

## Time Estimate: 2-3 days

---

# H8: No Accessibility Support Evident

**Severity**: High (Inclusivity)  
**Effort**: 1-2 weeks  
**Priority**: ♿ UX & Compliance

## Problem

No evidence of accessibility testing or content descriptions.

## Implementation

### Phase 1: Audit (2 days)

**Audit Checklist**:
```markdown
## Accessibility Audit

### Images & Icons
- [ ] All ImageViews have contentDescription
- [ ] Decorative images marked importantForAccessibility="no"
- [ ] Icon buttons have text alternatives

### Interactive Elements
- [ ] All buttons have labels
- [ ] Touch targets >= 48dp
- [ ] Focus order logical

### Text
- [ ] Min text size 12sp
- [ ] Supports large fonts
- [ ] High contrast ratios

### Navigation
- [ ] Works with TalkBack
- [ ] Keyboard navigation works
- [ ] Tab order correct
```

### Phase 2: Fix Critical Issues (3-4 days)

**Add Content Descriptions**:
```xml
<!-- Before -->
<ImageView
    android:id="@+id/album_art"
    android:src="@drawable/album" />

<!-- After -->
<ImageView
    android:id="@+id/album_art"
    android:src="@drawable/album"
    android:contentDescription="@string/album_art_desc" />
```

```xml
<!-- strings.xml -->
<string name="album_art_desc">Album artwork</string>
<string name="play_button_desc">Play audiobook</string>
<string name="pause_button_desc">Pause playback</string>
```

### Phase 3: Test & Document (2-3 days)

**Testing**:
1. Enable TalkBack
2. Navigate entire app
3. Test all features
4. Fix issues found
5. Document accessibility features

## Success Criteria

- [ ] All images have content descriptions
- [ ] TalkBack navigation works
- [ ] Touch targets meet guidelines
- [ ] Supports large fonts
- [ ] High contrast tested
- [ ] Accessibility documented
- [ ] CI checks added

## Time Estimate: 1-2 weeks

---

# Summary & Recommendations

## Execution Order

### Immediate (Week 1-2)
1. **H3: SDK Version** (1 day) - Quick fix
2. **H2: ProGuard Rules** (2 days) - Critical for releases
3. **H4: CI Tests** (1 day) - Infrastructure
4. **H1: Test Infrastructure** (1 week) - Start building

### Short Term (Week 3-4)
5. **H5: Dispatcher Injection** (4 days) - Benefits testing
6. **H7: TODO Audit** (3 days) - Cleanup
7. **H1: Continue** - Add tests weekly

### Medium Term (Week 5-8)
8. **H6: Delicate APIs** (1 day) - After C4
9. **H8: Accessibility** (2 weeks) - Important but can be phased
10. **H1: Ongoing** - Reach coverage goals

## Total Effort

| Task | Days | Can Parallel |
|------|------|--------------|
| H1 | 20 | Yes (ongoing) |
| H2 | 2 | No |
| H3 | 1 | Yes |
| H4 | 1 | Yes |
| H5 | 4 | No |
| H6 | 1 | After C4 |
| H7 | 3 | Yes |
| H8 | 10 | Yes (partial) |

**Sequential**: ~42 days (8.4 weeks)  
**With Parallelization**: ~30 days (6 weeks)

## Dependencies

```
C4 → H6
H1 → H4 (benefits from)
H5 → H1 (improves testing)
```

## Quick Wins

1. H3 (1 day) - Update docs
2. H4 (1 day) - Enable CI tests
3. H2 (2 days) - ProGuard rules

Start with these while planning H1 (testing) strategy.

---

## Approval Needed

**Before proceeding, please confirm**:

1. **Execution order acceptable?** (Start with H3, H2, H4, then H1)
2. **Testing strategy OK?** (Phased approach, 30%→45%→60% coverage)
3. **Can Android tests be re-enabled?** (Currently disabled - investigate why)
4. **ProGuard rules comprehensive enough?** (Review the rules I provided)
5. **Accessibility timeline OK?** (1-2 weeks, can be phased)
6. **Any tasks to prioritize differently?**

**Ready to start?** I recommend beginning with **H3** (quick doc fix) while you review plans for others.

---

*Created: 2025-11-28*  
*Status: ⏸️ Awaiting Approval*  
*Next: Execute approved tasks in recommended order*

