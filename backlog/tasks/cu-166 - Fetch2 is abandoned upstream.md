---
id: cu-166
title: 'Fetch2 is abandoned upstream; mirror the artifact'
status: To Do
assignee: []
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - trust
  - debt
milestone: m-2
dependencies: []
priority: medium
ordinal: 1000
---

## Description

Found during the R2 dependency audit (2026-09-05).

`com.github.tonyofrancis.Fetch:fetch2` 3.4.1 is **no longer maintained**. Verified: last commit
2024-12-03, 3.4.1 is the newest release, 121 open issues, one of which is literally titled *"This
repository seems to be out of maintenance."* Apache-2.0, so the licence is fine, and OSV reports
**no known CVEs**. The risk is maintenance and availability, not a vulnerability.

**cu-12's conclusion still holds; one of its premises does not.** cu-12 investigated migrating to
Media3 `DownloadManager` and correctly rejected it — Media3's `SimpleCache` uses an opaque layout
that would break `MoveSyncLocationWorker` and orphan every existing download. That reasoning is
unaffected. But cu-12 states *"Fetch2 is maintained: 3.4.1 is current"*, which was true then and is
false now. The recommendation is **not** to migrate.

Two aggravating factors make this worth acting on anyway:

1. It is delivered via **JitPack** (`settings.gradle.kts:14`), which builds from source on demand
   and offers no guarantee an artifact stays resolvable. A JitPack outage breaks the build outright.
2. It is woven into **11 files** and reaches the `MediaSource` seam, so a forced migration under
   time pressure would be expensive.

Alternatives are genuinely poor: Media3 is rejected for the reason above, and Android's platform
`DownloadManager` cannot attach per-request auth headers cleanly, which Plex requires.

## Acceptance Criteria

- [ ] The Fetch2 artifact is mirrored/vendored so a JitPack outage cannot break the build
- [ ] cu-12's stale "Fetch2 is maintained" premise is corrected in place, with its conclusion intact
- [ ] `CLAUDE.md` records that Fetch2 is unmaintained, so no future agent re-derives this
- [ ] No migration attempted — this task is explicitly about de-risking, not replacement
