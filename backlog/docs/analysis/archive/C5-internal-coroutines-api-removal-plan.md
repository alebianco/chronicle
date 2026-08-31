# Task C5: InternalCoroutinesApi Usage Removal Plan

> **Archived.** Its task [[cu-15]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C5  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The project uses `@OptIn(InternalCoroutinesApi::class)` and `@InternalCoroutinesApi` annotations in 8 locations to access internal Kotlin coroutines APIs that are **not meant for public use**. This is a critical stability issue because:

1. **No Stability Guarantees**: Internal APIs can change without notice
2. **Breaking Changes**: Future Kotlin versions may break this code
3. **Unsupported Usage**: No documentation or support for internal APIs
4. **Build Warnings**: Generates warnings about unstable API usage
5. **Maintenance Risk**: Code may stop working with Kotlin updates

---

## Current State Analysis

### What I Found:

#### 1. **InternalCoroutinesApi Usages** (8 occurrences across 4 files):

**File 1: `PlexConfig.kt`** (3 usages):
```kotlin
// Line 163
@InternalCoroutinesApi
fun connectToServer(plexMediaService: PlexMediaService) {
    prevConnectToServerJob?.cancel("Killing previous connection attempt")
    _connectionState.postValue(CONNECTING)
    prevConnectToServerJob =
        Job().also {  // ❌ Job() is internal!
            val context = CoroutineScope(it + Dispatchers.Main)
            context.launch {
                val connectionResult = chooseViableConnections(plexMediaService)
                // ...
            }
        }
}

// Line 225
@InternalCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun chooseViableConnections(...) {
    // ...
    while (deferredConnections.any { it.isActive }) {  // ❌ isActive on Deferred is internal!
        // ... check deferred.isCompleted, deferred.getCompleted()
    }
}
```

**File 2: `ChronicleApplication.kt`** (3 usages):
```kotlin
// Line 135
@OptIn(InternalCoroutinesApi::class)
private fun setupNetwork(plexPrefs: PlexPrefsRepo) {
    // ... registers network callback
    // Calls connectToServer() which uses InternalCoroutinesApi
}

// Line 203
@InternalCoroutinesApi
private val networkStateListener = object : BroadcastReceiver() {
    // ... calls connectToServer()
}

// Line 220
@InternalCoroutinesApi
private fun connectToServer() {
    plexConfig.connectToServer(plexMediaService)
}
```

**File 3: `ChooseLibraryViewModel.kt`** (1 usage):
```kotlin
// Line 21
@OptIn(InternalCoroutinesApi::class)
class ChooseLibraryViewModel @Inject constructor(...) : ViewModel() {
    // Uses plexConfig.connectToServer() in some method
}
```

**File 4: `AudiobookDetailsViewModel.kt`** (1 usage):
```kotlin
// Line 241
@InternalCoroutinesApi
fun connectToServer() {
    viewModelScope.launch(Dispatchers.IO) {
        plexConfig.connectToServer(plexMediaService)
    }
}
```

#### 2. **Root Cause Analysis**:

The internal APIs being used are:

1. **`Job()` constructor** - Creates a new Job (internal in Kotlin 1.6+)
2. **`Deferred.isActive`** - Checks if Deferred is still active (internal property)
3. **`Deferred.getCompleted()`** - Gets completed result (internal method)

**Why these were used**:
- `PlexConfig.connectToServer()` manually manages coroutine lifecycle
- Uses `Job()` to create cancellable scope
- Polls `Deferred.isActive` to wait for first success
- Retrieves results with `getCompleted()`

**This is a MANUAL coroutine management pattern - we can refactor to use standard APIs!**

#### 3. **The Real Issue**:

The `chooseViableConnections()` function tries to:
1. Launch multiple async connection attempts in parallel
2. Cancel all others when first succeeds
3. Return first successful connection

This is **race pattern** that can be solved with standard `select` or structured approach!

---

## Risk Assessment

### Technical Risks:
| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Connection logic breaks | Medium | High | Extensive testing with real servers |
| Performance regression | Low | Medium | Measure before/after |
| Timeout behavior changes | Low | Medium | Test timeout scenarios |
| Edge case failures | Medium | Medium | Test all connection scenarios |

### User Impact:
- ✅ **Zero user-facing changes** - internal refactoring only
- ✅ **Same behavior** - connection logic remains functionally identical
- ✅ **Better reliability** - using stable APIs

### Benefits:
1. **API Stability**: No risk of breakage from Kotlin updates
2. **Maintainability**: Using documented, supported APIs
3. **No Warnings**: Clean build output
4. **Future-Proof**: Won't break with Kotlin 2.x updates
5. **Best Practices**: Proper structured concurrency

---

## Solution Strategy

### Approach: **Refactor to Use Public Coroutine APIs**

We'll refactor the connection racing logic in `PlexConfig.kt` to use standard coroutine APIs:

**Option A: Use `coroutineScope` + `async` + `awaitFirst`** (Recommended)
**Option B: Use `Flow` + `first()`
**Option C: Use `select` expression (experimental but public)

I recommend **Option A** - clean, idiomatic, fully public API.

---

## Implementation Plan

### Phase 1: Analysis & Testing Setup (Day 1 - Morning, 2 hours)
**Risk**: Low

#### Tasks:
- [ ] 1.1. Document current connection behavior
- [ ] 1.2. Create comprehensive test cases
- [ ] 1.3. Test current implementation with various scenarios
- [ ] 1.4. Measure current timing/performance
- [ ] 1.5. Create feature branch

**Test Scenarios to Document**:
- Single connection (success)
- Single connection (failure)
- Multiple connections (first succeeds)
- Multiple connections (all fail)
- Timeout scenario
- Cancel mid-connection
- Very slow connections

**Validation**:
- [ ] All scenarios work with current code
- [ ] Baseline timing measurements captured

---

### Phase 2: Refactor chooseViableConnections (Day 1 - Afternoon, 3-4 hours)
**Risk**: Medium (core connection logic)

#### Tasks:
- [ ] 2.1. Refactor to use public APIs
- [ ] 2.2. Remove internal API usage
- [ ] 2.3. Test each connection scenario
- [ ] 2.4. Verify timeout behavior
- [ ] 2.5. Verify cancellation works

**Current Implementation** (using internal APIs):
```kotlin
@InternalCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun chooseViableConnections(
    plexMediaService: PlexMediaService
): ConnectionResult {
    val timeoutFailureReason = "Connection timed out"
    return withTimeoutOrNull(15000) {
        val connections = connectionSet.sortedByDescending { it.local }
        val deferredConnections = connections.map { conn ->
            async {
                // Test connection
                // Return Success or Failure
            }
        }

        // ❌ PROBLEM: Using internal APIs
        while (deferredConnections.any { it.isActive }) {
            deferredConnections.forEach { deferred ->
                if (deferred.isCompleted) {
                    val completed = deferred.getCompleted()  // ❌ Internal!
                    if (completed is Success) {
                        deferredConnections.forEach { it.cancel(...) }
                        return@withTimeoutOrNull completed
                    }
                }
            }
            delay(500)
        }
        // Handle all failed...
    } ?: Failure(timeoutFailureReason)
}
```

**New Implementation** (using public APIs):

```kotlin
// ✅ NO INTERNAL APIS!
private suspend fun chooseViableConnections(
    plexMediaService: PlexMediaService
): ConnectionResult = coroutineScope {
    val timeoutFailureReason = "Connection timed out"
    
    try {
        withTimeout(15000) {
            val connections = connectionSet.sortedByDescending { it.local }
            
            // Strategy 1: Race all connections, return first success
            val results = connections.map { conn ->
                async {
                    testConnection(conn, plexMediaService)
                }
            }
            
            // Poll results, return first success
            while (results.any { !it.isCompleted }) {
                val completed = results.filter { it.isCompleted }
                val success = completed.firstOrNull { 
                    it.await() is Success  // ✅ Public API!
                }
                if (success != null) {
                    // Cancel remaining
                    results.forEach { 
                        if (it != success && it.isActive) {  
                            it.cancel()  // ✅ Public API!
                        }
                    }
                    return@withTimeout success.await()
                }
                delay(500)
            }
            
            // All completed, check for any success
            val successResult = results.firstOrNull { 
                it.await() is Success 
            }
            if (successResult != null) {
                return@withTimeout successResult.await()
            }
            
            // All failed
            Failure("All connection attempts failed")
        }
    } catch (e: TimeoutCancellationException) {
        Failure(timeoutFailureReason)
    }
}

// Extract connection testing logic
private suspend fun testConnection(
    conn: Connection,
    plexMediaService: PlexMediaService
): ConnectionResult {
    Timber.i("Testing connection: ${conn.uri}")
    return try {
        val result = plexMediaService.checkServer(conn.uri)
        if (result.isSuccessful) {
            Success(conn.uri)
        } else {
            Failure(result.message() ?: "Unknown failure")
        }
    } catch (e: Throwable) {
        Failure(e.localizedMessage ?: "Exception during connection test")
    }
}
```

**Alternative (Cleaner) Implementation**:

```kotlin
// ✅ Even cleaner with select-style logic
private suspend fun chooseViableConnections(
    plexMediaService: PlexMediaService
): ConnectionResult = coroutineScope {
    try {
        withTimeout(15000) {
            val connections = connectionSet.sortedByDescending { it.local }
            
            // Launch all attempts
            val attempts = connections.map { conn ->
                async { testConnection(conn, plexMediaService) }
            }
            
            // Return first success, or all failures
            var lastFailure: Failure? = null
            for (attempt in attempts) {
                when (val result = attempt.await()) {
                    is Success -> {
                        // Cancel remaining
                        attempts.forEach { if (it != attempt) it.cancel() }
                        return@withTimeout result
                    }
                    is Failure -> {
                        lastFailure = result
                        // Continue to next
                    }
                }
            }
            
            // All failed
            lastFailure ?: Failure("No connections available")
        }
    } catch (e: TimeoutCancellationException) {
        Failure("Connection timed out")
    }
}
```

**Why This Works**:
- ✅ Uses only public APIs (`async`, `await`, `cancel`, `isCompleted`, `coroutineScope`)
- ✅ Proper structured concurrency
- ✅ Same behavior as original
- ✅ Cleaner, more readable code
- ✅ Better error handling

**Validation**:
- [ ] First connection succeeds immediately
- [ ] Second connection succeeds after first fails
- [ ] All connections fail properly
- [ ] Timeout works correctly
- [ ] Cancellation works
- [ ] Performance is same or better

---

### Phase 3: Refactor connectToServer (Day 1 - End, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 3.1. Replace manual Job() management
- [ ] 3.2. Use standard coroutineScope
- [ ] 3.3. Test connection/reconnection
- [ ] 3.4. Verify cancellation

**Current Implementation**:
```kotlin
private var prevConnectToServerJob: CompletableJob? = null

@InternalCoroutinesApi
fun connectToServer(plexMediaService: PlexMediaService) {
    prevConnectToServerJob?.cancel("Killing previous connection attempt")
    _connectionState.postValue(CONNECTING)
    prevConnectToServerJob =
        Job().also {  // ❌ Job() is internal!
            val context = CoroutineScope(it + Dispatchers.Main)
            context.launch {
                val connectionResult = chooseViableConnections(plexMediaService)
                // ...
            }
        }
}
```

**New Implementation**:
```kotlin
private var connectJob: Job? = null

// ✅ No internal APIs!
fun connectToServer(
    plexMediaService: PlexMediaService,
    scope: CoroutineScope  // Inject scope (or use existing applicationScope)
) {
    connectJob?.cancel()  // ✅ Public API
    _connectionState.postValue(CONNECTING)
    
    connectJob = scope.launch(Dispatchers.Main) {
        val connectionResult = chooseViableConnections(plexMediaService)
        Timber.i("Returned connection $connectionResult")
        when (connectionResult) {
            is Success -> {
                if (connectionResult.url != PLACEHOLDER_URL) {
                    url = connectionResult.url
                    _connectionState.postValue(CONNECTED)
                    Timber.i("Connection success: $url")
                } else {
                    _connectionState.postValue(CONNECTION_FAILED)
                }
            }
            is Failure -> {
                _connectionState.postValue(CONNECTION_FAILED)
            }
        }
    }
}
```

**Alternative (If PlexConfig is Singleton)**:
```kotlin
@Singleton
class PlexConfig @Inject constructor(
    private val plexPrefsRepo: PlexPrefsRepo,
    @ApplicationScope private val applicationScope: CoroutineScope  // Inject!
) {
    private var connectJob: Job? = null

    fun connectToServer(plexMediaService: PlexMediaService) {
        connectJob?.cancel()
        _connectionState.postValue(CONNECTING)
        
        connectJob = applicationScope.launch(Dispatchers.Main) {
            // ... same as above
        }
    }
}
```

**Validation**:
- [ ] Connection works
- [ ] Reconnection works
- [ ] Multiple rapid calls handled correctly
- [ ] Cancellation works

---

### Phase 4: Remove @InternalCoroutinesApi Annotations (Day 2 - Morning, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 4.1. Remove annotation from `PlexConfig.kt`
- [ ] 4.2. Remove annotation from `ChronicleApplication.kt`
- [ ] 4.3. Remove annotation from `ChooseLibraryViewModel.kt`
- [ ] 4.4. Remove annotation from `AudiobookDetailsViewModel.kt`
- [ ] 4.5. Remove imports
- [ ] 4.6. Clean build to verify no warnings

**Files to Update**:

```kotlin
// PlexConfig.kt
// ❌ Remove:
@InternalCoroutinesApi
fun connectToServer(...)

@InternalCoroutinesApi
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun chooseViableConnections(...)

// ✅ Just:
fun connectToServer(...)
private suspend fun chooseViableConnections(...)
```

```kotlin
// ChronicleApplication.kt
// ❌ Remove:
@OptIn(InternalCoroutinesApi::class)
private fun setupNetwork(...)

@InternalCoroutinesApi
private val networkStateListener = ...

@InternalCoroutinesApi
private fun connectToServer()

// ✅ Just regular functions, no annotations
```

```kotlin
// ChooseLibraryViewModel.kt
// ❌ Remove:
@OptIn(InternalCoroutinesApi::class)
class ChooseLibraryViewModel ...

// ✅ Just:
class ChooseLibraryViewModel ...
```

```kotlin
// AudiobookDetailsViewModel.kt
// ❌ Remove:
@InternalCoroutinesApi
fun connectToServer()

// ✅ Just:
fun connectToServer()
```

**Validation**:
- [ ] Project compiles
- [ ] No warnings about internal APIs
- [ ] No runtime errors

---

### Phase 5: Update Callers (Day 2 - Morning, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 5.1. Update `ChronicleApplication` to pass scope
- [ ] 5.2. Update `AudiobookDetailsViewModel` if needed
- [ ] 5.3. Verify all call sites work
- [ ] 5.4. Test network callbacks

**If injecting scope into PlexConfig**:
```kotlin
// injection/modules/AppModule.kt
@Provides
@Singleton
fun providePlexConfig(
    plexPrefsRepo: PlexPrefsRepo,
    @ApplicationScope applicationScope: CoroutineScope
): PlexConfig {
    return PlexConfig(plexPrefsRepo, applicationScope)
}
```

**Or pass scope explicitly**:
```kotlin
// ChronicleApplication.kt
private fun connectToServer() {
    plexConfig.connectToServer(plexMediaService, applicationScope)
}
```

**Validation**:
- [ ] Network connectivity changes trigger connection
- [ ] App startup connects to server
- [ ] Manual connection attempts work
- [ ] ViewModels can trigger connection

---

### Phase 6: Comprehensive Testing (Day 2 - Afternoon, 3-4 hours)
**Risk**: Medium

#### Test Plan:

**Connection Scenarios**:
- [ ] 6.1. App startup with server available
- [ ] 6.2. App startup with server unavailable
- [ ] 6.3. Network connection lost
- [ ] 6.4. Network connection restored
- [ ] 6.5. Server URL change
- [ ] 6.6. Multiple rapid connection attempts
- [ ] 6.7. Connection timeout
- [ ] 6.8. Partial network failure

**Server Configurations**:
- [ ] 6.9. Local server (HTTP)
- [ ] 6.10. Remote server (HTTPS)
- [ ] 6.11. Multiple connection URLs
- [ ] 6.12. IPv4 and IPv6
- [ ] 6.13. VPN connections

**Edge Cases**:
- [ ] 6.14. Airplane mode toggle
- [ ] 6.15. WiFi to mobile data switch
- [ ] 6.16. Server restart during connection
- [ ] 6.17. Very slow server response
- [ ] 6.18. DNS failures

**Performance**:
- [ ] 6.19. Connection time same or better
- [ ] 6.20. Memory usage unchanged
- [ ] 6.21. CPU usage unchanged
- [ ] 6.22. Battery impact acceptable

**Regression Tests**:
- [ ] 6.23. Login flow works
- [ ] 6.24. Library loading works
- [ ] 6.25. Playback works
- [ ] 6.26. Downloads work
- [ ] 6.27. Settings work

---

### Phase 7: Documentation & PR (Day 2 - End, 1 hour)
**Risk**: Low

#### Tasks:
- [ ] 7.1. Document refactoring in commit message
- [ ] 7.2. Add KDoc to refactored methods
- [ ] 7.3. Update architecture docs if needed
- [ ] 7.4. Create PR with explanation
- [ ] 7.5. Note: No breaking changes

**Documentation to Add**:

```kotlin
/**
 * Attempts to connect to the server by testing all available connection URIs.
 * 
 * Tests connections in priority order (local first), and returns the first
 * successful connection. If all connections fail or timeout occurs, updates
 * connection state to FAILED.
 * 
 * @param plexMediaService Service to use for testing server connectivity
 * 
 * This function uses structured concurrency and only public coroutine APIs.
 * Previous implementation used InternalCoroutinesApi which has been removed
 * for better stability and maintainability.
 */
fun connectToServer(plexMediaService: PlexMediaService)
```

```markdown
## Coroutine Best Practices

Chronicle follows Kotlin coroutine best practices:

### ✅ Do's
- Use structured concurrency (`coroutineScope`, `async`, `launch`)
- Use public APIs only
- Inject `CoroutineScope` when needed
- Use `withTimeout` for timeouts
- Cancel coroutines properly

### ❌ Don'ts
- Don't use `GlobalScope` (use proper scopes)
- Don't use internal coroutine APIs (unstable)
- Don't create Jobs manually (use builders)
- Don't block coroutines (use `suspend`)

### Recent Improvements
- Removed `@InternalCoroutinesApi` usage (C5)
- Replaced manual Job management with structured concurrency
- Improved connection testing logic for better reliability
```

---

## Rollback Plan

### If Issues in Phase 2-3:
1. Revert `PlexConfig.kt` changes
2. Keep annotations temporarily
3. **Impact**: Still have internal API usage
4. **Time**: < 5 minutes

### If Issues in Phase 5:
1. Revert caller changes
2. Use original signature
3. **Impact**: Partial fix
4. **Time**: < 10 minutes

### Complete Rollback:
1. `git revert` all commits
2. **Impact**: Back to original
3. **Time**: < 2 minutes

---

## Success Criteria

### Must Have ✅:
1. [ ] Zero InternalCoroutinesApi usage
2. [ ] No build warnings
3. [ ] All connection scenarios work
4. [ ] Performance unchanged or better
5. [ ] No regressions
6. [ ] Tests pass

### Should Have ✅:
1. [ ] Cleaner, more readable code
2. [ ] Better structured concurrency
3. [ ] Documentation updated

### Nice to Have 🎯:
1. [ ] Faster connection times
2. [ ] Better error messages
3. [ ] Unit tests for connection logic

---

## Open Questions & Clarifications Needed

### 🤔 Question 1: Scope Injection
**Q**: Should we inject CoroutineScope into PlexConfig?  
**Context**: PlexConfig is @Singleton  
**Options**:
- A) Inject ApplicationScope into PlexConfig (cleaner) - **RECOMMENDED**
- B) Pass scope as parameter to connectToServer (more flexible)

**My Recommendation**: **Option A** - Inject scope, cleaner API

---

### 🤔 Question 2: Connection Strategy
**Q**: Keep polling approach or use different pattern?  
**Options**:
- A) Keep polling with public APIs (minimal change) - **RECOMMENDED**
- B) Use Flow-based approach (more reactive)
- C) Use select expression (experimental but public)

**My Recommendation**: **Option A** - Minimal change, proven pattern

---

### 🤔 Question 3: Testing Depth
**Q**: How thorough should connection testing be?  
**Options**:
- A) Manual testing key scenarios (sufficient) - **RECOMMENDED**
- B) + Automated integration tests (better)
- C) + Mock server testing (thorough)

**My Recommendation**: **Option A** - Manual is sufficient for refactoring

---

### 🤔 Question 4: Performance Testing
**Q**: Should we benchmark connection performance?  
**Options**:
- A) Basic timing comparison (simple)
- B) Detailed profiling (thorough) - **RECOMMENDED**
- C) Skip (risky)

**My Recommendation**: **Option B** - At least measure before/after

---

### 🤔 Question 5: ExperimentalCoroutinesApi
**Q**: Keep @OptIn(ExperimentalCoroutinesApi) or remove?  
**Context**: Used for `withTimeoutOrNull` which is now stable  
**Options**:
- A) Remove (it's stable now) - **RECOMMENDED**
- B) Keep (conservative)

**My Recommendation**: **Option A** - It's been stable since Kotlin 1.6

---

## Dependencies

**Depends On**: 
- C4 (GlobalScope removal) would be good to do first, but not required

**Blocks**: 
- Future Kotlin version upgrades
- Clean build without warnings

**Blocked By**: None

**Relates to**: 
- C4 (GlobalScope) - both are coroutine issues
- H5 (Dispatcher injection) - related architecture improvement

---

## Estimated Effort Breakdown

| Phase | Task | Optimistic | Realistic | Pessimistic |
|-------|------|-----------|-----------|-------------|
| 1 | Analysis & Setup | 1h | 2h | 3h |
| 2 | Refactor chooseViableConnections | 2h | 3.5h | 5h |
| 3 | Refactor connectToServer | 0.5h | 1h | 1.5h |
| 4 | Remove Annotations | 0.5h | 1h | 1.5h |
| 5 | Update Callers | 0.5h | 1h | 1.5h |
| 6 | Testing | 2h | 3.5h | 5h |
| 7 | Documentation | 0.5h | 1h | 1.5h |
| **Total** | | **7.5h (1d)** | **13h (1.6d)** | **19h (2.4d)** |

**Recommended**: 2 days (16 hours effort) with buffer for thorough testing

---

## Pre-Implementation Checklist

Before starting:

- [ ] All builds are green
- [ ] Understand current connection flow
- [ ] Have test Plex servers available
- [ ] Can test various network scenarios
- [ ] Git working directory clean
- [ ] Test device/emulator ready

---

## Approval Checklist

Please confirm:

- [ ] **Strategy approved**: Refactor to public APIs
- [ ] **Scope injection**: Inject ApplicationScope into PlexConfig?
- [ ] **Connection strategy**: Keep polling approach?
- [ ] **Testing depth**: Manual testing sufficient?
- [ ] **Timeline**: 2 days acceptable?
- [ ] **Open questions resolved**: Answers to questions 1-5

---

## Next Steps After Approval

1. ✅ Create feature branch: `feature/C5-remove-internal-coroutines-api`
2. ✅ Document current behavior thoroughly
3. ✅ Start Phase 1: Analysis
4. ✅ Progress through phases sequentially
5. ✅ Daily progress updates
6. ✅ PR after Phase 7

---

## Additional Notes

### Why This Matters
- **Stability**: Internal APIs can break at any time
- **Maintainability**: Using supported, documented APIs
- **Future-Proof**: Won't break with Kotlin updates
- **Best Practices**: Proper structured concurrency

### What Could Go Wrong
- Connection logic breaks (testable, reversible)
- Timeout behavior changes (testable)
- Performance regression (unlikely, measurable)

### Confidence Level
- **Overall**: 85% confident
- **Refactoring**: 90% confident (straightforward)
- **Testing**: 80% confident (connection testing can be tricky)
- **No Breakage**: 90% confident (same logic, better APIs)

### Related Issues
- This is on the critical path for Kotlin 2.x adoption
- Sets good example for coroutine usage
- Improves code quality overall

---

**Ready to proceed?** Please review and provide:

1. ✅ **Approval** for the refactoring approach
2. 📝 **Answers** to the 5 open questions (or use my recommendations)
3. 🎯 **Any concerns** about the 2-day timeline or connection testing

**This task removes technical debt and future-proofs the codebase!**

---

*Created: 2025-11-28*  
*Owner: Architecture Team*  
*Estimated Completion: 2025-12-02*  
*Reviewer: Senior Android Engineer / Kotlin Expert*

