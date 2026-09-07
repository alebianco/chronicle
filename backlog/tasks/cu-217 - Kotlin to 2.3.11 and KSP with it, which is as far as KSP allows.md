---
id: cu-217
title: "Kotlin to 2.3.11 and KSP with it, which is as far as KSP allows"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - debt
  - tooling
milestone: m-3
dependencies: 
  - cu-210
  - cu-216
priority: medium
---

## Description

Kotlin is at **2.2.10**, KSP at **2.2.10-2.0.2**. Both can move to the **2.3.11** line.

**Kotlin 2.4 is not reachable, and this is a hard ceiling rather than caution.** Measured against
Maven Central: Kotlin 2.4.20 is published, but **KSP's newest release is 2.3.11 — there is no KSP
for Kotlin 2.4.** KSP versions are pinned to a Kotlin version, and this project runs Room, Hilt,
Moshi *and* Ktorfit through it. Nothing here can outrun KSP.

That also settles a pin recorded in cu-195: **Ktorfit stays at 2.6.5** because 2.7.5 requires
kotlin-stdlib 2.4.0. When that bump was attempted it produced nine failures that never mentioned
Ktor — four `[MissingType]: Element 'Audiobook'`, a Room `BookDatabase` failure, and four Hilt
assisted-injection errors citing `error.NonExistentClass` for a class that resolved fine. Raising the
stdlib underneath KSP makes unrelated types disappear, so the symptom points nowhere near the cause.
Worth remembering if this bump misbehaves the same way.

## The thing to get right

**Everything that generates code moves together or not at all.** Room, Hilt/Dagger, Moshi and
Ktorfit all run on KSP, and a mismatch presents as those same misleading `MissingType` errors rather
than as a version complaint. So: bump Kotlin and KSP in one change, then check each processor's own
compatibility floor before assuming a green build means a correct one.

Bisect rather than guess if it breaks. That is what identified the Ktorfit ceiling: HEAD green,
compilerOptions alone green, each Ktor artifact green, `ktorfit-lib` red — and only then did the
module metadata give the reason.

## Acceptance Criteria

- [ ] Kotlin and KSP on the 2.3.11 line, bumped together
- [ ] Every KSP processor still generates: Room `_Impl`s, Hilt components, Moshi adapters, Ktorfit
      service impls. Confirmed by their **existence**, not merely by a green compile
- [ ] `--rerun-tasks` on the unit suite, so nothing passes from cache
- [ ] `./test_release_build.sh` passes its dex assertions — codegen changes are R8-relevant
- [ ] Whether Ktorfit can now move past 2.6.5 is **checked and recorded either way**
- [ ] The KSP ceiling is written down where the next person will look, so Kotlin 2.4 is not attempted
      again from memory
- [ ] `./verify.sh` green

## Notes

Sequenced after cu-216 deliberately: Room's codegen moved to Kotlin output in the 2.8 line, and
landing that bump separately means a Kotlin problem and a Room problem cannot be confused.

Closing status **Done** — build-level, no user-visible surface — provided the release build and the
generated-code checks hold.
