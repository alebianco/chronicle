---
id: cu-52
title: StateFlow migration (LiveData replacement)
status: Draft
assignee: []
created_date: '2026-07-13'
labels: [R4, architecture]
dependencies: []
priority: low
milestone: m-4
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

## Open question / why this is still a draft

Still needs an owner decision on *whether* to migrate at all — unlike cu-58, nothing is blocked by
LiveData and it is not deprecated. Upstream's own note ("may not be worth it if LiveData works well")
stands. Promote to a task only if the answer is yes.

## Acceptance Criteria (provisional)

- [ ] Decision recorded: migrate or stay on LiveData
- [ ] If migrate: pilot one feature, then phased rollout
- [ ] CLAUDE.md convention updated to match
