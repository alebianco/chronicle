---
id: decision-12
title: Development principles (owner rules)
date: '2026-07-13'
status: accepted
---

## Context

Owner rules for how the project is built, 2026-07-13. Full operational text in CLAUDE.md.

## Decision

(1) agentic-first, geared to Claude Code; (2) Claude is implementer AND architect — mandatory self-review, industry standards, owner rarely reviews code; (3) prefer maintained GPLv3-compatible third-party libraries over hand-rolled; (4) acknowledge upstream (mattttvaughn), Epilogue ports (Ported-from: trailers), design influences (Prologue et al.); (5) primary user = owner's household; (6) file over app — markdown in-repo, CI logic in verify.sh/Gradle, no GitHub-exclusive workflows; (7) open formats, DRM-free, licence-free tools, no proprietary SDKs/analytics — **rule 7 re-stated by [[decision-19]] and [[decision-20]] (2026-09-05)**: the ban is on *data extraction*, not on proprietary code as such (its three examples were all extraction SDKs while its wording banned a whole category), with a second route for data the user **explicitly opts into sending**. Ads, analytics and telemetry are barred regardless of consent; auth tokens never sync ([[decision-8]]).

## Consequences

Governs every task's definition-of-done; rule 6 produced [[decision-13]]; rule 7 produced [[decision-14]], was re-stated by [[decision-19]] (ban targets data extraction, not proprietary code), and gained a consent-gated route in [[decision-20]] (opt-in crash reporting and settings sync; ads/analytics barred regardless of consent; auth still never leaves the device per [[decision-8]]).
