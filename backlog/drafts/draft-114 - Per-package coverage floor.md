---
id: DRAFT-114
title: Per-package coverage floor
status: Draft
assignee: []
labels: [R2, agentic, testing]
dependencies: []
priority: medium
milestone: m-2
---

## Description

Found in the 2026-09-02 branch review. The single aggregate coverage number hides an inversion:
**coverage is highest where bugs are cheapest, and lowest where they are most expensive.**

Measured at 28.49% overall (now 28.98%):

| Package | Coverage | Instructions |
|---|---|---|
| `data/model` | 81.6% | 3,332 |
| `data/local` | 50.8% | 6,431 |
| `features/player` | **24.8%** | 9,515 |
| `features/download` | **10.2%** | 2,378 |
| `features/settings` | **7.9%** | 4,481 |
| `features/library` | **2.2%** | 3,174 |
| `features/home` | **0.0%** | 1,288 |
| `features/login` | **0.0%** | 3,059 |
| `features/collections` | **0.0%** | 2,679 |

Per class it is sharper still: `SettingsBackupRepo` 100% and `SettingsBackupKt` 99.5%, but
`SettingsViewModel` **0%** across 1,231 instructions — and that is where the review found the
crash-on-import bug ([[DRAFT-112]]). Same story for `MediaPlayerService` (0%, 1,745) and
`HomeViewModel` (0%, the [[cu-110]] bug site).

A single global ratchet cannot see this: a 0%-covered `features/home` sits next to an 82%
`data/model` and averages out to a passing number. The aggregate can even *rise* while the risky
packages get worse.

Note this is not a request for a coverage push. The suite is good — 609 tests, none hollow, none
disabled, real sabotage-verification in `RoomSchemaTest`. The problem is that the *gate* is blind
to where the coverage sits.

## Acceptance Criteria

- [ ] `coverage-ratchet.sh` (or a sibling) enforces a floor per package, baselined per package in
      a plain committed file so movements show up in a diff (D12 rule 6)
- [ ] Baselines are set at *current* values, so this is a ratchet and not a migration project
- [ ] A drop in any single package fails the gate even when the aggregate rises — **verified by
      deliberate sabotage**, per the m-0 rule
- [ ] Fix the aggregate ratchet's downhill walk while here: the tolerance permits −0.05% per run
      cumulatively because the baseline is not a high-water mark, and its comment claims to absorb
      "sub-0.01% jitter" — a 5× mismatch with the code
- [ ] `CLAUDE.md` describes the real behaviour of both gates
