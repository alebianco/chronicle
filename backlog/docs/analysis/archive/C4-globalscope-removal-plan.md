---
id: C4-globalscope-removal-plan
title: "Task C4: GlobalScope Usage Resolution Plan"
type: analysis
created_date: '2026-09-01'
---

# Task C4: GlobalScope Usage Resolution Plan

> **Archived.** Its task [[cu-15]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C4  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The `CachedFileManager` class uses `GlobalScope` for launching coroutines, which is a critical anti-pattern in Android development that leads to:

1. **Memory Leaks**: Coroutines outlive their components
2. **Lifecycle Issues**: Work continues even after component destruction
3. **No Cancellation**: Can't properly cancel operations
4. **Testing Issues**: Impossible to control coroutine execution in tests
5. **Violates Structured Concurrency**: No parent-child relationship

---

## Current State Analysis

### What I Found:

#### 1. **GlobalScope Usages** (3 occurrences in `CachedFileManager.kt`):

**Location 1** - Line 122 (`downloadTracks`):
```kotlin
override fun downloadTracks(bookId: Int, bookTitle: String) {
    // Add downloads to Fetch
    GlobalScope.launch {  // ❌ PROBLEM
        fetch.enqueue(makeRequests(bookId, bookTitle)) {
            val errors = it.mapNotNull { (_, error) ->
                if (error == Error.NONE) null else error
            }
            if (BuildConfig.DEBUG && errors.isNotEmpty()) {
                Toast.makeText(applicationContext, "Error enqueuing download: $errors", LENGTH_SHORT).show()
            }
            if (errors.isEmpty()) {
                DownloadNotificationWorker.start()
            }
        }
    }
}
```

**Location 2** - Line 238 (`deleteCachedBook`):
```kotlin
override suspend fun deleteCachedBook(bookId: Int) {
    Timber.i("Deleting downloaded book: $bookId")
    fetch.deleteGroup(bookId)
    GlobalScope.launch {  // ❌ PROBLEM
        withContext(Dispatchers.IO) {
            val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
            tracks.forEach {
                val trackFile = File(prefsRepo.cachedMediaDir, it.getCachedFileName())
                trackFile.delete()
                trackRepository.updateCachedStatus(it.id, false)
            }
            bookRepository.updateCachedStatus(bookId, false)
        }
    }
}
```

**Location 3** - Line 325 (Fetch listener callback):
```kotlin
override fun onFinished(groupId: Int, fetchGroup: FetchGroup) {
    // ... existing code ...
    val downloadSuccess = downloads.all { it.error == Error.NONE } && downloads.isNotEmpty()
    if (downloadSuccess) {
        GlobalScope.launch {  // ❌ PROBLEM
            withContext(Dispatchers.IO) {
                Timber.i("Book download success for ($groupId)")
                bookRepository.updateCachedStatus(groupId, true)
            }
        }
    }
}
```

#### 2. **CachedFileManager Scope**:

- **Lifecycle**: `@Singleton` - lives for entire app lifecycle
- **Injected via**: Dagger's `AppComponent`
- **Used by**: ViewModels, Fragments, Application class
- **Has lifecycle**: Registers `BroadcastReceiver` in `init` block (never unregistered!)

#### 3. **Why GlobalScope Was Used**:

Looking at the code patterns:
1. **Fire-and-forget operations**: Download enqueuing, file deletion
2. **Callback context**: Fetch library callbacks don't have coroutine context
3. **Singleton scope**: Developer may have thought "singleton = GlobalScope is OK"

**This is WRONG** - even singletons should use properly scoped coroutines!

#### 4. **Current Lifecycle Issue**:

```kotlin
init {
    applicationContext.registerReceiver(
        downloadListener,
        IntentFilter().apply {
            addAction(DownloadNotificationWorker.ACTION_CANCEL_BOOK_DOWNLOAD)
            addAction(DownloadNotificationWorker.ACTION_CANCEL_ALL_DOWNLOADS)
        },
        Context.RECEIVER_NOT_EXPORTED,
    )
    // ... Fetch listeners added
}
```

**Problem**: BroadcastReceiver is NEVER unregistered! This is a memory leak.

---

## Risk Assessment

### Technical Risks:
| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Breaking download functionality | Low | High | Extensive testing |
| Lifecycle management errors | Medium | Medium | Proper scope injection |
| Race conditions | Low | Medium | Proper synchronization |
| Test failures | Low | Low | Update tests |

### User Impact:
- ✅ **Zero visible impact** - internal refactoring only
- ✅ **Better reliability** - proper cancellation prevents wasted work
- ✅ **No API changes** - interface remains the same

### Benefits:
1. **Memory Safety**: No more leaked coroutines
2. **Proper Cancellation**: Work stops when app closes
3. **Testability**: Can inject test scopes
4. **Best Practices**: Follows structured concurrency
5. **Clearer Lifecycle**: Explicit scope management

---

## Solution Strategy

### Approach: **Inject Application-Scoped CoroutineScope**

Since `CachedFileManager` is a `@Singleton` tied to the application lifecycle, we'll:
1. Inject an application-scoped `CoroutineScope`
2. Replace all `GlobalScope.launch` with injected scope
3. Add proper cleanup in case of future scope changes
4. Add tests to verify scope behavior

---

## Implementation Plan

### Phase 1: Create Application Scope (Day 1 - Morning, 1.5 hours)
**Risk**: Low

#### Tasks:
- [ ] 1.1. Create `ApplicationScope` qualifier annotation
- [ ] 1.2. Provide application-scoped `CoroutineScope` in `AppModule`
- [ ] 1.3. Test scope injection works
- [ ] 1.4. Create feature branch

**Implementation**:

```kotlin
// injection/scopes/ApplicationScope.kt
package io.github.mattpvaughn.chronicle.injection.scopes

import javax.inject.Qualifier

/**
 * Qualifier for application-scoped CoroutineScope.
 * Tied to application lifecycle, cancelled when app process terminates.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
```

```kotlin
// injection/modules/AppModule.kt
@Provides
@Singleton
@ApplicationScope
fun provideApplicationScope(): CoroutineScope {
    // Application scope with SupervisorJob
    // SupervisorJob ensures one failure doesn't cancel all children
    return CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
```

**Validation**:
- [ ] Project syncs
- [ ] Dagger graph builds
- [ ] No circular dependencies

---

### Phase 2: Inject Scope into CachedFileManager (Day 1 - Morning, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 2.1. Add `@ApplicationScope CoroutineScope` parameter to constructor
- [ ] 2.2. Store as private property
- [ ] 2.3. Verify injection works

**Implementation**:

```kotlin
// data/sources/plex/CachedFileManager.kt
class CachedFileManager
@Inject
constructor(
    private val fetch: Fetch,
    private val prefsRepo: PrefsRepo,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val plexConfig: PlexConfig,
    private val applicationContext: Context,
    @ApplicationScope private val applicationScope: CoroutineScope  // ✅ ADD THIS
) : ICachedFileManager {
    // ... rest of class
}
```

**Validation**:
- [ ] Dagger compiles successfully
- [ ] App launches without crashes
- [ ] CachedFileManager instantiated correctly

---

### Phase 3: Replace GlobalScope #1 - downloadTracks (Day 1 - Afternoon, 1 hour)
**Risk**: Low-Medium

#### Tasks:
- [ ] 3.1. Replace `GlobalScope.launch` with `applicationScope.launch`
- [ ] 3.2. Test download functionality
- [ ] 3.3. Verify error handling still works
- [ ] 3.4. Test cancellation

**Implementation**:

```kotlin
override fun downloadTracks(
    bookId: Int,
    bookTitle: String,
) {
    // Add downloads to Fetch
    applicationScope.launch {  // ✅ Changed from GlobalScope
        fetch.enqueue(makeRequests(bookId, bookTitle)) {
            val errors =
                it.mapNotNull { (_, error) ->
                    if (error == Error.NONE) null else error
                }
            if (BuildConfig.DEBUG && errors.isNotEmpty()) {
                Toast.makeText(
                    applicationContext,
                    "Error enqueuing download: $errors",
                    LENGTH_SHORT,
                ).show()
            }
            if (errors.isEmpty()) {
                DownloadNotificationWorker.start()
            }
        }
    }
}
```

**Why This Works**:
- Application scope lives as long as the app
- Download operations can continue in background
- Will be cancelled if app process is killed (correct behavior)
- No memory leaks

**Validation**:
- [ ] Start book download
- [ ] Download continues in background
- [ ] Download notification appears
- [ ] Download completes successfully
- [ ] Error handling works

---

### Phase 4: Replace GlobalScope #2 - deleteCachedBook (Day 1 - Afternoon, 1 hour)
**Risk**: Low-Medium

#### Tasks:
- [ ] 4.1. Replace `GlobalScope.launch` with `applicationScope.launch`
- [ ] 4.2. Consider if this should even be async (it's already in a suspend function!)
- [ ] 4.3. Test deletion functionality

**Implementation - Option A** (Keep async):
```kotlin
override suspend fun deleteCachedBook(bookId: Int) {
    Timber.i("Deleting downloaded book: $bookId")
    fetch.deleteGroup(bookId)
    applicationScope.launch {  // ✅ Changed from GlobalScope
        withContext(Dispatchers.IO) {
            val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
            tracks.forEach {
                val trackFile = File(prefsRepo.cachedMediaDir, it.getCachedFileName())
                trackFile.delete()
                trackRepository.updateCachedStatus(it.id, false)
            }
            bookRepository.updateCachedStatus(bookId, false)
        }
    }
}
```

**Implementation - Option B** (Better - remove unnecessary launch):
```kotlin
override suspend fun deleteCachedBook(bookId: Int) {
    Timber.i("Deleting downloaded book: $bookId")
    fetch.deleteGroup(bookId)
    // ✅ No launch needed - already suspend function, just switch context
    withContext(Dispatchers.IO) {
        val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
        tracks.forEach {
            val trackFile = File(prefsRepo.cachedMediaDir, it.getCachedFileName())
            trackFile.delete()
            trackRepository.updateCachedStatus(it.id, false)
        }
        bookRepository.updateCachedStatus(bookId, false)
    }
}
```

**Recommendation**: **Option B** - The function is already `suspend`, so the caller handles the coroutine scope. No need for extra launch!

**Why Option B is Better**:
- Caller already has a scope (ViewModel, Repository, etc.)
- Proper structured concurrency
- Work is cancelled if caller's scope is cancelled
- Simpler, less indirection

**Validation**:
- [ ] Delete cached book
- [ ] Files are deleted
- [ ] Database updated
- [ ] No crashes
- [ ] Works from different call sites

---

### Phase 5: Replace GlobalScope #3 - onFinished Callback (Day 1 - Afternoon, 1.5 hours)
**Risk**: Medium (callback context)

#### Tasks:
- [ ] 5.1. Replace `GlobalScope.launch` in Fetch callback
- [ ] 5.2. Handle callback lifecycle properly
- [ ] 5.3. Test download completion
- [ ] 5.4. Verify database updates

**Implementation**:

```kotlin
init {
    // ... existing code ...
    
    fetch.addListener(
        object : FetchGroupStartFinishListener() {
            // ... existing methods ...
            
            override fun onFinished(
                groupId: Int,
                fetchGroup: FetchGroup,
            ) {
                Timber.i(
                    "Group change for book with id $groupId: ${fetchGroup.downloads.size} tracks downloaded",
                )
                val downloads = fetchGroup.downloads
                Timber.i(downloads.joinToString { it.status.toString() })
                activeDownloads.remove(groupId)
                val downloadSuccess =
                    downloads.all { it.error == Error.NONE } && downloads.isNotEmpty()
                if (downloadSuccess) {
                    applicationScope.launch {  // ✅ Changed from GlobalScope
                        withContext(Dispatchers.IO) {
                            Timber.i("Book download success for ($groupId)")
                            bookRepository.updateCachedStatus(groupId, true)
                        }
                    }
                }
            }
        },
    )
}
```

**Why This Works**:
- Fetch library calls this from its own thread
- Application scope is available throughout app lifecycle
- Work completes even if UI is gone (correct for downloads)
- Proper error handling with SupervisorJob

**Validation**:
- [ ] Download book completely
- [ ] onFinished callback fires
- [ ] Database updated with cached status
- [ ] UI reflects cached state
- [ ] Works in background

---

### Phase 6: Fix BroadcastReceiver Leak (Day 1 - End, 1 hour)
**Risk**: Low (bonus fix)

#### Tasks:
- [ ] 6.1. Add cleanup method to unregister receiver
- [ ] 6.2. Decide on lifecycle (keep or add cleanup hook)
- [ ] 6.3. Document lifecycle behavior

**Current Problem**:
```kotlin
init {
    applicationContext.registerReceiver(downloadListener, ...)
    // ❌ NEVER UNREGISTERED - MEMORY LEAK!
}
```

**Solution Options**:

**Option A** - Keep as-is but document:
```kotlin
/**
 * Manages cached files for the application.
 * 
 * Lifecycle: Singleton scoped to application lifetime.
 * Note: BroadcastReceiver is intentionally not unregistered as this manager
 * should remain active for the entire app lifecycle to handle download events.
 */
class CachedFileManager @Inject constructor(...)
```

**Option B** - Add cleanup (if we ever need it):
```kotlin
class CachedFileManager @Inject constructor(...) : ICachedFileManager {
    // ... existing code ...
    
    /**
     * Cleanup method - call if manager needs to be destroyed.
     * Not typically needed for singleton scope.
     */
    fun cleanup() {
        try {
            applicationContext.unregisterReceiver(downloadListener)
        } catch (e: IllegalArgumentException) {
            // Already unregistered, ignore
            Timber.d("Receiver already unregistered")
        }
        fetch.removeAll()  // Remove Fetch listeners
    }
}
```

**Recommendation**: **Option A** - Document but don't add cleanup. As a singleton, it's expected to live for app lifetime, and Android will clean up when process is killed.

---

### Phase 7: Testing (Day 2, 3-4 hours)
**Risk**: Medium

#### Comprehensive Test Plan:

**Unit Tests** (if time permits):
- [ ] 7.1. Test scope is properly injected
- [ ] 7.2. Mock scope for testing
- [ ] 7.3. Verify coroutines launch in correct scope

**Functional Tests**:

**Download Functionality**:
- [ ] 7.4. Start download → completes successfully
- [ ] 7.5. Start download → cancel mid-download
- [ ] 7.6. Start multiple downloads
- [ ] 7.7. Download with errors
- [ ] 7.8. Download notification shows
- [ ] 7.9. Download in background (app backgrounded)

**Delete Functionality**:
- [ ] 7.10. Delete cached book
- [ ] 7.11. Delete from different call sites (ViewModel, Fragment)
- [ ] 7.12. Delete non-existent book (error handling)

**Lifecycle Tests**:
- [ ] 7.13. Start download, close app (download continues)
- [ ] 7.14. Start download, kill process (download stops, no leak)
- [ ] 7.15. Rapid download/cancel cycles

**Regression Tests**:
- [ ] 7.16. Library shows cached books correctly
- [ ] 7.17. Book details shows cache status
- [ ] 7.18. Settings cache size calculation
- [ ] 7.19. Clear all cache works

**Edge Cases**:
- [ ] 7.20. No storage space
- [ ] 7.21. Network disconnected during download
- [ ] 7.22. Server unavailable

---

### Phase 8: Documentation & PR (Day 2 - End, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 8.1. Add KDoc to `ApplicationScope` annotation
- [ ] 8.2. Document scope usage in `CachedFileManager`
- [ ] 8.3. Update architecture docs
- [ ] 8.4. Add to `CONTRIBUTING.md`
- [ ] 8.5. Update TODO in `todo.md`
- [ ] 8.6. Create PR with clear explanation

**Documentation to Add**:

```kotlin
/**
 * Qualifier for application-scoped CoroutineScope.
 * 
 * This scope is tied to the application lifecycle and uses [SupervisorJob]
 * to ensure that failures in one coroutine don't affect others.
 * 
 * Use this scope for:
 * - Long-running background operations (downloads, sync)
 * - Operations that should outlive individual UI components
 * - Work that should continue even when app is backgrounded
 * 
 * Do NOT use for:
 * - UI-related work (use viewModelScope, lifecycleScope)
 * - Operations tied to specific screens (use appropriate scope)
 * 
 * Example usage:
 * ```kotlin
 * class MyManager @Inject constructor(
 *     @ApplicationScope private val applicationScope: CoroutineScope
 * ) {
 *     fun startBackgroundWork() {
 *         applicationScope.launch {
 *             // Long-running work here
 *         }
 *     }
 * }
 * ```
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
```

```markdown
## Coroutine Scopes

Chronicle uses proper structured concurrency:

- **`ApplicationScope`**: Singleton scope for app-lifetime work (downloads, sync)
- **`viewModelScope`**: Automatically cancelled when ViewModel is cleared
- **`lifecycleScope`**: Cancelled when Activity/Fragment is destroyed

### When to Use Which Scope

| Scope | Use For | Example |
|-------|---------|---------|
| ApplicationScope | Background downloads, database sync | CachedFileManager |
| viewModelScope | Loading data for UI, user actions | ViewModels |
| lifecycleScope | UI animations, temporary listeners | Activities/Fragments |

### Anti-Patterns to Avoid

❌ **Never use GlobalScope** - Use appropriate scoped coroutine instead
❌ **Never block main thread** - Use `withContext(Dispatchers.IO)`
❌ **Never ignore cancellation** - Let coroutines be cancelled naturally
```

---

## Rollback Plan

### If Issues Arise:

**Phase 3-5 Issues** (Specific GlobalScope replacement):
1. Revert specific file changes
2. Keep new scope injection
3. **Impact**: Partial fix, some GlobalScope remains
4. **Time**: < 5 minutes per revert

**Phase 2 Issues** (Injection):
1. Remove `applicationScope` parameter
2. Revert to GlobalScope
3. **Impact**: Back to original state
4. **Time**: < 10 minutes

**Complete Rollback**:
1. `git revert` all commits
2. **Impact**: No changes
3. **Time**: < 2 minutes

---

## Success Criteria

### Must Have ✅:
1. [ ] Zero `GlobalScope` usage in CachedFileManager
2. [ ] Application scope properly injected
3. [ ] All downloads work correctly
4. [ ] Delete operations work
5. [ ] No memory leaks
6. [ ] No crashes
7. [ ] Tests pass

### Should Have ✅:
1. [ ] Improved code quality
2. [ ] Better testability
3. [ ] Documentation updated
4. [ ] TODO marked complete

### Nice to Have 🎯:
1. [ ] Unit tests for scope behavior
2. [ ] LeakCanary verification
3. [ ] Performance comparison

---

## Open Questions & Clarifications Needed

### 🤔 Question 1: deleteCachedBook Implementation
**Q**: Should `deleteCachedBook` use `applicationScope.launch` or just `withContext`?  
**Context**: It's already a `suspend` function  
**Options**:
- A) Keep launch (fire-and-forget, original behavior)
- B) Just withContext (better structured concurrency) - **RECOMMENDED**

**My Strong Recommendation**: **Option B** - Caller already has scope, no need for extra launch

---

### 🤔 Question 2: BroadcastReceiver Cleanup
**Q**: Should we add a cleanup method to unregister the receiver?  
**Context**: It's a singleton, lives for app lifetime  
**Options**:
- A) Add cleanup method (more complete)
- B) Leave as-is, document behavior (simpler) - **RECOMMENDED**

**My Recommendation**: **Option B** - Document that it's intentional for singleton lifetime

---

### 🤔 Question 3: Testing Depth
**Q**: How much testing is needed?  
**Options**:
- A) Manual functional testing (sufficient)
- B) + Unit tests with mock scopes (better)
- C) + Instrumented tests (thorough)

**My Recommendation**: **Option A** - Manual testing is sufficient, this is internal refactoring

---

### 🤔 Question 4: SupervisorJob vs Job
**Q**: Should application scope use SupervisorJob or regular Job?  
**Context**: SupervisorJob means one coroutine failure doesn't cancel siblings  
**Options**:
- A) SupervisorJob (more resilient) - **RECOMMENDED**
- B) Regular Job (stricter error propagation)

**My Recommendation**: **Option A** - SupervisorJob for download manager (one failure shouldn't cancel all downloads)

---

### 🤔 Question 5: Dispatcher
**Q**: What dispatcher for application scope?  
**Options**:
- A) Dispatchers.Main (default, good for most work) - **RECOMMENDED**
- B) Dispatchers.Default (CPU-intensive work)
- C) Dispatchers.IO (I/O operations)

**My Recommendation**: **Option A** - Main dispatcher, then switch context as needed with `withContext(Dispatchers.IO)` for specific operations

---

## Dependencies

**Depends On**: None - self-contained

**Blocks**: 
- Better testability for CachedFileManager
- H5 (Dispatcher injection) - related but independent

**Blocked By**: None

**Relates to**: C5 (InternalCoroutinesApi) - both are coroutine issues

---

## Estimated Effort Breakdown

| Phase | Task | Optimistic | Realistic | Pessimistic |
|-------|------|-----------|-----------|-------------|
| 1 | Application Scope | 1h | 1.5h | 2h |
| 2 | Inject Scope | 0.5h | 1h | 1.5h |
| 3 | Replace #1 (downloadTracks) | 0.5h | 1h | 1.5h |
| 4 | Replace #2 (deleteCachedBook) | 0.5h | 1h | 1.5h |
| 5 | Replace #3 (onFinished) | 1h | 1.5h | 2h |
| 6 | Fix BroadcastReceiver | 0.5h | 1h | 1.5h |
| 7 | Testing | 2h | 3h | 4h |
| 8 | Documentation | 0.5h | 1h | 1.5h |
| **Total** | | **6.5h (0.8d)** | **11h (1.4d)** | **15.5h (1.9d)** |

**Recommended**: 1.5 days (12 hours effort) with buffer

---

## Pre-Implementation Checklist

Before starting:

- [ ] All builds are green
- [ ] No pending coroutine-related changes
- [ ] Git working directory clean
- [ ] Test device ready
- [ ] Can trigger downloads to test
- [ ] Understand current download flow

---

## Approval Checklist

Please confirm:

- [ ] **Strategy approved**: Inject ApplicationScope
- [ ] **deleteCachedBook**: Use withContext only (no extra launch)?
- [ ] **BroadcastReceiver**: Document but don't add cleanup?
- [ ] **SupervisorJob**: Use for resilient error handling?
- [ ] **Timeline**: 1.5 days acceptable?
- [ ] **Testing**: Manual testing sufficient?
- [ ] **Open questions resolved**: Answers to questions 1-5

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/C4-remove-globalscope`
2. ✅ Start Phase 1: Create ApplicationScope
3. ✅ Progress through phases sequentially
4. ✅ Test thoroughly after each phase
5. ✅ Daily progress update (single day task)
6. ✅ PR after Phase 8

---

## Additional Notes

### Why This Matters
- **Correctness**: Proper lifecycle management prevents leaks
- **Maintainability**: Clear scope ownership
- **Testability**: Can inject test scopes
- **Best Practices**: Structured concurrency is the standard

### What Could Go Wrong
- Download functionality breaks (unlikely, testable)
- Scope injection issues (caught in Phase 2)
- Lifecycle mismatch (singleton is correct scope)

### Confidence Level
- **Overall**: 95% confident
- **Scope injection**: 99% confident (standard Dagger)
- **Replacements**: 95% confident (straightforward)
- **Testing**: 90% confident (download testing can be tricky)

### Related Issues
- Also addresses the TODO in `todo.md`: "Constructor inject Dispatchers, remove GlobalScope usages"
- Improves testability for H1 (test coverage)
- Sets pattern for H5 (dispatcher injection)

---

**Ready to proceed?** Please review and provide:

1. ✅ **Approval** for the approach
2. 📝 **Answers** to the 5 open questions (or use my recommendations)
3. 🎯 **Any concerns** about the 1.5 day timeline

**This is the simplest Critical task** - low risk, high impact, quick to implement!

---

*Created: 2025-11-28*  
*Owner: Architecture Team*  
*Estimated Completion: Same day or next day*  
*Reviewer: Senior Android Engineer*

