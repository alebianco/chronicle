---
id: cu-215
title: "CodeQL security scanning, free and inside GitHub"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - trust
milestone: m-3
dependencies: 
  - cu-210
priority: medium
---

## Description

Owner ask: a Sonar-like platform for security and quality analysis, free, ideally something GitHub
offers. **GitHub code scanning with CodeQL is free for public repositories**, supports Kotlin
natively, and ships Android-specific security queries.

It also fits this project's constraints in a way a hosted platform would not: analysis runs inside
GitHub Actions and results stay in the repository's security tab. **decision-19** forbids data
extraction and **decision-12 rule 7** already rejects third-party coverage SaaS — the same reasoning
would reject a SaaS quality platform, and CodeQL sidesteps it.

## What to do

Add a `codeql.yml` workflow (or extend `ci.yml`) with the language set to `java`, which is how Kotlin
analysis is enabled. Kotlin is a compiled language for CodeQL's purposes, so the workflow builds the
project — which means it needs the same JDK and Gradle cache setup `ci.yml` already has.

Scope it to the same branches as CI, including `feature/agentic-dev` (see cu-213 — that trigger is
currently missing).

## What this is and is not

**Is:** semantic security analysis. Taint tracking, injection paths, insecure Android patterns —
CodeQL's Android queries cover things like `webview-addjavascriptinterface` and JavaScript-enabled
WebSettings.

**Is not** a replacement for detekt (cu-220), which is quality and complexity, nor for the coverage
ratchet. They answer different questions and all three are cheap.

**Not a quality-debt tracker either.** If the owner later wants Sonar-style tracking over time, the
options that satisfy decision-19 are a **self-hosted Sonar CE** or **`mobsfscan`** (open source,
Docker, scans Kotlin and Android XML) once a homelab exists. Recorded here so the choice is not
re-derived.

## The thing to get right

**A scanner that reports nothing is indistinguishable from one that is not running.** So this task
must confirm the workflow actually analysed Kotlin — a run whose log shows the CodeQL database being
built over this project's sources, not merely a green tick. A misconfigured language setting produces
a passing job that scanned nothing.

## Acceptance Criteria

- [ ] A CodeQL workflow runs on push and pull request for the CI branches, `feature/agentic-dev`
      included
- [ ] Verified by observation that it **analysed Kotlin sources** — the run log or the database
      summary, not just a green check
- [ ] Any finding it reports is triaged: fixed, or dismissed with a reason in the security tab.
      An untriaged backlog of alerts is the same as no scanner
- [ ] Nothing leaves GitHub; no third-party account is created (decision-19)
- [ ] The Sonar CE / `mobsfscan` option is recorded for the homelab case, so it is not researched
      twice

## Notes

Closing status **In Review**: whether the findings are worth acting on is the owner's call, and the
first run's output is the only way to know.
