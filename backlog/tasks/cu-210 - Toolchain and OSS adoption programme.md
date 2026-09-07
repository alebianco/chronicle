---
id: cu-210
title: "Toolchain and OSS adoption programme"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - architecture
  - tooling
milestone: m-3
dependencies:
  - cu-195
priority: high
---

## Description

**A tracking task, not a unit of work.** The owner's answers to the cu-194 library survey turned into
a sixteen-item programme with a real dependency chain, so the sequence lives here and each item is
its own task (cu-211 … cu-226).

This exists because the ordering is not arbitrary and the reasons are easy to lose:

- Two items are **mitigations for a defect this session shipped** — a 100% launch crash that 1,678
  green unit tests did not catch — and they go first for that reason.
- Four items form a **toolchain chain** where each unlocks the next, and one of them (AGP 9) is the
  riskiest change on the list.
- Two library adoptions must wait for cu-195 to close because they touch the same files.

## The sequence

| # | Task | Why here |
|---|---|---|
| 1 | cu-211 launch-smoke test | Mitigates the crash the unit suite missed |
| 2 | cu-212 "a test may not disable a production check" | Mitigates *why* it was missed |
| 3 | cu-213 Dependabot + CI trigger fix | Config only; makes every later bump cheaper |
| 4 | cu-214 fix Pitest's stale exclusion | It **fails today**; fix before strengthening |
| 5 | cu-215 CodeQL | Free, additive, no code change |
| 6 | cu-216 Room 2.8.1 → 2.8.3 | Small; settles the SQLDelight question |
| 7 | cu-217 Kotlin → 2.3.11 + KSP | A ceiling, not a choice — see below |
| 8 | cu-218 compileSdk 37 + AGP 9.x | **Riskiest.** Lands alone, device-verified |
| 9 | cu-219 Compose BOM + lifecycle 2.11 | Only possible after cu-218 |
| 10 | cu-220 detekt | Wants the newer toolchain under it |
| 11 | cu-221 Dependency Analysis plugin | Pairs with cu-213: newer vs unused |
| 12 | cu-222 licences page, drop the GMS plugin | Compliance **and** removes an F-Droid blocker |
| 13 | cu-223 kotlinx-serialization | Unpins the models from the JVM |
| 14 | cu-224 Okio | **After cu-195** — same files |
| 15 | cu-225 DataStore, all three stages | Highest blast radius; wants detekt and the guards in place |
| 16 | cu-226 Circuit + Molecule + Turbine | Last; replaces `*Destination` |

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
- **AGP 9.4.0 is available**, so decision-22's `compileSdk 37` gate can actually be cleared.
- **`play-services-oss-licenses` is declared *and* its plugin applied, and nothing uses it.** It is
  dead weight *and* a distribution blocker: decision-1 puts F-Droid first, and F-Droid does not
  accept Play Services dependencies. See cu-222.
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
| **`EncryptedSharedPreferences`** | Deprecated upstream — and never used here; this project uses plain `SharedPreferences`, which is why cu-225 is a modernisation rather than a security fix |
| **Kotest, Mockito** | JUnit4 + MockK is established and the mock-vs-fake guidance is written down |
| **kotlinx-datetime** | `DurationFormat` is already pure over millis |
| **Gradle convention plugins** | Single-module; defer to cu-182 |
| **SaaS quality platforms** | decision-19 forbids data extraction, and decision-12 rule 7 already rejects coverage SaaS. CodeQL keeps analysis inside GitHub; a self-hosted Sonar CE or `mobsfscan` is the homelab option later |

## Acceptance Criteria

- [x] Every task cu-211 … cu-226 exists, ordered, with its dependencies set
- [ ] cu-211 and cu-212 land **first** — they are the mitigations, and the programme is
      unjustifiable if a repeat of this session's blocker can still reach a device
- [ ] cu-218 (AGP 9) lands **alone** and is device-verified before cu-219 stacks on it
- [ ] No task in this programme is started while cu-195 has open device criteria, except
      cu-211 … cu-215, which do not touch app code
- [ ] cu-194 is closed by citing this task rather than repeating its reasoning
- [ ] The portable-share figure cu-182 inherits is re-measured after cu-223

## Notes

**Sequencing risk to watch.** cu-218 (AGP 8 → 9) touches every build file and is the one item here
that can break the build in a way no unit test sees. It is deliberately isolated, and cu-211's
launch-smoke test exists partly so that bump has a device-level safety net.

**Do not let this become a rewrite by increments.** Twelve of these sixteen are config, tooling or a
version bump. Three are genuine library adoptions (cu-223, cu-224, cu-225) and one is an
architectural change (cu-226). If the programme stalls, stopping after cu-222 leaves the project
strictly better off with no half-migrated state.
