---
id: cu-16
title: Plex API fixture pack + FakePlexServer
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, agentic]
dependencies: []
priority: high
milestone: m-1
---

## Description

Record real Plex responses (PIN login, resources, library sections, album+tracks with includeChapters, timeline/scrobble) into app/src/test/resources/plex-fixtures/; MockWebServer-backed JUnit rule. The hermetic-testing unlock (D10, CVR §9). Extend pattern to FakeAbsServer when cu-33.1 starts.

## Acceptance Criteria

- [ ] Sync/progress/download tests need no live server or credentials
- [ ] Full test suite runs offline in CI
