---
id: cu-2
title: Agent entry point and doc truth (CLAUDE.md)
status: Done
assignee: []
created_date: '2026-07-13'
labels: [R0, agentic]
dependencies: []
priority: high
milestone: m-0
---

## Description

CLAUDE.md at root as single source of truth (D10/D12); AGENTS.md pointer; copilot files reduced to pointers; stale NOTES.md rewritten; KSP lie removed. Landed 2026-07-13.

## Acceptance Criteria

- [x] One source of truth; no doc claims contradicted by build files
- [x] Agent reading only CLAUDE.md can run the verify loop
