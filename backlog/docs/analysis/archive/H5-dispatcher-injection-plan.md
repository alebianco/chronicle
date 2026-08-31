# Task H5: Hardcoded Dispatcher Usage Resolution Plan

> **Archived.** Its task [[cu-15]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: H5  
**Priority**: 🟠 High (Testability)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

Direct use of `Dispatchers.IO`, `Dispatchers.Main`, and `Dispatchers.Default` throughout the codebase makes testing difficult and is mentioned in the project TODO list.

**Current State**:
- Direct `Dispatchers.*` usage in ViewModels, Repositories, and other classes
- Impossible to inject test dispatchers
- Tests must use actual IO/Main dispatchers or workarounds
- TODO item in codebase: "Constructor inject Dispatchers, remove GlobalScope usages"

**Impact**:
- Tests are slow (using real IO dispatcher)
- Tests are fragile (timing issues)
- Can't control dispatcher in tests
- Makes unit testing harder than necessary

---

## Solution Strategy

Create a `DispatcherProvider` interface and inject it via Dagger, allowing production code to use real dispatchers while tests use `TestDispatcher`.

---

## Implementation Plan

### Phase 1: Create DispatcherProvider (2 hours)

```kotlin
// util/DispatcherProvider.kt
package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides coroutine dispatchers. Allows swapping dispatchers for testing.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

/**
 * Production implementation using standard Kotlin dispatchers.
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

/**
 * Test implementation using a single test dispatcher for all.
 */
class TestDispatcherProvider(
    private val testDispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val main: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
    override val unconfined: CoroutineDispatcher = testDispatcher
}
```

---

### Phase 2: Configure Dagger (1 hour)

```kotlin
// injection/modules/AppModule.kt
@Provides
@Singleton
fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
```

For tests:
```kotlin
// testShared/TestAppModule.kt (for instrumented tests)
@Provides
@Singleton
fun provideDispatcherProvider(testDispatcher: TestDispatcher): DispatcherProvider =
    TestDispatcherProvider(testDispatcher)
```

---

### Phase 3: Update ViewModels (8 hours)

**Pattern**:

```kotlin
// Before
class LibraryViewModel @Inject constructor(
    private val bookRepository: IBookRepository,
    // ... other deps
) : ViewModel() {
    
    fun loadBooks() {
        viewModelScope.launch(Dispatchers.IO) {  // ❌ Hardcoded
            // work
        }
    }
}

// After
class LibraryViewModel @Inject constructor(
    private val bookRepository: IBookRepository,
    private val dispatchers: DispatcherProvider,  // ✅ Injected
    // ... other deps
) : ViewModel() {
    
    fun loadBooks() {
        viewModelScope.launch(dispatchers.io) {  // ✅ Testable
            // work
        }
    }
}
```

**ViewModels to Update** (~12 files):
- LibraryViewModel
- AudiobookDetailsViewModel
- CurrentlyPlayingViewModel
- SettingsViewModel
- MainActivityViewModel
- ChooseLibraryViewModel
- ChooseServerViewModel
- ChooseUserViewModel
- LoginViewModel
- And others...

---

### Phase 4: Update Repositories (4 hours)

**Repositories to Update** (~5 files):
- BookRepository
- TrackRepository
- PlexLoginRepo
- CollectionsRepository
- And others with coroutines...

---

### Phase 5: Update Tests (4 hours)

**Update test base class**:

```kotlin
@ExperimentalCoroutinesApi
abstract class ViewModelTestBase {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    protected val testDispatcher = StandardTestDispatcher()
    protected val dispatchers = TestDispatcherProvider(testDispatcher)
    
    @Before
    fun setupBase() {
        Dispatchers.setMain(testDispatcher)
    }
    
    @After
    fun tearDownBase() {
        Dispatchers.resetMain()
    }
}
```

**Update existing tests**:
```kotlin
@Test
fun `test something`() = runTest(testDispatcher) {
    // Given
    val viewModel = LibraryViewModel(
        mockRepository,
        dispatchers,  // ✅ Inject test dispatchers
        // ...
    )
    
    // When
    viewModel.loadBooks()
    testDispatcher.scheduler.advanceUntilIdle()  // Control timing
    
    // Then
    // assertions
}
```

---

### Phase 6: Find & Replace Remaining (2 hours)

**Search for remaining usages**:
```bash
grep -r "Dispatchers\." app/src/main --include="*.kt" | grep -v "import"
```

**Common patterns to replace**:
- `Dispatchers.IO` → `dispatchers.io`
- `Dispatchers.Main` → `dispatchers.main`
- `Dispatchers.Default` → `dispatchers.default`

---

## Migration Strategy

1. **Week 1**: Create infrastructure, update 3-4 ViewModels
2. **Week 2**: Update remaining ViewModels, start Repositories
3. **Week 3**: Finish Repositories, update tests
4. **Week 4**: Find stragglers, verify all tests pass

**Incremental approach** - Each ViewModel can be updated independently.

---

## Success Criteria

### Must Have ✅:
1. [ ] DispatcherProvider created and injected
2. [ ] All ViewModels use injected dispatchers
3. [ ] All Repositories use injected dispatchers
4. [ ] All tests updated to use TestDispatcherProvider
5. [ ] No direct `Dispatchers.*` usage in main code
6. [ ] TODO marked complete
7. [ ] All tests pass

### Should Have ✅:
1. [ ] Documentation updated
2. [ ] Example test showing usage
3. [ ] CONTRIBUTING.md explains pattern

### Nice to Have 🎯:
1. [ ] Lint rule to prevent future `Dispatchers.*` usage
2. [ ] Custom lint check

---

## Testing Impact

**Before**:
```kotlin
@Test
fun testSomething() {
    // Test uses real IO dispatcher - slow, unpredictable
    viewModel.loadData()
    Thread.sleep(1000) // ❌ Gross!
}
```

**After**:
```kotlin
@Test
fun testSomething() = runTest(testDispatcher) {
    viewModel.loadData()
    testDispatcher.scheduler.advanceUntilIdle() // ✅ Fast, deterministic
    // Assertions immediately
}
```

---

## Dependencies

**Depends On**: None

**Blocks**: None

**Benefits**: H1 (testing) - makes tests much easier

**Blocked By**: None

---

## Estimated Effort

| Phase | Time |
|-------|------|
| 1. Create DispatcherProvider | 2h |
| 2. Configure Dagger | 1h |
| 3. Update ViewModels | 8h |
| 4. Update Repositories | 4h |
| 5. Update Tests | 4h |
| 6. Find & Replace | 2h |
| **Total** | **21h (3-4 days)** |

---

## Approval Checklist

- [ ] **Pattern approved**: DispatcherProvider approach
- [ ] **Timeline OK**: 3-4 days
- [ ] **Can be incremental**: Update class-by-class
- [ ] **Test updates included**: Time allocated

---

## Next Steps

1. ✅ Create branch: `feature/H5-dispatcher-injection`
2. ✅ Create DispatcherProvider
3. ✅ Update 2-3 ViewModels as proof of concept
4. ✅ Get feedback
5. ✅ Continue with remaining classes

---

*Created: 2025-11-28*  
*Owner: Engineering Team*  
*Estimated Completion: 3-4 days*

