# Repo-side memory

Durable, **shareable** lessons from working on this project — the ones that survive a new machine
and belong to the repo rather than to one laptop.

**Everything here is committed and public to anyone holding the repo.** `./check-memory-safe.sh`
gates that: it refuses private LAN addresses, device serials, home paths, credentials, Plex
hostnames and emails. Run it before committing a memory, and see
[`docs/reference/12-agent-memory.md`](docs/reference/12-agent-memory.md) for what belongs here
versus in local auto-memory.

**Machine-specific and household facts stay out** — the tablet's address, the Plex server's name,
the owner's phone. Those live in local auto-memory
(`~/.claude/projects/<project>/memory/`), which is per-machine and never committed.

**A memory is sometimes a decision in disguise.** A memory records what is *true*; a decision
record states what we *chose*, why, and what it costs. When a memory states a choice among
alternatives that a future agent would cite to justify an action, promote it to
`backlog/decisions/` and leave a pointer behind — `chronicle-branch-base` → [[decision-23]] is the
worked example. The full test is in
[`docs/reference/12-agent-memory.md`](../docs/reference/12-agent-memory.md); product decisions stay
owner-only.

A memory records **what was true when it was written**. Before acting on one that names a file,
flag or mechanism, verify it still holds — and see `chronicle-coverage-gate-accumulates` for a
- [backlogmd-completed-search-gap](backlogmd-completed-search-gap.md) — Backlog.md does not index backlog/completed/ in `backlog search`; tracked upstream as issue 825
- [backlogmd-draft-filename-case](backlogmd-draft-filename-case.md) — Backlog.md drafts need a lowercase draft-<n> filename but an uppercase DRAFT-<n> frontmatter id
- [chronicle-auto-emulator](chronicle-auto-emulator.md) — How the Android Automotive emulator was set up headlessly for Chronicle, and why Gradle Managed Devices cannot do it
- [chronicle-branch-base](chronicle-branch-base.md) — Chronicle task branches base off feature/agentic-dev, not develop, until a release is cut — recorded as decision-23
- [chronicle-collapsed-sheet-isshown](chronicle-collapsed-sheet-isshown.md) — A collapsed bottom sheet keeps children VISIBLE at zero height, so isShown lies; measure the container before diagnosi
- [chronicle-commit-before-optimising](chronicle-commit-before-optimising.md) — Commit characterisation tests before changing the code they pin, or a scripted edit can delete the untracked test
- [chronicle-compose-adopted](chronicle-compose-adopted.md) — Compose accepted (decision-22) for Chronicle; the two gotchas that made a green test suite sit over a visibly broken s
- [chronicle-compose-device-only-traps](chronicle-compose-device-only-traps.md) — Two Compose failures a green unit suite cannot see: painterResource throwing on a shape drawable, and AnimatedVisibility composing hidden content
- [chronicle-compose-device-only-defects](chronicle-compose-device-only-defects.md) — Compose migrations ship defects no test can see — tint, clipping, two-colour drawables, and silently dropped controls;
- [chronicle-coverage-gate-accumulates](chronicle-coverage-gate-accumulates.md) — CORRECTED 2026-09-06 — coverage dips do NOT accumulate in either gate; both keep the higher floor. The real trap is a 
- [chronicle-device-check-catches-wiring](chronicle-device-check-catches-wiring.md) — A green Chronicle unit suite says nothing about fragment wiring; the library screen showed No books found over a full 
- [chronicle-doc-edits-in-worktree](chronicle-doc-edits-in-worktree.md) — Chronicle doc edits (CLAUDE.md, backlog/) must be made inside the task worktree, not the main checkout
- [chronicle-harness-trailer-conflict](chronicle-harness-trailer-conflict.md) — The harness injects a Claude-Session trailer that Chronicle's CLAUDE.md forbids; strip it before handing work over
- [chronicle-incidental-coverage](chronicle-incidental-coverage.md) — Coverage that comes from another component's test disappears when that component is deleted; measure the clean tree be
- [chronicle-no-task-ids-in-code](chronicle-no-task-ids-in-code.md) — Task ids are forbidden in code comments; the gate that enforces it only scans .kt
- [chronicle-playback-mainthread-cost](chronicle-playback-mainthread-cost.md) — Chronicle's playback main-thread cost is layout/draw, not data work — profiled 2026-09-04, and the named data findings
- [chronicle-prefs-cache-clobber](chronicle-prefs-cache-clobber.md) — Editing an Android app's SharedPreferences file while the app is running is silently reverted when the process dies
- [chronicle-profile-before-optimising](chronicle-profile-before-optimising.md) — Chronicle's performance tasks were written from TODOs, not measurements — cu-51's premise did not survive profiling, a
- [chronicle-research-2026-07](chronicle-research-2026-07.md) — Key strategic facts from the July 2026 Chronicle ownership/modernization research (full report in repo RESEARCH_FINDIN
- [chronicle-sabotage-rerun-tasks](chronicle-sabotage-rerun-tasks.md) — Sabotage-verifying a test in Chronicle needs --rerun-tasks, and the restore must be a separate tool call
- [chronicle-scoped-write-blind-spot](chronicle-scoped-write-blind-spot.md) — cu-127's SourceId scoping guards reads but not writes; check the write path stamps a resolved scope before believing a
- [chronicle-stateflow-conflation](chronicle-stateflow-conflation.md) — A StateFlow conflates equal consecutive values where a LiveData transformation re-emitted — null→null silently drops, 
- [chronicle-stateflow-testing](chronicle-stateflow-testing.md) — Testing a stateIn(WhileSubscribed) flow needs a subscriber AND a drained dispatcher; subscribing to two one at a time 
- [chronicle-vacuous-file-tests](chronicle-vacuous-file-tests.md) — Chronicle tests over cached-track files pass vacuously unless the fixture track has a real `media` path
- [chronicle-verify-research-claims](chronicle-verify-research-claims.md) — Research subagents on Chronicle produced both real verified bugs and confidently wrong corrections; check each claim a
- [chronicle-verify-resolved-version](chronicle-verify-resolved-version.md) — A dependency bump can compile green while resolving to the old version — check app:dependencies, and beware that a she
