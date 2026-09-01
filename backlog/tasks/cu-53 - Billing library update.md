---
id: cu-53
title: Billing library update
status: Won't Do
assignee: []
created_date: '2026-07-13'
labels: [R4, governance]
dependencies: []
priority: low
milestone: m-4
---

## Description

M3: Play Billing library update. DORMANT by D4/D9 — the IAP gate ships disabled and the billing plumbing is on the never-touch list. Only becomes a task if D9's revisit triggers fire (≥5k MAU, differentiators shipped, P.IVA willingness, employer cleared). Kept as a draft so the context isn't lost.

Analysis: [`M3-billing-library-update-plan.md`](../docs/analysis/archive/M3-billing-library-update-plan.md).

## Won't do (2026-08-31)

Obsolete. This draft assumed the IAP plumbing stayed dormant-but-present and would need updating if
D9's revisit triggers ever fired.

Neither premise holds. [[decision-15]] withdrew those triggers permanently — no monetization of any
kind, ever — and cu-60 deleted the billing code outright: `ChronicleBillingManager`, `PREMIUM_IAP_SKU`,
the Google-IAP dependency, its ProGuard rules and the Play Billing manifest declaration. A grep for
`billing` across `app/src/main` and the version catalog now returns zero hits.

There is no library left to update. Closed rather than left as a draft that reads like pending work.

## Filing note (2026-09-01)

Moved out of `backlog/drafts/` because drafts are *ideas awaiting owner triage* and this is already
triaged — the answer is no, permanently, and the reasoning is recorded above.

Filed under `tasks/` as a closed record rather than as a new file in `backlog/decisions/`: the
decision that settles it ([[decision-15]]) already exists and is the owner's, and billing is on the
never-touch list. Nothing here needed a new decision record — only somewhere honest to rest.
