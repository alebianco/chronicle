---
id: decision-2
title: Harvest from fabiogermann Epilogue, develop independently
date: '2026-07-13'
status: accepted
---

## Context

The fabiogermann 'Chronicle Epilogue' fork is 214 commits ahead with fixes for our worst issue clusters, but raised minSdk to 33 and jumped toolchains (RESEARCH_FINDINGS §7/§10.2).

## Decision

Port patterns module-by-module with attribution (D12 rule 4 / [[decision-12]]); do NOT rebase onto Epilogue. Develop as an independent fork.

## Consequences

Full control + household minSdk 27 preserved; ongoing effort to track their releases; revisit if they upstream aggressively or adopt our differentiators first (kill-criterion in [[decision-9]]).
