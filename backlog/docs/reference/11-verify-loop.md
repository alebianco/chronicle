# The verify loop

```bash
./verify.sh            # the full gate
./verify.sh --quick    # inner loop while iterating: ktlint + unit tests + coverage
./verify.sh --format   # runs ktlintFormat first, then the full gate
./verify.sh --instrumented   # adds a 10th stage on two managed emulators
./verify.sh --mutation       # adds the PIT mutation score — reported, never fatal
```

**`verify.sh` *is* the definition of "the build is fine"** (D12 rule 6) — not CI, not a forge's
required checks. CI is a thin wrapper calling this same script, so the gate is identical on a
laptop and on any forge.

## What green means

| Stage | Check |
|---|---|
| 1 | `check-memory-safe` — nothing private in `backlog/memory/` |
| 2 | `check-agent-refs` — every `agentType` resolves; agent/command frontmatter well-formed |
| 3 | `ktlintCheck` — code style |
| 4 | `testDebugUnitTest` — unit tests |
| 5 | Coverage ratchet (two gates, see below) |
| 6 | `assembleDebug` — debug APK builds |
| 7 | `:app:detektDebug` — complexity, potential bugs, coroutine misuse |
| 8 | `lintDebug` — Android lint |
| 9 | `compileReleaseKotlin` — **the release variant compiles** |

Nothing less. The last stage exists because the debug and release source sets each provide their own
`DebugHooks` object: `DebugHooksContract` makes the compiler check the shape, but only for the
variant being built — so a drifted release twin used to pass every debug-only check and break the
first release build.

## detekt (stage 7)

Config: `config/detekt/detekt.yml` · baseline: `config/detekt/baseline-debug.xml` · pinned by
`DetektRuleSetTest`.

**Three rule sets, and only three: complexity, potential-bugs, coroutines.** `style`, `naming` and
`comments` are disabled explicitly, and `formatting` is absent rather than disabled — **ktlint owns
formatting.** Two linters with opinions about the same lines produce advice that contradicts
itself: ktlint's rule says wrap here, detekt's says do not, and no edit satisfies both. That is a
build someone deletes a stage from rather than fixes.

**The stage is `:app:detektDebug`, not `detekt`.** The bare task analyses without a classpath, and
roughly half the potential-bugs set — `UnsafeCallOnNullableType`, `ElseCaseInsteadOfExhaustiveWhen`,
`IgnoredReturnValue` — needs type resolution to decide anything. Without it those rules report
*nothing*, which is indistinguishable from a clean tree. Measured on this codebase: **31 findings
without type resolution, 120 with**.

**It is ratcheted, and the baseline is visible debt.** `config/detekt/baseline-debug.xml` holds
**52** findings as of its first generation. Those do not block; a 53rd does. Working the number down
is its own effort, not a side project of whatever change happens to touch the file. The current
composition:

| Rule | Count |
|---|---|
| `LongMethod` | 18 |
| `ElseCaseInsteadOfExhaustiveWhen` | 11 |
| `UnsafeCallOnNullableType` | 7 |
| `NestedBlockDepth` | 4 |
| `CyclomaticComplexMethod` | 3 |
| `ImplicitDefaultLocale` | 3 |
| `UnnecessarySafeCall` | 2 |
| `LargeClass`, `ComplexCondition`, `IteratorNotThrowingNoSuchElementException`, `ExitOutsideMain` | 1 each |

Regenerate with `./gradlew :app:detektBaselineDebug` — and only deliberately: regenerating absorbs
whatever new findings exist, which is exactly what the gate is meant to stop.

**Two rules are off despite being in an enabled set**, with the reasons recorded in `detekt.yml`
next to each: `UnreachableCode` (broken under 1.23.x — it flags the `return` of every
`?: return null`, 34 false positives and no true ones) and `NullableToStringCall` (correct but not
defects — all 34 were `Timber.e("… ${e.message}")`, where "null" in a log line is the honest
rendering of a null).

**Cost: ~12s** of analysis on top of an already-compiled debug variant. That is why it sits after
`assembleDebug` in the full gate and **not** in `--quick`: it needs the debug compile that `--quick`
deliberately skips, so putting it there would add the whole compile to the inner loop.

## The coverage ratchet

`coverage-ratchet.sh` checks JaCoCo instruction coverage **twice from one report**, and both
baselines are plain committed files so every movement is reviewable in a diff (D12 rule 6).

**Aggregate**, against `coverage-baseline.txt` — fails on a drop of more than **0.05%**. That
tolerance absorbs codegen jitter, and it *is* a high-water mark: the no-regression branch
deliberately does not rewrite the file, so a second consecutive dip is measured against the same
high number and fails. **Drops cannot accumulate.**

> An earlier doc claimed the opposite and a task was filed to "fix" it. The walk does not exist —
> the comment in the script was simply describing a 0.01% tolerance the code never had.

**Per package**, against `coverage-baseline-packages.txt` — fails when any single package drops
more than **0.50%**, even while the aggregate rises. The looser tolerance is because a small
package moves several tenths of a percent per instruction.

This gate exists because coverage here sits **backwards** — `data/model` above 80% next to
`features/collections` and `features/home` at 0% — so the average passes while the expensive
packages rot. A **new package is seeded and announced, never silently admitted**, and a departed
one is pruned.

Both ratchet *up* on a rise — **commit the changed file**. To lower either on purpose:
`./coverage-ratchet.sh --update`, and justify it in the commit message.

## Release builds

`./test_release_build.sh` — an R8/ProGuard smoke test (see CONTRIBUTING.md "Release Builds &
ProGuard"). Run it whenever touching ProGuard rules, reflection-adjacent code (`@Serializable`
models, Room entities), or dependencies.

It asserts **against the dex** that Room, Ktorfit, Dagger and `@Serializable` classes survived R8 —
these fail at runtime, not build time. The `@Serializable` scan carries a **count floor**: it keys
on an annotation name, and when the serializer changed the old `@JsonClass` pattern matched nothing
and the check passed while asserting over an empty set.

Keep rules are deliberately narrow: **prefer adding one precise rule over widening a
blanket `-keep`**, which silently exempts code from R8.

## Instrumented tests

`./verify.sh --instrumented` adds them as a 10th stage; `./gradlew
instrumentedCheckGroupGroupDebugAndroidTest` runs them directly.

Two Gradle Managed Devices: **API 27** (the minSdk floor, which catches a new API called without a
version guard) and **API 35**, both AOSP `arm64-v8a`.

**Opt-in, not in the default gate** — two emulators take minutes where the unit gate takes seconds.

The suite is `LoggedInLaunchTest`: three cases against the fixture server via `MockPlexMode`,
so **no credentials and no live server**. It is deliberately small; it exists to make the
Fragment/Activity/media-session layer reachable at all, not to cover it.

See the `device-verification` skill for the four traps it cost to learn.

## Mutation testing

`./verify.sh --mutation` adds the PIT score; `./gradlew pitestDebug` runs it directly and writes
`app/build/reports/pitest/debug/index.html`.

It answers a **different question from the coverage ratchet** — "would the tests notice if this code
changed?" rather than "was this line executed?". A surviving mutant is a change to production code
that no test objected to, which is how a vacuous test looks from the outside.

**Opt-in and never fatal.** Measured at roughly a minute on top of a full verify, so it is not in
the default gate and never in `--quick`. It has no score floor either: a threshold picked before the
first honest measurement is either vacuous or blocks the build on day one, and a gate that blocks on
day one gets disabled — worse than not having it. The stage prints the numbers so a floor can be
ratcheted from a real baseline later.

**Baseline at the time it was first made to run:** 472 mutations generated, 185 killed (39%), 122
survived, 165 with no coverage; test strength 60%, over an 18-class allowlist.

### The Robolectric exclusion is derived, not listed

PIT + Robolectric is broken upstream (koral--/gradle-pitest-plugin#80, open since 2022) and fails
**silently**, reporting false SURVIVED/NO_COVERAGE. A Robolectric class in PIT's scope therefore
does not break the run — it makes it lie about which tests are worthless.

The exclusion was once a hand-maintained list guarded only by a comment saying not to forget it. It
was forgotten: **62** Robolectric classes accumulated against **14** listed, one listed class no
longer existed, and `pitestDebug` failed outright for long enough that nobody noticed — `verify.sh`
stayed green throughout, because nothing ran PIT.

`robolectricTestClasses()` in `app/build.gradle.kts` now derives the list from the test sources, and
`PitestScopeTest` fails if that derivation stops matching the test tree. **Do not replace it with
literals**; the failure mode of forgetting is what the derivation removes.

`targetClasses` remains a deliberate allowlist — generated code (Room `_Impl`, Dagger factories)
produces thousands of meaningless mutants. Widening it is a separate judgement, not maintenance.

## Other measurement scripts

| Script | Purpose |
|---|---|
| `./capture-screens.sh <dir>` | Drives the app and screenshots the main screens |
| `./plex-session.sh {backup\|real\|mock\|status}` | Switch between a real Plex session and mock mode without `pm clear` |
| `./measure-audio-glitches.sh` | Audio glitch measurement |
| `./list-build-gates.sh` | Regenerates the enforced-rules table |
| `./check-agent-refs.sh` | Agent/command definitions resolve and are well-formed |
| `./check-memory-safe.sh` | Committed memory carries nothing private |
| `./setup-repo.sh --check` | Pre-commit hook, RTK trust and `local.properties` present |
| `./compare-package-coverage.py` | Per-package coverage comparison |

## Two traps

**Gradle's up-to-date checks make a sabotaged test look like it passed.** When verifying a guard by
sabotage, use `--rerun-tasks` — and restore the sabotage in a separate call.

**A green suite is not device verification.** 1301 green tests once missed "No books found" over a
full library. Open every tab after rewiring fragments.
