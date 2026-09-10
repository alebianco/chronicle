---
id: cu-210
title: Toolchain and OSS adoption programme
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:58'
labels:
  - R3
  - architecture
  - tooling
milestone: m-2
dependencies:
  - cu-195
priority: high
ordinal: 103000
---

## Description

**A tracking task, not a unit of work.** The owner's answers to the cu-194 library survey turned into
a programme of ten tasks (cu-211 … cu-220), and this holds the sequence and the reasoning so neither
has to be re-derived.

**Deliberately consolidated.** A first pass produced seventeen tickets — one per decision — which the
owner rightly called overwhelming for what is largely configuration and version bumps. Merged to ten
by asking what a *unit of work* actually is: things worked and closed together share a ticket, and
sequencing that matters lives in **acceptance criteria** rather than in ticket boundaries. cu-214 is
the clearest case — four version bumps that must happen in order, as four staged ACs in one task.

## The sequence

| # | Task | Why here |
|---|---|---|
| 1 | **cu-212** CI and dependency hygiene | Dependabot, CodeQL, dependency-analysis. No app code. **Moved ahead of cu-211**: it adds the `feature/agentic-dev` CI trigger cu-211's instrumented job needs |
| 2 | **cu-211** close the launch-crash post-mortem | Wire the instrumented suite that already exists but never runs, **and** the rule that failed. Everything after this is unjustifiable until a repeat cannot reach a device |
| 3 | **cu-213** Pitest, working and wired | It **fails today**; fix before strengthening |
| 4 | **cu-214** the toolchain chain, four staged steps | Room → Kotlin/KSP → compileSdk 37/AGP 9 → Compose BOM. Sequential; step 3 is the riskiest thing here |
| 5 | **cu-215** detekt | Wants the newer toolchain under it |
| 6 | **cu-216** licences page, drop the GMS plugin | Compliance **and** removes an F-Droid blocker |
| 7 | **cu-217** kotlinx-serialization | Unpins the models from the JVM |
| 8 | **cu-218** Okio | **After cu-195** — same files |
| 9 | **cu-219** DataStore, three stages | Highest blast radius |
| 10 | **cu-220** Circuit + Molecule + Turbine | Last; a decision task that would replace `*Destination`. **Recommended decline; owner vetoed and adopted** — see [[decision-26]], staged as cu-229/230/231 |

## The measurements that set the order

Each of these was checked rather than assumed, and each one moved an answer:

- **KSP is the Kotlin ceiling.** KSP's newest release is `2.3.11`; there is none for Kotlin 2.4, and
  Room, Hilt, Moshi and Ktorfit all run on KSP. So Kotlin 2.4 is unreachable — which also means
  **Ktorfit stays at 2.6.5**, and the pin recorded in cu-195 is a constraint rather than caution.
- **Room's KMP support landed in 2.8.3**, not 2.8.1 where this project sits. That is the fact that
  settles SQLDelight, and it was found by looking rather than from memory.
- **Room 3.0 exists and is declined.** It is a deliberate breaking major, currently alpha, whose
  headline is JS/WASM. Against five databases, nineteen exported schemas and seven migration tests,
  with no second target yet asked for by cu-182, the risk buys nothing.
- **AGP 9.4.0 is available.** This was recorded as the way to clear decision-22's `compileSdk 37`
  gate — but that gate was misdescribed. compileSdk 37 landed in step 3 on AGP 8.13.2 and lifted
  nothing; the real constraint on Compose 1.12, lifecycle 2.11 and navigation-compose 2.10 is
  **AGP 9.1.0** (re-measured 2026-09-08, decision-22 amended). AGP 9 was then measured and
  **skipped** in cu-214: five incompatibilities, three of them silent, against one gain.
- **`play-services-oss-licenses` is declared *and* its plugin applied, and nothing uses it.** It is
  dead weight *and* a distribution blocker: decision-1 puts F-Droid first, and F-Droid does not
  accept Play Services dependencies. See cu-216.
- **Pitest does not currently run.** `./gradlew pitestDebug` fails in 51 s because
  `excludedTestClasses` is hand-maintained and there are now 62 Robolectric test classes — the
  Compose migration added most of them. Its own comment predicted this.

## What is declined, and stays declined

Recorded here so cu-194 can cite it rather than re-deriving:

| Candidate | Why not |
|---|---|
| **SQLDelight** | Room *is* the KMP path, confirmed at 2.8.3. Room is embedded across five databases with migration tests |
| **Room 3.0** | Alpha, breaking, and its benefit has no consumer until cu-182 names a target |
| **Kotlin 2.4 / Ktorfit 2.7.x** | No KSP for Kotlin 2.4 |
| **Paging 3** | cu-51 measured 10,000 books searching in 29 ms against a household library of 196 |
| **Tink** | A new crypto dependency for tokens already excluded from Auto Backup. Fewer moving parts around credentials, not more |
| **`EncryptedSharedPreferences`** | Deprecated upstream — and never used here; this project uses plain `SharedPreferences`, which is why cu-219 is a modernisation rather than a security fix |
| **Kotest, Mockito** | JUnit4 + MockK is established and the mock-vs-fake guidance is written down |
| **kotlinx-datetime** | `DurationFormat` is already pure over millis |
| **Gradle convention plugins** | Single-module; defer to cu-182 |
| **SaaS quality platforms** | decision-19 forbids data extraction, and decision-12 rule 7 already rejects coverage SaaS. CodeQL keeps analysis inside GitHub; a self-hosted Sonar CE or `mobsfscan` is the homelab option later |

## Acceptance Criteria

- [x] Every task exists, ordered, with its dependencies set — ten of them, consolidated from a
      first pass of seventeen
- [x] cu-211 lands **before any toolchain or library task** — it is the mitigation, and the
      programme is unjustifiable if a repeat of this session's blocker can still reach a device.
      Only cu-212 precedes it, because cu-211's CI job needs the branch trigger cu-212 adds
- [x] cu-214's four steps are committed and verified **separately** — each with its own
      `./verify.sh`, and CI green after steps 1 and 2. Step 3 shipped **compileSdk 37 on AGP
      8.13.2** and was device-verified on the tablet in both orientations; **AGP 9 itself was
      measured and skipped**, so nothing stacked on it. Step 4 is consequently recorded as skipped
      rather than done — Compose 1.12 and lifecycle 2.11 need AGP 9.1.0
- [x] No task in this programme is started while cu-195 has open device criteria — cu-211, cu-212
      and cu-213 ran under the carve-out, and **cu-195 closed Done on 2026-09-07**, so the rest of
      the programme is unblocked
- [x] cu-194 is closed by citing this task rather than repeating its reasoning — ten of its eleven
      criteria tick by citation. The eleventh, **screenshot testing**, is the one question this
      programme never answered: cu-194 deferred it until Compose landed, which has now happened, so
      it is carried to **cu-232** rather than force-closed. Ticking it by citation would have
      recorded an answer nobody gave
- [x] The portable-share figure cu-182 inherits is re-measured after cu-217 — **23.7% → 26.0%**
      (+2.3 pts) of `app/src/main`, recorded in `maintainability-review-2026-09.md` beside the
      original so the two are comparable

## Where the programme stands — 2026-09-08

All ten tasks are landed: cu-213, cu-215 and cu-220 closed **Done**; cu-211, cu-212, cu-214, cu-216,
cu-217, cu-218 and cu-219 sit **In Review**. Every acceptance criterion on this task is met.

Two threads continue past it and are tracked on their own tickets rather than holding this one open:

- **cu-229 / cu-230 / cu-231** — Turbine, Molecule and Circuit, staged per [[decision-26]]. These
  exist because the owner **vetoed** cu-220's recommended decline, so they are a consequence of this
  programme rather than part of its original ten.
- **cu-232** — screenshot testing, the one cu-194 question this programme never answered.

The intended exit point held: cu-216 was the last task after which stopping would have left the
project strictly better off with no half-migrated state, and everything past it was additive.

## Notes

**Sequencing risk to watch.** cu-214's step 3 (AGP 8 → 9) touches every build file and is the one
item here that can break the build in a way no unit test sees. Its ACs require it to be committed and
device-verified alone, and cu-211's launch-smoke test exists partly to give it that safety net.

**Do not let this become a rewrite by increments.** Five of these ten are config, tooling or version
bumps. Three are genuine library adoptions (cu-217, cu-218, cu-219), one is compliance work
(cu-216), and one is an architectural decision (cu-220). If the programme stalls, **stopping after
cu-216 leaves the project strictly better off with no half-migrated state** — that is the intended
exit point, not a fallback.
