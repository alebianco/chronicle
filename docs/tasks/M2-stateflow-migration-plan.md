# Task M2: Migrate to StateFlow from LiveData Resolution Plan

**Task ID**: M2  
**Priority**: 🟡 Medium (Modernization)  
**Created**: 2025-11-28  
**Status**: Planning - **DECISION REQUIRED**

---

## Problem Statement

The project uses LiveData throughout, but there's a TODO suggesting consideration of StateFlow migration. This is a significant architectural decision with major implications.

**Current State**:
- LiveData used in all ViewModels
- LiveData used in Repositories
- Data binding uses LiveData
- TODO in `todo.md` mentions considering StateFlow

**Question**: Should we migrate? This is a **product/architecture decision**, not just a technical one.

---

## Analysis: LiveData vs StateFlow

### LiveData Pros ✅
- ✅ Lifecycle-aware (stops when inactive)
- ✅ Perfect for Android UI
- ✅ Data Binding support built-in
- ✅ Works well with current architecture
- ✅ Team knows it well
- ✅ Stable, mature, well-documented

### LiveData Cons ❌
- ❌ Android-specific (not multiplatform)
- ❌ Less flexible than Flow/StateFlow
- ❌ No backpressure handling
- ❌ Transformations can be verbose
- ❌ Not as modern/idiomatic for Kotlin

### StateFlow Pros ✅
- ✅ Kotlin-first, coroutines-native
- ✅ Works on any platform (KMP ready)
- ✅ More flexible operators
- ✅ Better testability
- ✅ Modern, idiomatic Kotlin
- ✅ Better integration with suspend functions

### StateFlow Cons ❌
- ❌ Not lifecycle-aware by default (need lifecycleScope)
- ❌ Data Binding needs adapters
- ❌ Team learning curve
- ❌ More boilerplate for simple cases
- ❌ Migration effort significant

---

## Decision Framework

### When to Migrate to StateFlow:

**DO MIGRATE if**:
- Planning Kotlin Multiplatform (KMP)
- Team expertise in coroutines/Flow
- Want better testability
- Building new reactive features
- Have time for significant refactor

**DON'T MIGRATE if**:
- LiveData working well
- Limited time/resources
- No KMP plans
- Team unfamiliar with Flow
- Other priorities more urgent

---

## My Recommendation: **DON'T MIGRATE (Yet)**

**Reasoning**:
1. **LiveData works well** for this Android-only app
2. **High effort** (3+ weeks) for **uncertain benefit**
3. **Other critical tasks** need attention (C1-C6, H1-H8)
4. **Risk of introducing bugs** during migration
5. **No compelling reason** to change working code

**Alternative Approach**:
- Use StateFlow for **new features** only
- Keep LiveData for existing code
- Gradual, organic migration over time
- Evaluate after 6-12 months

---

## Implementation Plan (If Approved to Migrate)

### Phase 1: Pilot Migration (Week 1, 20 hours)

**Choose One Feature to Migrate**:
- Pick isolated, small feature
- Good candidate: Settings screen
- Migrate ViewModel + Repository
- Document lessons learned

**Example Migration**:

```kotlin
// Before (LiveData)
class SettingsViewModel @Inject constructor(
    private val prefsRepo: PrefsRepo
) : ViewModel() {
    
    val autoRewindEnabled: LiveData<Boolean> = prefsRepo.autoRewindEnabled
    
    fun setAutoRewind(enabled: Boolean) {
        prefsRepo.setAutoRewind(enabled)
    }
}

// After (StateFlow)
class SettingsViewModel @Inject constructor(
    private val prefsRepo: PrefsRepo
) : ViewModel() {
    
    val autoRewindEnabled: StateFlow<Boolean> = prefsRepo.autoRewindEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    
    fun setAutoRewind(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutoRewind(enabled)
        }
    }
}
```

**UI Observation**:
```kotlin
// Before (LiveData)
viewModel.autoRewindEnabled.observe(viewLifecycleOwner) { enabled ->
    // Update UI
}

// After (StateFlow)
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.autoRewindEnabled.collect { enabled ->
            // Update UI
        }
    }
}

// Or with extension function:
viewModel.autoRewindEnabled.collectAsState(viewLifecycleOwner) { enabled ->
    // Update UI
}
```

---

### Phase 2: Create Migration Guide (Week 1, 8 hours)

**Document Patterns**:

```markdown
# StateFlow Migration Guide

## ViewModel Pattern

### LiveData → StateFlow
```kotlin
// LiveData
private val _uiState = MutableLiveData<UiState>()
val uiState: LiveData<UiState> = _uiState

// StateFlow
private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

### Repository Pattern

```kotlin
// LiveData
fun getBooks(): LiveData<List<Book>> = bookDao.getAllRows()

// StateFlow  
fun getBooks(): Flow<List<Book>> = bookDao.getAllRowsFlow()
```

## Testing

```kotlin
// LiveData test
val liveData = viewModel.books.getOrAwaitValue()
assertThat(liveData).isEqualTo(expected)

// StateFlow test
val result = viewModel.books.first()
assertThat(result).isEqualTo(expected)
```
```

---

### Phase 3: Migrate Core ViewModels (Week 2-3, 60 hours)

**Priority Order**:
1. SettingsViewModel (pilot)
2. LibraryViewModel
3. AudiobookDetailsViewModel
4. CurrentlyPlayingViewModel
5. MainActivityViewModel
6. Others...

**Per ViewModel**:
- Convert LiveData to StateFlow (~2h)
- Update UI observations (~1h)
- Test thoroughly (~2h)
- **Total per ViewModel**: ~5h

**12 ViewModels × 5h = 60 hours**

---

### Phase 4: Update Repositories (Week 4, 40 hours)

**Repositories to Migrate**:
- BookRepository
- TrackRepository
- PlexLoginRepo
- CollectionsRepository
- Others...

---

### Phase 5: Data Binding Adapters (Week 5, 20 hours)

**Create StateFlow Data Binding**:

```kotlin
// BindingAdapters.kt
@BindingAdapter("stateFlowText")
fun TextView.setStateFlowText(flow: StateFlow<String>?) {
    flow?.let {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { text ->
                    this@setStateFlowText.text = text
                }
            }
        }
    }
}
```

---

### Phase 6: Testing & Validation (Week 6, 40 hours)

**Comprehensive Testing**:
- [ ] All ViewModels work
- [ ] All UI updates correctly
- [ ] No memory leaks (LeakCanary)
- [ ] Lifecycle handling correct
- [ ] Performance acceptable
- [ ] Battery impact acceptable

---

## Effort Estimate (Full Migration)

| Phase | Time |
|-------|------|
| 1. Pilot | 20h |
| 2. Guide | 8h |
| 3. ViewModels | 60h |
| 4. Repositories | 40h |
| 5. Data Binding | 20h |
| 6. Testing | 40h |
| **Total** | **188h (~4-5 weeks)** |

---

## Alternative: Hybrid Approach ✅ RECOMMENDED

**Use StateFlow for NEW code only**:
- Keep existing LiveData
- New features use StateFlow
- Natural migration over 1-2 years
- Low risk, gradual learning

**Benefits**:
- Zero migration effort
- Team learns StateFlow gradually
- Both patterns coexist fine
- Can reassess later

**Guidelines**:
```markdown
## State Management Guidelines

### For Existing Code
- Keep LiveData (don't fix what works)
- Only migrate if touching code anyway

### For New Code
- Use StateFlow for new ViewModels
- Use Flow for new Repository methods
- Document pattern choice

### Data Binding
- Continue using LiveData (better support)
- Or create StateFlow adapters
```

---

## Success Criteria

### If Migrating:
1. [ ] Pilot successful
2. [ ] Migration guide created
3. [ ] All ViewModels migrated
4. [ ] All tests pass
5. [ ] No performance regression
6. [ ] No memory leaks
7. [ ] Team trained

### If Hybrid Approach:
1. [ ] Guidelines documented
2. [ ] Example StateFlow ViewModel created
3. [ ] Team aware of both patterns

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Introduce bugs | High | High | Extensive testing |
| Performance issues | Medium | Medium | Benchmark early |
| Team confusion | High | Medium | Training, documentation |
| Incomplete migration | Medium | High | Use hybrid approach |

---

## Dependencies

**Depends On**: 
- H5 (Dispatcher Injection) - makes testing easier

**Blocks**: None

**Blocked By**: Other priorities (C1-C6, H1-H8)

---

## Approval Questions

### 🔥 **DECISION REQUIRED**:

1. **Should we migrate at all?**
   - A) Yes, full migration (4-5 weeks)
   - B) Hybrid approach (new code only) - **RECOMMENDED**
   - C) No, keep LiveData

2. **If migrating, when?**
   - A) Now (delays other critical tasks)
   - B) After H1-H8 complete
   - C) Next quarter

3. **Why migrate?**
   - KMP plans? (if so, migrate)
   - Just modernization? (hybrid approach)
   - Team learning? (pilot only)

---

## My Strong Recommendation

**Option B: Hybrid Approach**

1. **Document** that both patterns are OK
2. **Use StateFlow** for new features
3. **Keep LiveData** for existing code
4. **Reassess** in 6-12 months

**Don't spend 5 weeks on this when**:
- Security issues need fixing (C1)
- No tests exist (H1)
- Build is slow (C2)
- ProGuard rules missing (H2)

---

## Next Steps (If Approved)

### If Full Migration:
1. ✅ Create branch: `feature/M2-stateflow-migration`
2. ✅ Pilot on one feature
3. ✅ Get team feedback
4. ✅ Proceed or abort

### If Hybrid:
1. ✅ Document guidelines
2. ✅ Create example StateFlow ViewModel
3. ✅ Train team
4. ✅ Done in 1 day

### If No Migration:
1. ✅ Mark TODO as "Decided: Keep LiveData"
2. ✅ Document decision
3. ✅ Done in 1 hour

---

*Created: 2025-11-28*  
*Owner: Architecture Team + Product*  
*Estimated Completion: 1 day (hybrid) or 5 weeks (full)*  
*Status: ⏸️ **Awaiting Architecture Decision***

