# Project Analysis & Improvement Tasks

**Document Status**: Active Task Tracking  
**Last Updated**: 2025-11-28  
**Purpose**: Comprehensive analysis of Chronicle Android app identifying technical debt, issues, and improvement opportunities

---

## Executive Summary

Chronicle is a mature Android audiobook player with solid architecture (MVVM + Dagger 2 + Room + Retrofit). The codebase shows good patterns but has accumulated technical debt in several areas:

- **Critical Issues**: 6 items requiring immediate attention
- **High Priority**: 8 items affecting code quality and maintainability  
- **Medium Priority**: 7 items for modernization and best practices
- **Low Priority**: 5 items for nice-to-have improvements

**Overall Assessment**: The project builds successfully with warnings but has deprecated APIs, incomplete features, unsafe practices, and minimal test coverage that need addressing.

---

## 📋 Detailed Task Plans

Comprehensive resolution plans for all tasks are available in separate files in the `tasks/` directory:

### Critical Priority (C1-C6)
- [C1: Cleartext Traffic](tasks/C1-cleartext-traffic-resolution-plan.md)
- [C2: KAPT to KSP Migration](tasks/C2-kapt-to-ksp-migration-plan.md)
- [C3: Fresco to Coil Migration](tasks/C3-fresco-to-coil-migration-plan.md)
- [C4: GlobalScope Removal](tasks/C4-globalscope-removal-plan.md)
- [C5: InternalCoroutinesApi Removal](tasks/C5-internal-coroutines-api-removal-plan.md)
- [C6: LocalMediaSource Decision](tasks/C6-localmediasource-decision-plan.md)

### High Priority (H1-H8)
- [H1: Test Coverage](tasks/H1-test-coverage-plan.md) - 4 weeks ongoing
- [H2: ProGuard Rules](tasks/H2-proguard-rules-plan.md) - 2 days
- [H3: SDK Version Mismatch](tasks/H3-sdk-version-mismatch-plan.md) - 1 day
- [H4: CI Test Execution](tasks/H4-ci-test-execution-plan.md) - 1 day
- [H5: Dispatcher Injection](tasks/H5-dispatcher-injection-plan.md) - 3-4 days
- [H6: Delicate API Usage](tasks/H6-delicate-api-usage-plan.md) - 1 day (after C4/C5)
- [H7: TODO Audit](tasks/H7-todo-audit-plan.md) - 3 days
- [H8: Accessibility Support](tasks/H8-accessibility-support-plan.md) - 1-2 weeks

### Medium Priority (M1-M7)
- [M1: Outdated Dependency](tasks/M1-outdated-dependency-plan.md) - 0.5-1 day
- [M2: StateFlow Migration](tasks/M2-stateflow-migration-plan.md) - Decision required
- [M3: Billing Library Update](tasks/M3-billing-library-update-plan.md) - 2-3 days
- [M4: Chapter Management Refactor](tasks/M4-chapter-management-refactor-plan.md) - 1-2 weeks
- [M5: Android Auto Support](tasks/M5-android-auto-support-plan.md) - 1-2 weeks
- [M6: Notification Issues](tasks/M6-notification-issues-plan.md) - 2-3 days
- [M7: Large Library Performance](tasks/M7-large-library-performance-plan.md) - 3-4 weeks

Each plan includes:
- Detailed problem analysis
- Step-by-step implementation plan
- Code examples and templates
- Success criteria
- Risk mitigation
- Time estimates
- Approval checklists

---

## 🔴 CRITICAL PRIORITY (Security, Build Failures, Production Issues)

### C1. Security: Cleartext Traffic Enabled
**Severity**: Critical (Security Risk)  
**Location**: `app/src/main/AndroidManifest.xml:22`  
**Issue**: `android:usesCleartextTraffic="true"` allows unencrypted HTTP connections

**Impact**:
- Exposes user data to man-in-the-middle attacks
- Violates Android security best practices
- May fail Play Store security review

**Tasks**:
- [ ] Audit all network connections to ensure HTTPS is used
- [ ] Remove `usesCleartextTraffic="true"` from manifest
- [ ] Implement Network Security Configuration for exceptions if needed
- [ ] Test with actual Plex servers to ensure HTTPS works
- [ ] Add server certificate validation

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Security/Backend team

---

### C2. KAPT to KSP Migration Incomplete
**Severity**: Critical (Build Performance & Future Compatibility)  
**Location**: `app/build.gradle.kts`, multiple annotation processors  
**Issue**: Project still uses deprecated KAPT instead of KSP

**Current State**:
- Using `kotlin-kapt` plugin
- Moshi codegen removed but using reflection (performance hit)
- Room using KAPT (should use KSP)
- Dagger using KAPT (should use KSP)
- Instructions claim KSP is used but build files show KAPT

**Impact**:
- Slow build times (KAPT is significantly slower than KSP)
- Future Kotlin versions may not support KAPT
- Documentation mismatches reality

**Tasks**:
- [ ] Migrate Room from KAPT to KSP (`libs.room.compiler` → KSP)
- [ ] Migrate Dagger from KAPT to KSP (`libs.dagger.compiler` → KSP)
- [ ] Re-enable Moshi codegen with KSP (better performance than reflection)
- [ ] Remove `kotlin-kapt` plugin and KAPT configuration
- [ ] Update `.github/copilot-instructions.md` (currently says KSP but it's KAPT)
- [ ] Test all generated code works correctly
- [ ] Update build scripts and documentation
- [ ] Measure build time improvements

**Estimated Effort**: 3-5 days  
**Dependencies**: None (but should be done before other major changes)  
**Owner**: Build/Infrastructure team

---

### C3. Deprecated Fresco API Usage
**Severity**: Critical (Deprecated Library)  
**Location**: `views/ChronicleDraweeView.kt`, `views/BindingAdapters.kt`, `features/login/UserListAdapter.kt`  
**Issue**: Using deprecated `DraweeView` API from Fresco

**Warnings**:
```
w: 'class DraweeView<DH : DraweeHierarchy!> : ImageView' is deprecated. Deprecated in Java.
w: 'fun setImageURI(p0: Uri?): Unit' is deprecated. Deprecated in Java.
```

**Impact**:
- Future Fresco versions may remove deprecated APIs
- Build warnings clutter
- Potential runtime issues

**Tasks**:
- [ ] Evaluate migration options:
  - Option A: Migrate to Coil (modern, Kotlin-first)
  - Option B: Migrate to Glide (already in dependencies)
  - Option C: Update Fresco to non-deprecated APIs
- [ ] Replace all `DraweeView` usage
- [ ] Update `BindingAdapters.kt` image loading
- [ ] Update custom `ChronicleDraweeView` or remove
- [ ] Remove Fresco dependencies if migrating away
- [ ] Test image loading, caching, and transformations
- [ ] Update documentation

**Estimated Effort**: 4-6 days  
**Dependencies**: None  
**Recommendation**: Migrate to Coil for modern Kotlin support

---

### C4. GlobalScope Usage (Coroutine Anti-pattern)
**Severity**: Critical (Memory Leaks, Lifecycle Issues)  
**Location**: `data/sources/plex/CachedFileManager.kt:122, 238, 325`  
**Issue**: Using `GlobalScope` instead of proper coroutine scopes

**Impact**:
- Coroutines outlive their components (memory leaks)
- No structured concurrency
- Can't cancel operations properly
- Violates Android lifecycle management

**Tasks**:
- [ ] Inject appropriate `CoroutineScope` via Dagger
- [ ] Replace `GlobalScope.launch` with injected scope
- [ ] Ensure scope is tied to component lifecycle
- [ ] Add proper error handling
- [ ] Test cancellation works correctly
- [ ] Update TODO item in `todo.md` that mentions this

**Estimated Effort**: 1-2 days  
**Dependencies**: None  
**Owner**: Architecture team

---

### C5. InternalCoroutinesApi Usage
**Severity**: Critical (API Stability)  
**Location**: Multiple files (8 occurrences)  
**Issue**: Using internal Kotlin coroutines APIs not meant for public use

**Affected Files**:
- `application/ChronicleApplication.kt` (3 occurrences)
- `features/login/ChooseLibraryViewModel.kt`
- `features/bookdetails/AudiobookDetailsViewModel.kt`
- `data/sources/plex/PlexConfig.kt` (2 occurrences)

**Impact**:
- Internal APIs can change without notice
- Breaking changes in Kotlin updates
- No stability guarantees

**Tasks**:
- [ ] Identify why internal APIs are needed
- [ ] Find public API alternatives
- [ ] Refactor to use stable APIs
- [ ] Remove `@OptIn(InternalCoroutinesApi::class)` annotations
- [ ] Test functionality remains the same

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Architecture team

---

### C6. Incomplete LocalMediaSource Implementation
**Severity**: Critical (Broken Feature)  
**Location**: `data/sources/local/LocalMediaSource.kt`  
**Issue**: Entire class has `TODO("Not yet implemented")` stubs

**Impact**:
- Feature doesn't work if enabled
- Will crash if called
- Dead code in production

**Tasks**:
- [ ] Decide: Implement or remove?
  - If removing: Delete file and references
  - If keeping: Implement or disable/hide from users
- [ ] If implementing:
  - [ ] Add storage permissions handling
  - [ ] Implement file scanning
  - [ ] Add database integration
  - [ ] Create UI for local library management
- [ ] Update documentation on supported sources

**Estimated Effort**: 1 week (if implementing), 1 day (if removing)  
**Dependencies**: Product decision needed  
**Owner**: Product + Engineering

---

## 🟠 HIGH PRIORITY (Code Quality, Maintainability)

### H1. Inadequate Test Coverage
**Severity**: High (Quality Assurance)  
**Location**: `app/src/test/`, `app/src/androidTest/`  
**Issue**: Minimal test coverage across the project

**Current State**:
- Unit tests: Only 2 test files found
  - `AudiobookDetailsViewModelTest.kt`
  - `TrackListStateManagerTest.kt`
- Instrumented tests: ~5 test files
- No repository tests
- No DAO tests
- No service tests
- Most ViewModels untested
- `app/build.gradle.kts:137` disables DebugAndroidTest tasks

**Impact**:
- High risk of regressions
- Difficult to refactor safely
- No confidence in changes
- CI/CD less effective

**Tasks**:
- [ ] Set test coverage goals (aim for 70%+ on critical paths)
- [ ] Add unit tests for:
  - [ ] All ViewModels (at least happy path)
  - [ ] All Repositories
  - [ ] Business logic in managers
  - [ ] Utility classes
- [ ] Add DAO tests (in-memory database)
- [ ] Add integration tests for:
  - [ ] Playback service
  - [ ] Download manager
  - [ ] Sync logic
- [ ] Re-enable or document why DebugAndroidTest is disabled
- [ ] Add test coverage reporting to CI
- [ ] Document testing strategy

**Estimated Effort**: 3-4 weeks (ongoing)  
**Dependencies**: None  
**Owner**: QA + Engineering

---

### H2. Missing ProGuard Rules
**Severity**: High (Release Build Issues)  
**Location**: `app/proguard-rules.pro`  
**Issue**: Minimal ProGuard configuration despite using R8 minification

**Current State**:
- File is nearly empty (just comments)
- Using Room, Retrofit, Moshi, Dagger - all need keep rules
- Release builds may crash due to reflection issues
- Copilot instructions mention updating ProGuard but no rules exist

**Impact**:
- Release builds may crash
- Reflection-based libraries may fail
- Difficult to debug minified crashes

**Tasks**:
- [ ] Add keep rules for Room (entities, DAOs)
- [ ] Add keep rules for Retrofit (service interfaces)
- [ ] Add keep rules for Moshi (JSON models)
- [ ] Add keep rules for Dagger (injected classes)
- [ ] Add keep rules for ExoPlayer
- [ ] Add keep rules for Fresco/Glide
- [ ] Test release builds thoroughly
- [ ] Add R8 full mode testing
- [ ] Document ProGuard strategy

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Build team

---

### H3. SDK Version Mismatch
**Severity**: High (Compatibility)  
**Location**: `app/build.gradle.kts`, `.github/copilot-instructions.md`  
**Issue**: Documentation says targetSdk 36 but build file shows 34

**Current State**:
- `app/build.gradle.kts:23`: `targetSdk = 34`
- `.github/copilot-instructions.md`: Claims `targetSdk 36`
- Android 14 is current, Android 15 coming soon

**Impact**:
- Documentation misleads contributors
- Missing new platform features
- Play Store requires recent targetSdk

**Tasks**:
- [ ] Update targetSdk to 34 (current stable)
- [ ] Test on Android 14 devices
- [ ] Update documentation to match
- [ ] Plan for Android 15 (API 35) update
- [ ] Review new permission requirements
- [ ] Test behavior changes

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Platform team

---

### H4. No CI Test Execution
**Severity**: High (CI/CD)  
**Location**: `.github/workflows/ci.yml`  
**Issue**: CI workflow doesn't run tests automatically

**Current State**:
- CI runs ktlintCheck ✅
- CI runs assembleDebug ✅
- CI has unit test job but unclear if it runs
- No test reporting
- No test coverage metrics

**Impact**:
- Tests not enforced in PRs
- Broken tests can be merged
- No visibility into test health

**Tasks**:
- [ ] Verify test job actually runs tests
- [ ] Add test result reporting
- [ ] Add test coverage reporting (JaCoCo)
- [ ] Make tests required for merge
- [ ] Add badge to README
- [ ] Set up test result artifacts
- [ ] Add performance benchmarks

**Estimated Effort**: 1-2 days  
**Dependencies**: H1 (more tests needed)  
**Owner**: DevOps team

---

### H5. Hardcoded Dispatcher Usage
**Severity**: High (Testability)  
**Location**: Throughout codebase  
**Issue**: Direct use of `Dispatchers.IO`, `Dispatchers.Main` instead of injection

**Impact**:
- Difficult to test coroutines
- Can't use TestDispatchers
- Slows down tests
- TODO item mentions this issue

**Tasks**:
- [ ] Create `DispatcherProvider` interface
- [ ] Inject dispatchers via Dagger
- [ ] Replace direct dispatcher usage
- [ ] Create test dispatcher provider
- [ ] Update test setup to use test dispatchers
- [ ] Mark TODO as complete

**Estimated Effort**: 3-4 days  
**Dependencies**: None  
**Owner**: Architecture team

---

### H6. Delicate API Usage Without Suppression
**Severity**: High (Code Safety)  
**Location**: `data/sources/plex/CachedFileManager.kt:122, 238, 325`  
**Issue**: Using delicate coroutine APIs without proper care

**Warnings**:
```
w: This is a delicate API and its use requires care. Make sure you fully read and understand documentation of the declaration that is marked as a delicate API.
```

**Impact**:
- Risk of subtle bugs
- Unclear if developers understand implications
- No documentation of why delicate APIs are needed

**Tasks**:
- [ ] Review each delicate API usage
- [ ] Document why each is necessary
- [ ] Add `@OptIn` with explanation comments
- [ ] Explore safer alternatives
- [ ] Add tests for edge cases
- [ ] Code review by senior developer

**Estimated Effort**: 1-2 days  
**Dependencies**: C4 (GlobalScope issue)  
**Owner**: Architecture team

---

### H7. TODO Items in Critical Paths
**Severity**: High (Incomplete Features)  
**Location**: Multiple locations (20 TODOs found)  
**Issue**: Many TODO comments in production code

**Categories**:
1. **Stub implementations** (PlexMediaSource, LocalMediaSource)
2. **Missing features** (casting, multiple back stacks, playback speed in sleep timer)
3. **Tech debt** (token security, permissions)
4. **Search refactoring** needed

**Tasks**:
- [ ] Audit all 20 TODO items
- [ ] Categorize: Implement, Delete, or Convert to Issue
- [ ] Create tickets for legitimate features
- [ ] Remove or implement critical TODOs
- [ ] Document decisions
- [ ] Add linting rule to require TODO with ticket number

**Estimated Effort**: 2-3 days (audit + planning)  
**Dependencies**: Product decisions  
**Owner**: Tech Lead

---

### H8. No Accessibility Support Evident
**Severity**: High (Inclusivity)  
**Location**: UI components, layouts  
**Issue**: No evidence of accessibility testing or support

**Potential Issues**:
- No content descriptions visible
- May not work with TalkBack
- No accessibility testing in CI

**Tasks**:
- [ ] Audit all UI for accessibility
- [ ] Add content descriptions to all images/icons
- [ ] Test with TalkBack enabled
- [ ] Test with large fonts
- [ ] Test with high contrast
- [ ] Add accessibility tests
- [ ] Document accessibility features
- [ ] Get accessibility review

**Estimated Effort**: 1-2 weeks  
**Dependencies**: None  
**Owner**: UI/UX + Engineering

---

## 🟡 MEDIUM PRIORITY (Modernization, Best Practices)

### M1. Outdated Dependency (kotlin-result)
**Severity**: Medium (Maintenance)  
**Location**: `gradle/libs.versions.toml:18`  
**Issue**: Comment says `result = "1.1.11" # OUT OF DATE`

**Tasks**:
- [ ] Check latest version of kotlin-result
- [ ] Review changelog for breaking changes
- [ ] Update dependency
- [ ] Test all Result usages
- [ ] Update code if API changed

**Estimated Effort**: 2-3 hours  
**Dependencies**: None  
**Owner**: Maintenance team

---

### M2. Migrate to StateFlow from LiveData
**Severity**: Medium (Modernization)  
**Location**: All ViewModels, Repositories  
**Issue**: TODO item mentions considering StateFlow migration

**Current State**:
- Using LiveData throughout
- StateFlow is more modern, better for Kotlin
- Would improve coroutine integration

**Tasks**:
- [ ] Research StateFlow best practices
- [ ] Create migration guide
- [ ] Pilot migration in one feature
- [ ] Evaluate benefits vs effort
- [ ] Make decision: migrate or keep LiveData
- [ ] Update architecture docs
- [ ] If migrating: phased rollout

**Estimated Effort**: 2-3 weeks (if full migration)  
**Dependencies**: H5 (dispatcher injection)  
**Owner**: Architecture team  
**Note**: May not be worth effort if LiveData works well

---

### M3. Billing Library Update Required
**Severity**: Medium (Compliance)  
**Location**: IAP implementation  
**Issue**: TODO mentions billing version needs update by November

**Tasks**:
- [ ] Check current billing library version
- [ ] Check Google Play Billing Library requirements
- [ ] Update to latest version
- [ ] Test purchase flows
- [ ] Test subscription handling
- [ ] Test edge cases
- [ ] Update to Play Billing Library 6.x if needed

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Monetization team

---

### M4. Chapter Management Refactoring
**Severity**: Medium (Architecture)  
**Location**: Database, playback logic  
**Issue**: TODO mentions chapters should have their own database

**Current State**:
- Chapters likely embedded in tracks or books
- Makes queries more complex

**Tasks**:
- [ ] Design chapter schema
- [ ] Create ChapterDao
- [ ] Create migration for existing data
- [ ] Update playback service to use chapter DB
- [ ] Update UI to display chapters from DB
- [ ] Test chapter navigation
- [ ] Test migration from old schema

**Estimated Effort**: 1 week  
**Dependencies**: None  
**Owner**: Architecture team

---

### M5. Android Auto Support Completion
**Severity**: Medium (Feature)  
**Location**: Media session, Auto integration  
**Issue**: README says "feature flagged, but should be functional" - unclear state

**Tasks**:
- [ ] Test Android Auto thoroughly
- [ ] Document what works vs what doesn't
- [ ] Implement voice command support
- [ ] Improve Auto UI/UX
- [ ] Add Auto-specific testing
- [ ] Consider removing feature flag if stable
- [ ] Update README with accurate status

**Estimated Effort**: 1-2 weeks  
**Dependencies**: None  
**Owner**: Features team

---

### M6. Notification Issues
**Severity**: Medium (UX)  
**Location**: Notification builder, playback service  
**Issue**: TODO mentions notification not updated on chapter change

**Tasks**:
- [ ] Reproduce notification issues
- [ ] Fix chapter change notification update
- [ ] Test notification on all Android versions
- [ ] Test notification actions
- [ ] Test notification on Android 13+ (new permission)
- [ ] Add notification tests

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Playback team

---

### M7. Large Library Performance
**Severity**: Medium (Performance)  
**Location**: Repository queries, list loading  
**Issue**: TODO mentions issues loading huge libraries

**Current State**:
- Timeout increased from 15s to 30s (stopgap)
- Real fix needs: incremental loading, query optimization
- Repository gets don't scale (sub-n²)

**Tasks**:
- [ ] Profile library loading with large datasets
- [ ] Implement pagination/incremental loading
- [ ] Optimize repository query patterns
- [ ] Add database indexes
- [ ] Test with 1000+ book library
- [ ] Add performance tests
- [ ] Document scalability limits

**Estimated Effort**: 1-2 weeks  
**Dependencies**: None  
**Owner**: Performance team

---

## 🟢 LOW PRIORITY (Nice-to-Have, Polish)

### L1. Casting Support
**Severity**: Low (Feature Request)  
**Location**: Media session  
**Issue**: High priority TODO but not critical for core functionality

**Tasks**:
- [ ] Evaluate Chromecast demand
- [ ] Implement Cast integration
- [ ] Test cast discovery
- [ ] Test cast playback
- [ ] Handle cast failures gracefully
- [ ] Update UI for cast state

**Estimated Effort**: 2-3 weeks  
**Dependencies**: Product prioritization  
**Owner**: Features team

---

### L2. ExoPlayer Offload Scheduling
**Severity**: Low (Optimization)  
**Location**: Player configuration  
**Issue**: TODO mentions looking into `experimentalSetOffloadSchedulingEnabled`

**Tasks**:
- [ ] Research offload scheduling benefits
- [ ] Test on various devices
- [ ] Measure battery impact
- [ ] Measure audio quality impact
- [ ] Enable if beneficial
- [ ] Document findings

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: Playback team

---

### L3. Playback Progress Real-time Updates
**Severity**: Low (UX Polish)  
**Location**: Library view  
**Issue**: Progress only updates on library refresh, not real-time

**Tasks**:
- [ ] Add LiveData observer for playback progress
- [ ] Update library items during playback
- [ ] Optimize to avoid excessive updates
- [ ] Test battery impact
- [ ] Test performance impact

**Estimated Effort**: 1-2 days  
**Dependencies**: None  
**Owner**: UI team

---

### L4. Filter/Sort UI Completion
**Severity**: Low (Feature Polish)  
**Location**: Library fragment  
**Issue**: Partially implemented - subtitle doesn't reflect sort field

**Tasks**:
- [ ] Update grid/list item subtitle based on sort
- [ ] Add genre sorting
- [ ] Add release date sorting
- [ ] Test all sort combinations
- [ ] Polish UI/UX

**Estimated Effort**: 2-3 days  
**Dependencies**: None  
**Owner**: UI team

---

### L5. Documentation Improvements
**Severity**: Low (Developer Experience)  
**Location**: `docs/` folder  
**Issue**: Documentation exists but could be enhanced

**Tasks**:
- [ ] Add more code examples
- [ ] Add troubleshooting section
- [ ] Add FAQ
- [ ] Add contribution guide details
- [ ] Update diagrams if architecture changes
- [ ] Add video walkthroughs
- [ ] Keep in sync with code changes

**Estimated Effort**: Ongoing  
**Dependencies**: None  
**Owner**: Tech Writing

---

## 📊 Summary Metrics

| Category | Count | Estimated Effort |
|----------|-------|------------------|
| Critical | 6 | 3-5 weeks |
| High | 8 | 6-8 weeks |
| Medium | 7 | 4-6 weeks |
| Low | 5 | 2-3 weeks |
| **Total** | **26** | **15-22 weeks** |

---

## 🎯 Recommended Roadmap

### Phase 1: Critical Fixes (3-5 weeks)
**Goal**: Address security, stability, and build issues

1. **Week 1-2**: 
   - C4: Fix GlobalScope usage (2d)
   - C1: Remove cleartext traffic, enforce HTTPS (3d)
   - C5: Remove InternalCoroutinesApi (3d)

2. **Week 3-4**:
   - C2: Migrate KAPT → KSP (5d)
   - C6: Decide on LocalMediaSource (1d decision + implementation if needed)

3. **Week 5**:
   - C3: Migrate from Fresco to Coil (5d)
   - H3: Fix SDK version mismatch (1d)

### Phase 2: Quality & Infrastructure (4-6 weeks)
**Goal**: Improve code quality, testing, and CI/CD

4. **Week 6-7**:
   - H2: Add ProGuard rules (3d)
   - H4: Fix CI test execution (2d)
   - H7: Audit and address TODOs (3d)

5. **Week 8-10**:
   - H1: Add test coverage (ongoing, start with critical paths)
   - H5: Inject Dispatchers for testability (4d)
   - H6: Address delicate API usage (2d)

6. **Week 11**:
   - H8: Accessibility audit and fixes (start, ongoing)

### Phase 3: Modernization (3-4 weeks)
**Goal**: Update dependencies and architecture

7. **Week 12-13**:
   - M1: Update kotlin-result (1d)
   - M3: Update billing library (3d)
   - M6: Fix notification issues (3d)
   - M7: Optimize large library performance (1w)

8. **Week 14-15**:
   - M2: Evaluate StateFlow migration (decide, pilot)
   - M4: Refactor chapter management (1w)

### Phase 4: Features & Polish (2-3 weeks)
**Goal**: Complete features and polish UX

9. **Week 16-17**:
   - M5: Complete Android Auto support
   - L3: Real-time progress updates
   - L4: Complete filter/sort UI

10. **Week 18** (if prioritized):
    - L1: Add Casting support (or defer to Phase 5)
    - L2: ExoPlayer optimizations
    - L5: Documentation improvements (ongoing)

---

## 🔄 Ongoing Maintenance

These should be continuous practices:

- **Weekly**: Review new TODOs, ensure they're tracked
- **Sprint**: Add tests for any new features
- **Monthly**: Check for dependency updates
- **Quarterly**: Security audit, performance review
- **Annually**: Major version updates (Android SDK, Kotlin)

---

## 📝 Notes

### Dependencies to Monitor
- Kotlin 2.1.20 → watch for 2.2.x
- AGP 8.13.1 → keep updated with stable
- Room 2.7.0-alpha12 → move to stable when available
- Dagger 2.54 → consider Hilt migration long-term

### Architecture Decisions Needed
1. StateFlow vs LiveData: decide and document
2. LocalMediaSource: implement or remove?
3. Fresco → Coil migration: approved?
4. Casting feature: prioritize or defer?

### Risk Assessment
- **Highest Risk**: Security (cleartext traffic)
- **Most Impact**: Test coverage increase
- **Biggest Effort**: Full StateFlow migration (if pursued)
- **Quick Wins**: Fix GlobalScope, update docs, add ProGuard rules

---

## 🤝 How to Use This Document

1. **Product/Tech Lead**: Review and prioritize based on business needs
2. **Engineering**: Pick up tasks from approved phases
3. **QA**: Focus testing on areas being refactored
4. **DevOps**: Support CI/CD improvements in Phase 2
5. **All**: Update this doc as tasks complete, mark with `[x]`

**Status Updates**: Update this document monthly or after each phase completes.

---

*This analysis was generated on 2025-11-28. Regular updates recommended as codebase evolves.*

