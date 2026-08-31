# Task H4: No CI Test Execution Resolution Plan

**Task ID**: H4  
**Priority**: 🟠 High (CI/CD Infrastructure)  
**Created**: 2025-11-28  
**Status**: Planning - Awaiting Approval

---

## Problem Statement

The CI workflow has a test job but it's unclear if tests actually run, and there's no test reporting or coverage metrics visible.

**Current State** (`.github/workflows/ci.yml`):
- ✅ Runs ktlintCheck (linting)
- ✅ Runs assembleDebug (build)
- ❓ Has "test" job but unclear if executes properly
- ❌ No test results published
- ❌ No coverage reports
- ❌ No coverage badge
- ❌ No PR comments with coverage

**Impact**:
- Can't see if PRs break tests
- No visibility into test coverage
- Tests might be passing/failing silently
- No enforcement of test requirements

---

## Solution Strategy

Enhance CI to properly run tests, generate coverage reports, and provide visibility through artifacts, badges, and PR comments.

---

## Implementation Plan

### Phase 1: Verify & Fix Test Execution (2 hours)

**Update `.github/workflows/ci.yml`**:

```yaml
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
      run: ./gradlew testDebugUnitTest --stacktrace

    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: test-results
        path: app/build/test-results/testDebugUnitTest/*.xml
        retention-days: 30
```

---

### Phase 2: Add Coverage Reporting (2 hours)

**Add JaCoCo step** (requires H1 JaCoCo setup):

```yaml
    - name: Generate coverage report
      if: always()
      run: ./gradlew jacocoTestReport

    - name: Upload coverage report
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: coverage-report
        path: app/build/reports/jacoco/jacocoTestReport/*
        retention-days: 30

    - name: Upload coverage to Codecov
      if: always()
      uses: codecov/codecov-action@v3
      with:
        files: app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
        fail_ci_if_error: false
```

---

### Phase 3: Add PR Coverage Comments (1 hour)

```yaml
    - name: Comment PR with coverage
      if: github.event_name == 'pull_request'
      uses: madrapps/jacoco-report@v1.6.1
      with:
        paths: app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
        token: ${{ secrets.GITHUB_TOKEN }}
        min-coverage-overall: 30
        min-coverage-changed-files: 50
        title: 'Code Coverage Report'
        update-comment: true
```

---

### Phase 4: Add Coverage Badge (30 minutes)

**Update README.md**:

```markdown
# Chronicle Audiobook Player

[![CI](https://github.com/USERNAME/chronicle/actions/workflows/ci.yml/badge.svg)](https://github.com/USERNAME/chronicle/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/USERNAME/chronicle/branch/main/graph/badge.svg)](https://codecov.io/gh/USERNAME/chronicle)

The best Android Audiobook Player for Plex.
```

---

### Phase 5: Make Tests Required (30 minutes)

**GitHub Branch Protection** (in repository settings):
```
Settings → Branches → Branch protection rules → main/develop

☑ Require status checks to pass before merging
  ☑ Unit Tests
  ☑ Lint

☑ Require branches to be up to date before merging
```

---

## Success Criteria

### Must Have ✅:
1. [ ] CI actually runs tests
2. [ ] Test results published as artifacts
3. [ ] Can see test failures in CI logs
4. [ ] Coverage reports generated
5. [ ] Tests required for PR merge

### Should Have ✅:
1. [ ] Coverage badge in README
2. [ ] PR comments with coverage
3. [ ] Codecov integration
4. [ ] Test results visible in GitHub UI

### Nice to Have 🎯:
1. [ ] Flaky test detection
2. [ ] Test performance tracking
3. [ ] Parallel test execution

---

## Dependencies

**Depends On**: 
- H1 (JaCoCo setup) for coverage reporting

**Blocks**: None

**Blocked By**: None

---

## Estimated Effort

| Phase | Time |
|-------|------|
| 1. Fix test execution | 2h |
| 2. Add coverage | 2h |
| 3. PR comments | 1h |
| 4. Badge | 0.5h |
| 5. Branch protection | 0.5h |
| **Total** | **6h (1 day)** |

---

## Approval Checklist

- [ ] **CI changes approved**: Update workflow
- [ ] **Codecov account**: Set up if needed (free for open source)
- [ ] **Branch protection**: Can enforce test requirements
- [ ] **Timeline OK**: 1 day

---

## Next Steps

1. ✅ Create branch: `feature/H4-ci-test-execution`
2. ✅ Update CI workflow
3. ✅ Test on PR
4. ✅ Add badge
5. ✅ Enable branch protection

---

*Created: 2025-11-28*  
*Owner: DevOps / Engineering*  
*Estimated Completion: 1 day*

