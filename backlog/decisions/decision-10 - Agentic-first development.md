---
id: decision-10
title: Agentic-first development
date: '2026-07-13'
status: accepted
---

## Context

The 15-22 week debt + R0-R4 roadmap only ships if AI agents can implement/verify/land changes with minimal supervision, for a solo dev with a day job (COMMERCIAL_VIABILITY_REPORT §9).

## Decision

CLAUDE.md at root is the single source of truth; every change passes the headless verify loop; CI must never be green-but-no-op; agents never touch signing/billing/licence/branding/Play metadata without sign-off. 80/20 = [[cu-2]], [[cu-3]], [[cu-8]], [[cu-16]].

## Consequences

Cheap maintenance makes 'keep it free' ([[decision-9]]) more affordable, not less; raises the bar on doc truthfulness and test infrastructure.
