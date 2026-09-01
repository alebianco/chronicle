---
id: C2-kapt-to-ksp-migration-plan
title: "Task C2: KAPT to KSP Migration Plan"
type: analysis
created_date: '2026-09-01'
---

# Task C2: KAPT to KSP Migration Plan

> **Archived.** Its task [[cu-8]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C2  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The project currently uses the deprecated KAPT (Kotlin Annotation Processing Tool) for code generation instead of the modern KSP (Kotlin Symbol Processing) API. This creates several issues:

1. **Build Performance**: KAPT is 2-4x slower than KSP
2. **Future Compatibility**: KAPT may be removed in future Kotlin versions
3. **Documentation Mismatch**: `.github/copilot-instructions.md` claims KSP is used, but build files show KAPT
4. **Suboptimal Performance**: Moshi codegen was removed, forcing reflection-based adapters (slower)

---

## Current State Analysis

### What I Found:

#### 1. **Build Configuration** (`app/build.gradle.kts`):
```kotlin
plugins {
  id("kotlin-kapt")  // ❌ Using KAPT
}

kapt {
  arguments {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
  }
}

dependencies {
  // Room - using KAPT ❌
  kapt(libs.room.compiler)
  
  // Dagger - using KAPT ❌
  kapt(libs.dagger.compiler)
  
  // Moshi - codegen REMOVED, using reflection ❌
  // Comment says: "Removed moshi-codegen KAPT processor - deprecated for Kotlin 2.x"
  // This is INCORRECT - KSP works fine with Kotlin 2.x
  
  // Test dependencies also using KAPT ❌
  kaptTest(libs.dagger.compiler)
  kaptAndroidTest(libs.dagger.compiler)
}
```

#### 2. **Root Build Configuration** (`build.gradle.kts`):
- Contains complex KAPT task configuration via reflection
- Adds JDK module access flags for KAPT
- This entire section can be removed with KSP

#### 3. **Version Catalog** (`gradle/libs.versions.toml`):
```toml
ksp = "2.1.20-1.0.0"  # ✅ Already defined!
kotlin = "2.1.20"

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }  # ✅ Already defined!
```
**Good news**: KSP plugin is already in the version catalog, just not applied!

#### 4. **Annotation Processing Usage**:

**Room** (4 entities, 4 DAOs):
- `@Entity`: Audiobook, MediaItemTrack, Chapter, Collection
- `@Dao`: BookDao, TrackDao, ChapterDao, CollectionDao
- Schema location: `app/schemas/`
- **KSP Support**: ✅ Excellent (Room 2.6+ has full KSP support)

**Dagger 2** (4 components, 4 modules):
- `@Component`: AppComponent, ActivityComponent, ServiceComponent, UITestAppComponent
- `@Module`: AppModule, ActivityModule, ServiceModule, UITestAppModule
- **KSP Support**: ✅ Experimental but stable (Dagger 2.48+)

**Moshi** (15 JSON classes):
- 15 classes with `@JsonClass(generateAdapter = true)`
- Currently using reflection (slower, larger APK)
- **KSP Support**: ✅ Excellent (moshi-kotlin-codegen works with KSP)

---

## Risk Assessment

### Technical Risks:
| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Dagger KSP experimental issues | Medium | High | Extensive testing, rollback plan |
| Generated code differences | Low | Medium | Compare generated code before/after |
| Build script issues | Low | Low | Incremental migration |
| Test failures | Medium | High | Run full test suite |
| Schema location changes | Low | Medium | Verify Room schemas generated correctly |

### User Impact:
- ✅ **Zero user impact** - this is a build-time change only
- ✅ **No API changes** - generated code remains functionally identical
- ✅ **No runtime changes** - no behavior differences

### Benefits:
1. **Build Speed**: 2-4x faster clean builds
2. **Incremental Builds**: Much faster incremental compilation
3. **Memory Usage**: Lower build memory consumption
4. **Future-Proof**: KSP is the official replacement for KAPT
5. **APK Size**: Re-enabling Moshi codegen will reduce APK size vs reflection
6. **Maintenance**: Simpler build configuration

---

## Migration Strategy

### Approach: **Incremental Migration with Validation**

We'll migrate in stages, validating each step:
1. **Stage 1**: Add KSP plugin (alongside KAPT)
2. **Stage 2**: Migrate Moshi (lowest risk)
3. **Stage 3**: Migrate Room (medium risk)
4. **Stage 4**: Migrate Dagger (highest risk)
5. **Stage 5**: Remove KAPT entirely

This allows us to catch issues early and rollback specific processors if needed.

---

## Implementation Plan

### Phase 1: Preparation & Setup (Day 1 - Morning, 2-3 hours)
**Risk**: Low

#### Tasks:
- [x] 1.1. Research current KSP compatibility for all libraries
- [ ] 1.2. Backup current generated code for comparison
- [ ] 1.3. Document current build times (baseline)
- [ ] 1.4. Apply KSP plugin (without removing KAPT yet)
- [ ] 1.5. Create feature branch

**Implementation**:

```kotlin
// app/build.gradle.kts
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("kotlin-parcelize")
  id("kotlin-kapt")  // Keep temporarily
  alias(libs.plugins.ksp)  // ✅ ADD THIS
  id("com.google.android.gms.oss-licenses-plugin")
}
```

**Validation**:
- [ ] Project syncs successfully
- [ ] No new errors in build output
- [ ] Can still build with KAPT

---

### Phase 2: Migrate Moshi to KSP (Day 1 - Afternoon, 2-3 hours)
**Risk**: Low (Moshi has excellent KSP support)

#### Why First?
- Moshi is currently NOT using codegen (using reflection)
- Adding KSP codegen will **improve** performance
- No existing KAPT dependency to conflict
- Good test case for KSP setup

#### Tasks:
- [ ] 2.1. Add moshi-kotlin-codegen to dependencies with KSP
- [ ] 2.2. Build and verify Moshi adapters are generated
- [ ] 2.3. Test JSON serialization/deserialization
- [ ] 2.4. Verify network calls work correctly
- [ ] 2.5. Measure APK size difference

**Implementation**:

```kotlin
// app/build.gradle.kts
dependencies {
  implementation(libs.moshi)
  ksp(libs.moshi.codegen)  // ✅ ADD THIS - was removed, now back with KSP
  
  // ... rest of dependencies
}
```

**Validation Tests**:
- [ ] Login flow works (uses Moshi for OAuth)
- [ ] Server selection works (uses Moshi for server list)
- [ ] Library loading works (uses Moshi for media items)
- [ ] Network requests succeed
- [ ] JSON parsing errors caught properly

**Expected Outcome**:
- ✅ Generated adapters in `build/generated/ksp/`
- ✅ Faster JSON parsing (codegen vs reflection)
- ✅ Smaller APK size (no reflection)

---

### Phase 3: Migrate Room to KSP (Day 2 - Morning, 3-4 hours)
**Risk**: Low-Medium (Room has mature KSP support)

#### Tasks:
- [ ] 3.1. Configure KSP for Room schema location
- [ ] 3.2. Change Room compiler from KAPT to KSP
- [ ] 3.3. Build and verify DAOs are generated
- [ ] 3.4. Verify database schemas in correct location
- [ ] 3.5. Run database tests
- [ ] 3.6. Test migrations

**Implementation**:

```kotlin
// app/build.gradle.kts
android {
  // ... existing config
}

// Remove kapt block, replace with KSP configuration
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.expandProjection", "true")
}

dependencies {
  implementation(libs.room.runtime)
  ksp(libs.room.compiler)  // ✅ Changed from kapt
  implementation(libs.room.ktx)
  
  // ... rest
}
```

**Validation Tests**:
- [ ] App launches without crashes
- [ ] Database opens successfully
- [ ] All queries work (books, tracks, chapters, collections)
- [ ] Insert/Update/Delete operations work
- [ ] LiveData observers receive updates
- [ ] Database migrations work
- [ ] Schema files generated in `app/schemas/`

**Schema Verification**:
```bash
# Verify schemas are still generated correctly
ls -la app/schemas/io.github.mattpvaughn.chronicle.data.local.BookDatabase/
ls -la app/schemas/io.github.mattpvaughn.chronicle.data.local.TrackDatabase/
```

---

### Phase 4: Migrate Dagger to KSP (Day 2 - Afternoon, 4-5 hours)
**Risk**: Medium (Dagger KSP is experimental but stable)

#### Why Last?
- Dagger is most complex (components, modules, scopes)
- Touches entire dependency graph
- Most critical for app functionality
- Experimental KSP support (though stable in practice)

#### Tasks:
- [ ] 4.1. Research Dagger KSP known issues
- [ ] 4.2. Change Dagger compiler to KSP
- [ ] 4.3. Build and verify all components generated
- [ ] 4.4. Test dependency injection throughout app
- [ ] 4.5. Test all features (login, playback, download, etc.)
- [ ] 4.6. Run instrumented tests

**Implementation**:

```kotlin
// app/build.gradle.kts
dependencies {
  implementation(libs.dagger)
  ksp(libs.dagger.compiler)  // ✅ Changed from kapt
  
  // Test dependencies
  testImplementation(libs.dagger)
  ksp(libs.dagger.compiler)  // ✅ Changed from kaptTest
  
  androidTestImplementation(libs.dagger)
  ksp(libs.dagger.compiler)  // ✅ Changed from kaptAndroidTest
  
  // ... rest
}
```

**Potential Issues & Solutions**:

1. **Issue**: Component not found
   - **Solution**: Clean build, invalidate caches
   
2. **Issue**: Inject constructor not found
   - **Solution**: Verify @Inject annotations preserved
   
3. **Issue**: Module binding errors
   - **Solution**: Check @Provides and @Binds methods

**Validation Tests**:
- [ ] App component injection works
- [ ] Activity component injection works
- [ ] Service component injection works
- [ ] All ViewModels inject correctly
- [ ] All Repositories inject correctly
- [ ] All Fragments inject correctly
- [ ] Background services work
- [ ] Test components work

**Critical Paths to Test**:
1. **Login Flow**: AppComponent → LoginFragment
2. **Playback**: ServiceComponent → MediaPlayerService
3. **Main Activity**: ActivityComponent → MainActivity
4. **ViewModels**: Factory injection works

---

### Phase 5: Cleanup & Remove KAPT (Day 3 - Morning, 2-3 hours)
**Risk**: Low (all migrations complete)

#### Tasks:
- [ ] 5.1. Remove `kotlin-kapt` plugin
- [ ] 5.2. Remove KAPT configuration from app build.gradle.kts
- [ ] 5.3. Remove KAPT reflection config from root build.gradle.kts
- [ ] 5.4. Clean build to verify no KAPT tasks run
- [ ] 5.5. Update documentation

**Implementation**:

```kotlin
// app/build.gradle.kts
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("kotlin-parcelize")
  // id("kotlin-kapt")  // ❌ REMOVE THIS
  alias(libs.plugins.ksp)
  id("com.google.android.gms.oss-licenses-plugin")
}

// Remove entire kapt {} block ❌

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.expandProjection", "true")
}
```

```kotlin
// build.gradle.kts - Remove KAPT reflection configuration
allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")

  // Remove entire tasks.matching block for KAPT ❌
}
```

**Update Documentation**:
- [ ] `.github/copilot-instructions.md` - Already says KSP, now it's true! ✅
- [ ] Update comment in build.gradle.kts explaining KSP usage
- [ ] Add migration notes to CONTRIBUTING.md

---

### Phase 6: Testing & Validation (Day 3 - Afternoon, 4-5 hours)
**Risk**: Medium

#### Comprehensive Test Plan:

**Unit Tests**:
- [ ] `./gradlew test` - All unit tests pass
- [ ] No generated code differences affect tests

**Instrumented Tests**:
- [ ] `./gradlew connectedDebugAndroidTest` - If enabled
- [ ] Manual testing on device

**Functional Testing**:
- [ ] 6.1. **Login & Authentication**
  - [ ] OAuth flow works
  - [ ] User selection works
  - [ ] Server selection works
  - [ ] Library selection works
  
- [ ] 6.2. **Media Playback**
  - [ ] Play audiobook
  - [ ] Pause/Resume
  - [ ] Seek
  - [ ] Next/Previous track
  - [ ] Chapter navigation
  
- [ ] 6.3. **Library Management**
  - [ ] Load library
  - [ ] Search books
  - [ ] Sort/Filter
  - [ ] Book details
  
- [ ] 6.4. **Downloads**
  - [ ] Download book
  - [ ] Download progress
  - [ ] Play offline
  - [ ] Delete downloads
  
- [ ] 6.5. **Settings**
  - [ ] Change playback speed
  - [ ] Auto-rewind
  - [ ] Sleep timer
  - [ ] App settings
  
- [ ] 6.6. **Background Services**
  - [ ] Notification controls
  - [ ] Background playback
  - [ ] Android Auto (if enabled)

**Build Performance Testing**:
- [ ] Measure clean build time (before vs after)
- [ ] Measure incremental build time
- [ ] Measure memory usage
- [ ] Document improvements

**Expected Improvements**:
```
Clean Build:
- Before (KAPT): ~45-60 seconds
- After (KSP): ~20-30 seconds
- Improvement: 50-60% faster

Incremental Build:
- Before (KAPT): ~10-15 seconds
- After (KSP): ~5-8 seconds
- Improvement: 50% faster
```

---

### Phase 7: Documentation & PR (Day 3 - End, 1-2 hours)
**Risk**: Low

#### Tasks:
- [ ] 7.1. Document migration in commit message
- [ ] 7.2. Update CONTRIBUTING.md
- [ ] 7.3. Update architecture documentation
- [ ] 7.4. Create PR with detailed description
- [ ] 7.5. Add before/after build time metrics
- [ ] 7.6. Note any breaking changes (none expected)

**Documentation to Add**:

```markdown
## Build System Migration: KAPT → KSP

We've migrated from KAPT to KSP for all annotation processing.

### Benefits
- 2-4x faster builds
- Lower memory usage
- Future-proof (KSP is official replacement)
- Moshi codegen re-enabled (faster JSON, smaller APK)

### For Contributors
- Use `ksp()` instead of `kapt()` for annotation processors
- Generated code now in `build/generated/ksp/`
- Clean build recommended after pulling this change

### Troubleshooting
If you see "Cannot find Component" errors:
1. Build → Clean Project
2. File → Invalidate Caches & Restart
3. `./gradlew clean build`
```

---

## Rollback Plan

### If Issues in Phase 2 (Moshi):
1. Remove `ksp(libs.moshi.codegen)`
2. Continue without Moshi codegen (reflection is working)
3. **Impact**: None, just slower JSON parsing

### If Issues in Phase 3 (Room):
1. Change back: `ksp(libs.room.compiler)` → `kapt(libs.room.compiler)`
2. Restore `kapt {}` configuration block
3. **Impact**: Minimal, easily reversible

### If Issues in Phase 4 (Dagger):
1. Change back: `ksp(libs.dagger.compiler)` → `kapt(libs.dagger.compiler)`
2. Keep Moshi and Room on KSP (partial migration)
3. **Impact**: Still get some KSP benefits

### Complete Rollback:
1. Revert all changes to build.gradle.kts
2. Remove KSP plugin
3. Restore KAPT configuration
4. `./gradlew clean build`
5. **Time to rollback**: < 15 minutes

---

## Known Issues & Workarounds

### 1. Dagger KSP Experimental Status
**Issue**: Dagger docs say KSP support is experimental  
**Reality**: Been stable since Dagger 2.48 (we're on 2.54)  
**Mitigation**: Extensive testing in Phase 4

### 2. Generated Code Location Change
**Issue**: Generated code moves from `build/generated/kapt/` to `build/generated/ksp/`  
**Impact**: IDE might need cache invalidation  
**Solution**: Clean + Invalidate Caches

### 3. Incremental Compilation
**Issue**: First build after migration will be clean build  
**Impact**: One-time slower build  
**Solution**: Document in PR, warn contributors

### 4. Schema Location
**Issue**: Room schema location uses different KSP argument syntax  
**Impact**: None if configured correctly  
**Validation**: Check schema files generated in correct location

---

## Success Criteria

### Must Have ✅:
1. [ ] All annotation processors migrated to KSP
2. [ ] KAPT plugin removed
3. [ ] App builds successfully
4. [ ] All tests pass
5. [ ] All features work
6. [ ] Build time improved
7. [ ] Documentation updated

### Should Have ✅:
1. [ ] Moshi codegen re-enabled
2. [ ] Build time improvement measured and documented
3. [ ] No regression in functionality
4. [ ] Clean build runs without warnings

### Nice to Have 🎯:
1. [ ] APK size comparison (with Moshi codegen)
2. [ ] Memory usage comparison
3. [ ] CI/CD build time improvement
4. [ ] Blog post about migration (optional)

---

## Open Questions & Clarifications Needed

### 🤔 Question 1: Dagger KSP Stability
**Q**: Are you comfortable using Dagger KSP experimental support?  
**Context**: It's been stable since 2.48, we're on 2.54, and widely used in production  
**Options**:
- A) Proceed with Dagger KSP (recommended - it's stable)
- B) Migrate only Moshi + Room, keep Dagger on KAPT
- C) Wait for Dagger KSP to be officially stable

**My Recommendation**: Option A - Dagger KSP is production-ready despite "experimental" label

---

### 🤔 Question 2: Moshi Codegen Priority
**Q**: Should we re-enable Moshi codegen?  
**Context**: Comment says "deprecated for Kotlin 2.x" but this is incorrect  
**Benefits**: Faster JSON parsing, smaller APK, type-safe  
**Risk**: Very low - Moshi has excellent KSP support

**My Recommendation**: Yes, re-enable - the comment was misinformed

---

### 🤔 Question 3: Testing Depth
**Q**: How extensive should testing be?  
**Options**:
- A) Basic: Run unit tests + smoke test app (fast, 2 days total)
- B) Standard: Run all tests + manual testing of key features (recommended, 3 days)
- C) Thorough: Full regression testing + performance testing (safe, 4 days)

**My Recommendation**: Option B - Standard testing (as planned)

---

### 🤔 Question 4: Gradual Rollout
**Q**: Should we do staged rollout or merge all at once?  
**Options**:
- A) Single PR with all migrations (faster to merge)
- B) Multiple PRs (Moshi → Room → Dagger) (safer)
- C) Feature flag in build.gradle (most careful)

**My Recommendation**: Option A - Single PR is fine, easy to revert as unit

---

### 🤔 Question 5: Documentation of the Comment
**Q**: The Moshi comment says "deprecated for Kotlin 2.x" - should we investigate why?  
**Context**: This appears to be incorrect information  
**Action**: 
- A) Just fix it and document in PR
- B) Investigate git history to understand decision
- C) Ask original author if available

**My Recommendation**: Option A - Fix and document, likely a misunderstanding

---

## Dependencies

**Depends On**: None - this task is self-contained

**Blocks**: 
- Better build performance for all developers
- Future Kotlin version upgrades

**Blocked By**: None

---

## Estimated Effort Breakdown

| Phase | Task | Optimistic | Realistic | Pessimistic |
|-------|------|-----------|-----------|-------------|
| 1 | Preparation | 1.5h | 2.5h | 3h |
| 2 | Moshi Migration | 1.5h | 2.5h | 4h |
| 3 | Room Migration | 2h | 3.5h | 5h |
| 4 | Dagger Migration | 3h | 4.5h | 6h |
| 5 | KAPT Removal | 1.5h | 2.5h | 3h |
| 6 | Testing | 3h | 4.5h | 6h |
| 7 | Documentation | 1h | 1.5h | 2h |
| **Total** | | **13.5h (1.7d)** | **21.5h (2.7d)** | **29h (3.6d)** |

**Recommended**: 3 days (24 hours effort) with buffer

---

## Pre-Migration Checklist

Before starting, ensure:

- [ ] All current builds are green
- [ ] No pending KAPT-related changes
- [ ] Git working directory is clean
- [ ] Have test device/emulator ready
- [ ] Have Plex test server access
- [ ] Backup of current generated code
- [ ] Baseline build time measurements

---

## Approval Checklist

Please confirm before proceeding:

- [ ] **Strategy approved**: Incremental migration (Moshi → Room → Dagger)
- [ ] **Dagger KSP acceptable**: Despite "experimental" label
- [ ] **Moshi codegen**: Re-enable with KSP
- [ ] **Timeline acceptable**: 3 days for thorough migration
- [ ] **Testing plan**: Standard testing as outlined
- [ ] **Single PR approach**: All migrations in one PR
- [ ] **Risk tolerance**: Medium risk acceptable with good rollback plan
- [ ] **Open questions resolved**: Answers to questions 1-5

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/C2-kapt-to-ksp-migration`
2. ✅ Take baseline measurements (build time, APK size)
3. ✅ Start Phase 1: Add KSP plugin
4. ✅ Progress through phases sequentially
5. ✅ Daily status updates
6. ✅ Request code review after Phase 7

---

## Additional Notes

### Why This Matters
- **Build Speed**: Developers will save 30-60 seconds per build
- **CI/CD**: Faster CI builds = faster PR feedback
- **Future-Proof**: KAPT will eventually be removed from Kotlin
- **Best Practices**: KSP is official, recommended approach

### What Could Go Wrong
- Dagger injection failures (testable, reversible)
- Room query generation issues (unlikely with 2.7.0)
- Moshi JSON parsing issues (very unlikely)

### Confidence Level
- **Overall**: 85% confident in success
- **Moshi**: 95% confident
- **Room**: 90% confident
- **Dagger**: 80% confident (experimental label, but stable in practice)

---

**Ready to proceed?** Please review and provide:

1. ✅ **Approval or changes** to the strategy
2. 📝 **Answers to the 5 open questions**
3. 🎯 **Any additional concerns** or requirements

---

*Created: 2025-11-28*  
*Owner: Build/Infrastructure Team*  
*Estimated Completion: 2025-12-01*  
*Reviewer: Tech Lead / Senior Android Engineer*

