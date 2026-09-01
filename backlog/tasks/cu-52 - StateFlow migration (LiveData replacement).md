---
id: cu-52
title: StateFlow migration (LiveData replacement)
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R2, architecture, trust]
dependencies: []
priority: high
milestone: m-2
---

## Blocker cleared (2026-08-31)

cu-58 is Done, so this is unblocked — but it still needs an owner decision on *whether* to migrate at all (see below).

## Description

M2: migrate ViewModels/repositories from LiveData to StateFlow. Upstream's own todo flags this as uncertain ('may not be worth it if LiveData works well'). Depends on dispatcher injection (cu-15). CLAUDE.md currently mandates LiveData — this draft is the trigger to revisit that convention, not a committed task.

Analysis: [`M2-stateflow-migration-plan.md`](../docs/analysis/M2-stateflow-migration-plan.md).

## Sequencing (owner decision 2026-08-30)

Third in the UI-layer sequence: **cu-58 (DataBinding→ViewBinding) → cu-8 (KAPT→KSP) → this**.

Deliberately kept out of cu-58. The two are coupled only at the XML boundary: 10 files set
`lifecycleOwner` so layouts can observe LiveData. Once cu-58 moves observation into Kotlin, those call
sites are `liveData.observe(viewLifecycleOwner) { … }` — which works fine and does **not** require
StateFlow. Bundling them would have meant ~30 layouts + 73 `MutableLiveData` declarations + 35
`observe` sites in one change against 3.76% coverage.

Scope when it starts: 28 files reference LiveData, 73 `MutableLiveData` declarations, 35 `observe`
call sites.

## Owner decision (2026-09-01): migrate, promoted to R2

The open question below is **answered: yes**. Promoted from R4 draft to an R2 task on *correctness*
grounds rather than tidiness — the argument changed after the cu-73 device session.

Three of the fifteen device-only bugs were async-write races, and the mini-player one was fixed by
turning seven `postValue` calls into `value =`. **72 `postValue` sites remain.** `postValue` is
asynchronous and coalesces, so a read-after-write sees a stale value and two posts in the same tick
collapse to one — the shape of every one of those three bugs. The worst remaining form is
read-modify-write, e.g. `SettingsViewModel.kt:106` doing `postValue(it.copy(...))`.

StateFlow makes that class of bug structurally impossible: `value` is synchronous, and
`MutableStateFlow.update` is atomic. That is why this is worth doing now, and why it is *not* a
cosmetic migration.

Sequencing note: the prerequisites named below are both Done (cu-58, cu-8), so this is unblocked.
Do it **after** cu-33 if the two collide, since cu-33 changes the same repository seams.

### Original open question, kept for the record

Unlike cu-58, nothing is blocked by LiveData and it is not deprecated. Upstream's own note ("may not
be worth it if LiveData works well") stands on its own terms — the postValue race record is what
overrides it.

## Acceptance Criteria

- [x] Decision recorded: migrate (owner, 2026-09-01)
- [ ] Pilot one feature end to end before any rollout — `features/home` is the smallest with a real
      `postValue` (`HomeViewModel.kt:94, 137, 147, 150`)
- [ ] No `postValue` remains in a migrated file; a read-modify-write uses `update {}`
- [ ] A guard test fails the build on a new `postValue` in migrated packages, in the style of
      `InternalApiUsageTest` — otherwise the sites creep back
- [ ] CLAUDE.md convention 3 updated to match (it currently mandates LiveData and forbids mixing)
- [ ] Phased rollout: remaining 28 LiveData files, 73 `MutableLiveData` declarations, 35 `observe`
      sites
