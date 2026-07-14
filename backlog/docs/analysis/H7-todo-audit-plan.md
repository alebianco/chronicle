# Task H7: TODO Items in Critical Paths Resolution Plan

**Task ID**: H7  
**Priority**: 🟠 High (Incomplete Features)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The codebase contains 20+ TODO comments with no tracking, some in critical production code paths.

**Current State**:
- TODOs scattered throughout codebase
- No GitHub issues tracking them
- No prioritization or categorization
- No plan to address them
- Some may be obsolete

**Impact**:
- Incomplete features forgotten
- Technical debt hidden
- No visibility for team
- May confuse new developers

---

## Solution Strategy

**Phase 1**: Audit all TODOs  
**Phase 2**: Categorize and prioritize  
**Phase 3**: Remove, implement, or track  
**Phase 4**: Add linting to enforce format

---

## Implementation Plan

### Phase 1: Audit All TODOs (4 hours)

**Find all TODOs**:
```bash
grep -rn "TODO" app/src/main --include="*.kt" > todos.txt
grep -rn "FIXME" app/src/main --include="*.kt" >> todos.txt
grep -rn "HACK" app/src/main --include="*.kt" >> todos.txt
```

**Create spreadsheet**:

| File | Line | TODO Text | Category | Priority | Action |
|------|------|-----------|----------|----------|--------|
| CachedFileManager.kt | 18 | acquire permissions | Critical | High | Implement |
| PlexConfig.kt | 32 | merge into PlexMediaSource | Refactor | Low | Track |
| ... | ... | ... | ... | ... | ... |

---

### Phase 2: Categorize (2 hours)

**Categories**:
1. **Remove** - Obsolete, already done, no longer relevant
2. **Quick Fix** - < 1 hour, can do immediately
3. **Feature** - New functionality, needs planning
4. **Refactor** - Technical debt, needs issue
5. **Investigate** - Need more info/research

**Priority**:
- **Critical** - Affects core functionality
- **High** - Important but not breaking
- **Medium** - Nice to have
- **Low** - Someday/maybe

---

### Phase 3: Execute (1-2 days)

**Actions**:

**For "Remove"** (obsolete):
```bash
# Simply delete the TODO line
# Example: "TODO: test this" after tests are added
```

**For "Quick Fix"** (< 1 hour each):
```kotlin
// Before
// TODO: use constant instead of magic number
val timeout = 5000

// After
private const val TIMEOUT_MS = 5000
val timeout = TIMEOUT_MS
```

**For "Feature/Refactor"** (create issues):
```markdown
# GitHub Issue Template
Title: [TODO] Short description

**Original TODO**: 
File: path/to/file.kt:123
Text: "TODO: description"

**Context**:
What is this about?

**Proposed Solution**:
How should we address this?

**Priority**: High/Medium/Low

**Effort**: Small/Medium/Large

Labels: technical-debt, todo
```

**Update code**:
```kotlin
// Before
// TODO: implement local media source

// After  
// TODO(#456): Implement local media source support
// See: https://github.com/user/chronicle/issues/456
```

---

### Phase 4: Add Linting Rule (2 hours)

**Option A - EditorConfig**:
```ini
# .editorconfig
[*.{kt,kts}]
# Require TODO format: TODO(#123): Description
# This is more documentation than enforcement
```

**Option B - Custom Detekt Rule**:
```yaml
# detekt.yml
formatting:
  TodoFormat:
    active: true
    pattern: 'TODO\(#\d+\):'
```

**Option C - Pre-commit Hook**:
```bash
# pre-commit hook
#!/bin/bash
# Reject commits with improperly formatted TODOs

IMPROPERLY_FORMATTED=$(git diff --cached --name-only | \
  xargs grep -n "TODO" | \
  grep -v "TODO(#[0-9]*)")

if [ -n "$IMPROPERLY_FORMATTED" ]; then
  echo "❌ Found improperly formatted TODOs:"
  echo "$IMPROPERLY_FORMATTED"
  echo ""
  echo "Format should be: TODO(#123): Description"
  exit 1
fi
```

---

### Phase 5: Document Guidelines (1 hour)

**Add to CONTRIBUTING.md**:

```markdown
## TODO Comments

### Format

All TODO comments must link to a GitHub issue:

```kotlin
// ✅ Good
// TODO(#456): Implement feature X
// See: https://github.com/user/chronicle/issues/456

// ❌ Bad
// TODO: implement feature X
```

### When to Use

- **Do**: Use TODO for planned work with a tracking issue
- **Don't**: Use TODO for immediate fixes (just fix it)
- **Don't**: Use TODO without creating an issue

### Process

1. Create GitHub issue describing the work
2. Add TODO comment linking to issue
3. Reference issue in code
4. Close issue when TODO resolved
```

---

## Known TODOs (Examples from codebase)

Based on C6 analysis, here are some known TODOs:

1. **LocalMediaSource.kt** - Everything (`TODO: Not yet implemented`)
   - **Action**: Addressed in C6 decision (remove)

2. **PlexConfig.kt** - "merge behavior into PlexMediaSource"
   - **Action**: Create refactoring issue

3. **CachedFileManager.kt** - "acquire permissions somehow"
   - **Action**: Investigate permission requirements

4. **Various** - "Constructor inject Dispatchers"
   - **Action**: Addressed in H5

---

## Success Criteria

### Must Have ✅:
1. [ ] All TODOs audited and categorized
2. [ ] Obsolete TODOs removed
3. [ ] Quick fixes implemented
4. [ ] GitHub issues created for remaining
5. [ ] Linting rule added
6. [ ] Guidelines documented

### Should Have ✅:
1. [ ] Zero untracked TODOs
2. [ ] Pre-commit hook prevents new untracked TODOs
3. [ ] Template for TODO issues

### Nice to Have 🎯:
1. [ ] Dashboard showing all TODOs
2. [ ] Automated issue creation from TODOs

---

## Expected Results

**Before**:
- 20+ untracked TODOs
- No visibility
- No plan

**After**:
- 0 untracked TODOs
- 5-10 GitHub issues
- 5-10 removed (obsolete)
- 3-5 fixed immediately
- Enforcement in place

---

## Dependencies

**Depends On**: None

**Blocks**: None

**Blocked By**: None

---

## Estimated Effort

| Phase | Time |
|-------|------|
| 1. Audit | 4h |
| 2. Categorize | 2h |
| 3. Execute | 16h (2 days) |
| 4. Linting | 2h |
| 5. Document | 1h |
| **Total** | **25h (3 days)** |

---

## Approval Checklist

- [ ] **Audit approach OK**: Spreadsheet tracking
- [ ] **Can create issues**: GitHub issue creation approved
- [ ] **Quick fixes in scope**: Can fix < 1h items immediately
- [ ] **Linting rule preferred**: Pre-commit hook, detekt, or editorconfig?
- [ ] **Timeline OK**: 3 days

---

## Next Steps

1. ✅ Create branch: `feature/H7-todo-audit`
2. ✅ Run audit (Phase 1)
3. ✅ Categorize (Phase 2)
4. ✅ Create spreadsheet for review
5. ✅ Get approval on actions
6. ✅ Execute Phase 3
7. ✅ Add enforcement

---

*Created: 2025-11-28*  
*Owner: Engineering Team*  
*Estimated Completion: 3 days*

