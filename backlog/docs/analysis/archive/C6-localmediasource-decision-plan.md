# Task C6: LocalMediaSource Implementation Decision Plan

> **Archived.** Its task [[cu-15]] is Done; kept as historical context, not part of the
> active reference set.

**Task ID**: C6  
**Priority**: 🔴 Critical  
**Created**: 2025-11-28  
**Status**: Planning - **DECISION REQUIRED**

---

## Problem Statement

The `LocalMediaSource` class is a **completely unimplemented stub** with all methods throwing `TODO("Not yet implemented")`. This is a critical issue because:

1. **Production Code Risk**: Will crash if any code path tries to use it
2. **Dead Code**: Adds complexity with no value
3. **Maintenance Burden**: Must be maintained or removed
4. **Architectural Debt**: Suggests abandoned feature plan
5. **Confusion**: Misleading for developers (looks ready but isn't)

---

## Current State Analysis

### What I Found:

#### 1. **LocalMediaSource Implementation** (Complete Stub):

```kotlin
// data/sources/local/LocalMediaSource.kt
class LocalMediaSource : MediaSource {
    override val id: Long = MEDIA_SOURCE_ID_LOCAL  // ID = 2L

    companion object {
        const val MEDIA_SOURCE_ID_LOCAL: Long = 2L
    }

    // TODO: acquire the permissions needed somehow
    
    override val dataSourceFactory: DefaultDataSource.Factory
        get() = TODO("Not yet implemented")  // ❌ CRASHES

    override suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable> {
        TODO("Not yet implemented")  // ❌ CRASHES
    }

    override suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable> {
        TODO("Not yet implemented")  // ❌ CRASHES
    }

    override val isDownloadable: Boolean = false
}
```

#### 2. **MediaSource Interface Requirements**:

```kotlin
interface MediaSource {
    val id: Long  // ✅ Implemented (2L)
    val dataSourceFactory: DefaultDataSource.Factory  // ❌ TODO
    suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable>  // ❌ TODO
    suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable>  // ❌ TODO
    val isDownloadable: Boolean  // ✅ Implemented (false)
}
```

#### 3. **Usage Analysis**:

**NOT CURRENTLY USED** - Key findings:
- ❌ No references found in codebase (only 1 grep result - the file itself)
- ❌ Not registered with `SourceManager`
- ❌ Not injected anywhere
- ❌ No UI to enable/configure it
- ❌ No settings for local files
- ✅ Safe to remove without breaking anything

**Comparison - PlexMediaSource**:
- ✅ ID = 0L
- ✅ Fully implemented (though also has some TODOs for future features)
- ✅ Actively used throughout the app
- ✅ Registered and managed

#### 4. **What LocalMediaSource Would Need**:

If we were to implement it properly:

**Storage Permissions**:
- `READ_EXTERNAL_STORAGE` (API < 33)
- `READ_MEDIA_AUDIO` (API 33+)
- Runtime permission handling
- Scoped storage support (Android 11+)

**File Scanning**:
- Scan external storage for audiobook files
- Support formats: MP3, M4A, M4B
- Parse audio metadata (ID3 tags, etc.)
- Organize files into books and tracks
- Handle folder structures (author/book/files)

**Database Integration**:
- Store scanned books in Room
- Track file locations and metadata
- Handle file moves/deletes
- Sync database with filesystem

**UI Components**:
- Settings screen to enable local files
- File browser/selector
- Import progress indicator
- Manage local library
- Handle permission denials

**Data Source Factory**:
- Provide local file URIs to ExoPlayer
- Handle file:// scheme properly
- No authentication needed

**Estimated Implementation Effort**: 2-3 weeks (minimum)

---

## Risk Assessment

### If We Keep It (Implement):

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Complex implementation | High | High | 2-3 weeks dev time |
| Permission handling issues | High | High | Thorough testing |
| Scoped storage compatibility | Medium | High | Support Android 11+ |
| File parsing errors | Medium | Medium | Robust error handling |
| User confusion (Plex + Local) | Medium | Medium | Clear UI/UX |
| Increased complexity | High | Medium | More code to maintain |
| Limited demand | High | Low | May not be used |

### If We Remove It:

| Risk | Likelihood | Impact | Mitigation |
|------|------------|---------|------------|
| Future feature request | Medium | Low | Can re-implement if needed |
| Lost architecture | Low | Low | Design preserved if needed |
| Breaking future plans | Low | Low | No concrete plans evident |

---

## Decision Options

### Option A: **Remove LocalMediaSource** 🥇 **STRONGLY RECOMMENDED**

**Rationale**:
- ✅ Not currently used anywhere
- ✅ Complete stub with no value
- ✅ Eliminates crash risk
- ✅ Reduces maintenance burden
- ✅ Can always re-implement if needed
- ✅ Quick to execute (1 day)

**Pros**:
- Clean codebase
- No dead code
- No crash risk
- Fast resolution
- No complexity added

**Cons**:
- Removes future option (but can restore)
- Loses stub architecture (but simple to recreate)

**Effort**: 1 day (4-6 hours)

---

### Option B: **Implement LocalMediaSource Minimally** ⚠️ **NOT RECOMMENDED**

**Rationale**:
- Keep option for local files
- Implement basic functionality
- Enable future expansion

**Pros**:
- Feature available for users
- Supports offline use cases
- Architectural completeness

**Cons**:
- ❌ 2-3 weeks development time
- ❌ Complex permission handling
- ❌ Scoped storage challenges
- ❌ Limited demand (assumption)
- ❌ Maintenance overhead
- ❌ Testing complexity
- ❌ Delays other critical tasks

**Effort**: 2-3 weeks (80-120 hours)

---

### Option C: **Keep Stub, Hide from Use** ⚠️ **NOT RECOMMENDED**

**Rationale**:
- Preserve future option
- Document it's incomplete
- Ensure it's never called

**Pros**:
- Minimal immediate effort
- Preserves architecture

**Cons**:
- ❌ Dead code remains
- ❌ Crash risk if accidentally used
- ❌ Confuses developers
- ❌ Technical debt persists
- ❌ Doesn't solve the problem

**Effort**: 2-3 hours (documentation only)

---

## My Strong Recommendation: **Option A - Remove It**

### Reasoning:

1. **YAGNI Principle** (You Aren't Gonna Need It)
   - No evidence of demand
   - No concrete plans
   - Plex support is the core feature

2. **Risk vs Reward**
   - High implementation cost
   - Low expected usage
   - Can restore if proven needed

3. **Critical Path**
   - Other critical tasks waiting
   - Security issues need attention
   - Build performance improvements blocked

4. **Code Quality**
   - Eliminates crash risk
   - Reduces complexity
   - Improves maintainability

5. **Reversibility**
   - Git history preserves it
   - Simple interface to re-implement
   - Can add later if validated need

---

## Implementation Plan (Option A - Removal)

### Phase 1: Verification (2 hours)
**Risk**: Low

#### Tasks:
- [x] 1.1. Confirm no usage in codebase (DONE - verified above)
- [ ] 1.2. Search for any configuration/settings
- [ ] 1.3. Check for any TODO references to local files
- [ ] 1.4. Verify no UI elements for local files
- [ ] 1.5. Check git history for context

**Validation**:
- [ ] Absolutely no references to LocalMediaSource
- [ ] No future plans documented
- [ ] Safe to remove

---

### Phase 2: Remove Implementation (1 hour)
**Risk**: Very Low

#### Tasks:
- [ ] 2.1. Delete `LocalMediaSource.kt`
- [ ] 2.2. Remove from source control
- [ ] 2.3. Update any documentation that mentions it
- [ ] 2.4. Clean build to verify no errors

**Files to Delete**:
```
app/src/main/java/io/github/mattpvaughn/chronicle/data/sources/local/LocalMediaSource.kt
```

**Validation**:
- [ ] Project compiles
- [ ] No references remain
- [ ] Tests pass (if any exist)

---

### Phase 3: Update Documentation (1 hour)
**Risk**: Low

#### Tasks:
- [ ] 3.1. Add note in CONTRIBUTING.md about future local file support
- [ ] 3.2. Update architecture docs if needed
- [ ] 3.3. Add to git commit message explaining removal
- [ ] 3.4. Update any roadmap documents

**Documentation to Add**:

```markdown
## Future Features: Local File Support

### Status: Not Implemented

The app currently supports Plex servers only. Local file support was considered
but not implemented due to:
- Complexity of permission handling (scoped storage)
- Maintenance overhead
- Limited expected demand
- Focus on Plex integration

### If Local Files Are Needed

To implement local file support:
1. Create `LocalMediaSource` implementing `MediaSource` interface
2. Handle storage permissions (READ_MEDIA_AUDIO on Android 13+)
3. Scan external storage for audio files (MP3, M4A, M4B)
4. Parse metadata from ID3 tags
5. Store in Room database
6. Create UI for import/management
7. Test thoroughly on Android 11+ (scoped storage)

See git history (commit [HASH]) for previous stub implementation.

### Estimated Effort
2-3 weeks for basic implementation
```

---

### Phase 4: Testing & Validation (30 minutes)
**Risk**: Very Low

#### Tasks:
- [ ] 4.1. Clean build
- [ ] 4.2. Run tests
- [ ] 4.3. Launch app and verify functionality
- [ ] 4.4. Check for any runtime errors

**Validation**:
- [ ] App launches
- [ ] Plex sources work
- [ ] No crashes
- [ ] No warnings about missing classes

---

### Phase 5: PR & Documentation (30 minutes)
**Risk**: Low

#### Tasks:
- [ ] 5.1. Create PR with clear explanation
- [ ] 5.2. Reference this decision document
- [ ] 5.3. Note: Can be restored if needed
- [ ] 5.4. Update task tracking (mark C6 complete)

**Commit Message**:
```
Remove unimplemented LocalMediaSource stub

WHAT: Deleted LocalMediaSource.kt (complete stub with TODOs)

WHY:
- Not currently used anywhere in codebase
- All methods throw TODO("Not yet implemented")
- Represents potential crash risk if accidentally used
- Adds complexity with no value
- No evidence of demand or concrete plans

IMPACT:
- Zero user impact (feature never existed)
- Eliminates crash risk
- Reduces code complexity
- Cleans up technical debt

FUTURE:
- Can be re-implemented if validated demand emerges
- Git history preserves original stub for reference
- Design pattern is simple (implement MediaSource interface)
- Estimated 2-3 weeks for proper implementation

Related: Task C6 in project-analysis-and-tasks.md
```

---

## Alternative Plan (Option B - Implementation)

**ONLY IF PRODUCT CONFIRMS DEMAND**

<details>
<summary>Click to expand full implementation plan (2-3 weeks)</summary>

### Phase 1: Requirements & Design (3-4 days)
- Define supported file formats and structures
- Design permission flow (Android 11+ scoped storage)
- Design database schema for local files
- Design UI/UX for local file management
- Create technical specification

### Phase 2: Permission Handling (2-3 days)
- Implement runtime permission requests
- Handle Android 13+ READ_MEDIA_AUDIO
- Handle Android 11-12 scoped storage
- Add permission denial handling
- Test on multiple Android versions

### Phase 3: File Scanning (3-4 days)
- Implement MediaStore queries (recommended)
- Scan for audio files (MP3, M4A, M4B)
- Parse file metadata (MediaMetadataRetriever)
- Organize files into audiobooks and tracks
- Handle edge cases (malformed files, etc.)

### Phase 4: Database Integration (2-3 days)
- Extend Room schema if needed
- Store local audiobooks with source ID = 2L
- Store local tracks
- Handle file moves/deletes
- Test database operations

### Phase 5: DataSource Factory (1-2 days)
- Implement DefaultDataSource.Factory for local files
- Provide file:// URIs to ExoPlayer
- Test playback from local files

### Phase 6: UI Implementation (3-4 days)
- Settings screen to enable local files
- Import/scan UI
- Progress indicators
- File browser (optional)
- Error handling UI

### Phase 7: Testing (3-5 days)
- Unit tests for file scanning
- Integration tests for database
- UI tests for import flow
- Test on Android 11, 12, 13, 14
- Test various file structures

### Phase 8: Documentation (1 day)
- User documentation
- Developer documentation
- Architecture updates

**Total**: 18-30 days (not recommended without validated demand)

</details>

---

## Success Criteria (Option A)

### Must Have ✅:
1. [ ] LocalMediaSource.kt deleted
2. [ ] Project builds successfully
3. [ ] No references remain
4. [ ] Tests pass
5. [ ] Documentation updated

### Should Have ✅:
1. [ ] Clear commit message
2. [ ] Future guidance documented
3. [ ] PR approved

### Nice to Have 🎯:
1. [ ] Architecture doc updated
2. [ ] CONTRIBUTING.md note added

---

## Open Questions & Clarifications Needed

### 🔥 **CRITICAL DECISION REQUIRED** 🔥

**Q1: Should we remove LocalMediaSource or implement it?**

**Context**:
- Complete stub, never used
- No evidence of demand
- 2-3 weeks to implement properly
- Other critical tasks waiting

**Options**:
- **A) Remove (1 day)** - STRONGLY RECOMMENDED ✅
- **B) Implement (2-3 weeks)** - NOT RECOMMENDED ❌
- **C) Keep stub (2 hours)** - NOT RECOMMENDED ❌

**What I Need from You**:
1. ✅ **Approve removal** (Option A)
   - OR -
2. ❌ **Justify keeping/implementing** with:
   - Evidence of user demand
   - Business case for local files
   - Priority over other critical tasks
   - Resource allocation for 2-3 weeks

---

### 🤔 Question 2: Future Local File Support
**Q**: If removed, document how to implement in future?  
**Options**:
- A) Yes, add implementation guidance - **RECOMMENDED**
- B) No, developers can figure it out

**My Recommendation**: **Option A** - Leave breadcrumbs for future

---

### 🤔 Question 3: Search for Related TODOs
**Q**: Should we search for other local file TODOs?  
**Options**:
- A) Yes, clean up related comments
- B) Just remove the file

**My Recommendation**: **Option A** - Clean up comprehensively

---

## Dependencies

**Depends On**: 
- **PRODUCT DECISION** - Critical blocker!

**Blocks**: 
- If kept: Blocks progress on other critical tasks
- If removed: Unblocks C6 completion

**Blocked By**: None

---

## Estimated Effort Breakdown

### Option A (Removal) - RECOMMENDED:
| Phase | Task | Time |
|-------|------|------|
| 1 | Verification | 2h |
| 2 | Remove Implementation | 1h |
| 3 | Update Documentation | 1h |
| 4 | Testing | 0.5h |
| 5 | PR & Docs | 0.5h |
| **Total** | | **5h (< 1 day)** |

### Option B (Implementation) - NOT RECOMMENDED:
| Phase | Task | Time |
|-------|------|------|
| All Phases | See collapsed section above | **18-30 days** |

---

## Risk Mitigation

### If We Remove:
- ✅ **Low Risk**: Not currently used
- ✅ **Reversible**: Git history preserves it
- ✅ **Fast**: 1 day completion
- ✅ **Safe**: Can re-implement if needed

### If We Implement:
- ❌ **High Risk**: Complex permissions
- ❌ **High Cost**: 2-3 weeks minimum
- ❌ **Uncertain Value**: No validated demand
- ❌ **Blocks**: Other critical tasks

---

## Recommendation Summary

### ✅ **STRONGLY RECOMMEND: Option A - Remove LocalMediaSource**

**Why:**
1. Not used anywhere (verified)
2. Complete stub (crash risk)
3. No evidence of demand
4. Other critical tasks waiting
5. Can restore if validated need emerges
6. Fast resolution (1 day vs 3 weeks)

**Next Steps:**
1. **You decide**: Remove or Implement?
2. If Remove: I execute Phase 1-5 (1 day)
3. If Implement: Need product validation + 2-3 weeks

---

## Approval Required

**Before proceeding, I need your decision on:**

1. ✅ **APPROVE REMOVAL** (Option A) - Execute 5-phase plan (1 day)
   - OR -
2. ❌ **REQUIRE IMPLEMENTATION** (Option B) - Provide business justification + 2-3 weeks

3. 📝 **Document future implementation guidance?** (Yes recommended)

4. 📝 **Search for related local file TODOs?** (Yes recommended)

---

**WAITING FOR YOUR DECISION** - This is a **critical product decision** that determines:
- 1 day vs 2-3 weeks timeline
- Code quality vs feature scope
- Risk vs reward

**My professional recommendation as an engineer**: **Remove it (Option A)** - Clean code, fast resolution, can always restore if proven valuable.

---

*Created: 2025-11-28*  
*Owner: Product Team (Decision) + Engineering (Execution)*  
*Status: ⏸️ **BLOCKED ON PRODUCT DECISION***  
*Timeline: 1 day (removal) OR 2-3 weeks (implementation)*


