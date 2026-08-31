# Task H6: Delicate API Usage Without Suppression Resolution Plan

> **Archived.** Its task [[cu-15]] is Done; kept as historical context, not part of the
> active reference set.
*Estimated Completion: 1 day (after C4/C5)*
*Owner: Engineering Team*  
*Created: 2025-11-28*  

---

**This task is primarily resolved by C4 and C5** - only minimal verification needed afterward.

---

5. ✅ Document any remaining usage
4. ✅ Add suppression if truly needed
3. ✅ Verify no delicate APIs
2. ⏸️ Wait for C5 completion
1. ⏸️ Wait for C4 completion

## Next Steps

---

- [ ] **Suppression guidelines**: If needed, must document why
- [ ] **Verification planned**: Check after C4/C5
- [ ] **Depends on C4/C5**: Understood this waits for them

## Approval Checklist

---

| **Expected** | **1h** |
| If suppression needed | 2h (add @OptIn + docs) |
| If C4/C5 resolve it | 1h (verification only) |
|----------|------|
| Scenario | Time |

## Estimated Effort

---

**Blocked By**: C4, C5

**Blocks**: None

- **C5** (InternalCoroutinesApi removal) - Primary
- **C4** (GlobalScope removal) - Primary
**Depends On**: 

## Dependencies

---

1. [ ] Lint rule preventing future delicate API usage
### Nice to Have 🎯:

2. [ ] All structured concurrency
1. [ ] No delicate APIs remaining
### Should Have ✅:

4. [ ] If delicate APIs used, properly annotated with docs
3. [ ] No delicate API warnings in build
2. [ ] C5 completed (InternalCoroutinesApi removed)
1. [ ] C4 completed (GlobalScope removed)
### Must Have ✅:

## Success Criteria

---

4. Plan to remove in future (if applicable)
3. Link to issue/discussion
2. What alternatives were considered
1. Why delicate API is needed
**Documentation Required**:

```
}
    }
        // ... use carefully
        val job = Job()
    private fun someFunction() {
    @DelicateCoroutinesApi
     */
     * @see [GitHub Issue #123] for discussion
     * 
     * This is necessary because [reason].
     * Uses [Job()] to manually manage connection lifecycle.
    /**
) {
    // ...
class CachedFileManager @Inject constructor(
@OptIn(DelicateCoroutinesApi::class)
// If truly needed, add with documentation
```kotlin

**Only if delicate APIs still required**:

### Phase 3: Add Suppression If Needed (1 hour)

---

```
grep -r "DelicateCoroutinesApi" app/src/main --include="*.kt"
grep -r "\.getCompleted()" app/src/main --include="*.kt"
grep -r "\.isActive" app/src/main --include="*.kt"
grep -r "Job()" app/src/main --include="*.kt"
# Search for delicate API usage
```bash

After C4/C5:

### Phase 2: Verify No Delicate APIs Remain (1 hour)

---

**Expected outcome**: Delicate API warnings resolved naturally.

- Use structured concurrency
- Inject proper `CoroutineScope`
- Replace internal coroutine APIs with public ones
- Remove `GlobalScope` usage
C4 and C5 will:

### Phase 1: Complete C4 & C5 First (See those tasks)

## Implementation Plan

---

**Fallback**: If delicate APIs still needed, add proper `@OptIn` with documentation

**Primary**: Resolve through C4 and C5 refactoring (removing need for delicate APIs)

## Solution Strategy

---

**Related**: This is addressed as part of **C4 (GlobalScope Removal)** and **C5 (InternalCoroutinesApi Removal)**

- May generate compiler warnings
- No documentation explaining delicate API usage
- No `@OptIn(DelicateCoroutinesApi::class)` annotations
- Uses `Job()`, `Deferred.isActive`, `Deferred.getCompleted()` (delicate APIs)
**Current State**:

`CachedFileManager.kt` uses delicate coroutine APIs without proper `@OptIn` annotations or documentation explaining why.

## Problem Statement

---

**Status**: Planning - Awaiting Approval
**Created**: 2025-11-28  
**Priority**: 🟠 High (Code Safety)  
**Task ID**: H6  


