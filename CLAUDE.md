# CLAUDE.md — Chronicle Unabridged

Android audiobook player for self-hosted libraries: **Plex first**, Audiobookshelf and local
files/WebDAV planned (backlog D11). GPLv3 fork of
[mattttvaughn/chronicle](https://github.com/mattttvaughn/chronicle). Free forever, no monetization
(D9).

This file is the **entry point**, deliberately short. The durable knowledge lives in
`backlog/docs/reference/` and in `.claude/skills/`, which load automatically when the work matches.
**If this file contradicts the code, the code wins** — then fix this file in the same PR.

---

## Read this first

| You are… | Read |
|---|---|
| Working on anything | [`reference/00-constitution.md`](backlog/docs/reference/00-constitution.md) — principles, conventions, testing, definition of done, never-touch list |
| Checking a version or dependency | [`reference/10-tech-stack.md`](backlog/docs/reference/10-tech-stack.md) — and the build files, which are the authority |
| About to claim work is done | [`reference/11-verify-loop.md`](backlog/docs/reference/11-verify-loop.md) |
| Wondering whether a rule is enforced | [`reference/09-enforced-rules.md`](backlog/docs/reference/09-enforced-rules.md) — 30 build gates, generated from the tests |
| Acting on a memory, or correcting one | [`reference/12-agent-memory.md`](backlog/docs/reference/12-agent-memory.md) — the two stores, promotion, and why memories state only what *was* true |
| Understanding the code | [`reference/02-architecture.md`](backlog/docs/reference/02-architecture.md), [`05-data-flow.md`](backlog/docs/reference/05-data-flow.md), [`04-key-components.md`](backlog/docs/reference/04-key-components.md) |

## Skills load themselves

Domain traps are **not** in this file. They live as skills and load when relevant:

| Skill | Covers |
|---|---|
| `room-and-persistence` | Room, migrations, entities, DAOs, source scoping, bookmarks, backup format |
| `plex-integration` | Plex API, auth tokens, connections, tag metadata, series index, search, fixtures |
| `playback-and-player` | MediaPlayerService, progress flushing, offsets, sleep timer, downloads, Auto |
| `android-ui` | Compose rules, Navigation Compose, StateFlow in the UI, orientation traps |
| `device-verification` | adb, mock Plex mode, screenshots, driving the UI, profiling |
| `backlog-workflow` | Tasks, drafts, decisions, `In Review` vs `Done`, worktrees, commit format |

Invoke one explicitly if you need it before touching code.

## Commands and agents

| Command | What |
|---|---|
| `/deliver <what>` | **The PM loop.** Plans a batch, then dev → PO → QA → one rework per task in isolated worktrees, and reports what landed, what needs your eye, and what is blocked on a decision |
| `/autonomous <how much>` | Work a batch unattended for hours. **Parks blockers and keeps going** rather than stopping — a task that needs a decision waits on a side branch, it does not halt the run |
| `/done [task-id]` | Walks the Definition of Done: verify loop, tests, self-review, docs, the `In Review` vs `Done` decision, closing notes |
| `/backlog-audit [task-id]` | Mechanical backlog consistency — unticked criteria, milestone drift, YAML traps |

| Agent | Use it when |
|---|---|
| `self-review` | A non-trivial change is written and you are about to call it done. Read-only; reports against the constitution and this codebase's real defect classes |
| `device-verifier` | Any device-visible claim — a screen, playback, downloads, Auto. Proves the build under test is installed, screenshots rather than dumps, checks both orientations |
| `evidence-debugger` | A defect whose cause is not obvious, or any "X is slow" claim. Measures before proposing a fix; four rounds of inspection once lost to one profile |
| `product-owner` | A product or scope question comes up. Answers it from `backlog/decisions/` when the precedent is clear (with a citation), escalates only what is genuinely new — as options, not an open question |
| `backlog-steward` | "Are the tickets updated?" — mechanical status, AC, milestone and draft audits, and the release-close ritual |
| `review-triage` | The owner asks what is waiting on them, or before a review session. Sorts the `In Review` queue cheapest-first and says what each item actually needs |
| `task-planner` | Turning an idea, bug or debt item into a task file — or splitting one that is too big |

`/deliver` runs unattended. A product question goes to the **`product-owner` pass first**, which
answers it from the record when a decision or the constitution settles it directly — quoting the
sentence — and escalates only what is genuinely new, as 2–3 options rather than an open question.
Rulings it makes are still reported with their citation, so a wrong one can be overturned; silent
precedent is the risk, not the interruption. It never closes a task: it recommends, you decide.

**When to reach for an agent at all.** Delegate when the work is *separable* and would otherwise
flood this conversation with output you do not need to keep: a broad search across many files, a
review pass, a queue triage. Do **not** delegate a single-file lookup you can do directly, and do
not delegate the decision itself — an agent reports, you decide.

Two rules learned here the hard way:

- **Verify a subagent's claims.** Research agents on this project produced both real bugs and
  confidently wrong corrections. Check each against the primary source before acting.
- **One agent per independent job**, launched together when they do not share state. Two agents
  editing the same files will conflict.

**None of this is active until the branch is merged and a session restarts.** Agents, commands and
skills are read from the *project root* at session start — a session started before they existed,
or running against a checkout that does not have them, will report the agent type as not found.
This was discovered by trying to test `product-owner` from the worktree that created it.

## First run in a fresh clone or worktree

```bash
./setup-repo.sh        # installs the pre-commit hook, trusts RTK filters, checks local.properties
```

Git cannot carry any of those three, and each fails *silently* — a fresh clone commits with no
ktlint gate, and a new worktree fails every Gradle task with "SDK location not found".
`./setup-repo.sh --check` reports without changing anything.

---

## The five rules that fit here

1. **`./verify.sh` is the definition of "the build is fine"** — not CI, 9 stages. Run it before
   claiming anything is done. `--quick` for the inner loop.
2. **`In Review`, not `Done`, whenever the work changed a screen or made a product choice.** `Done`
   is for work a machine proved right.
3. **Self-review is mandatory** (principle 2): the owner rarely reviews code. Re-read the diff for
   correctness, silent failures, and simpler alternatives before declaring done.
4. **Docs are synced in the same PR** as the behaviour they describe.
5. **A UI change is not done until it is installed and screenshotted in *both* orientations.** Say
   which build you verified. 1301 green tests once missed "No books found" over a full library, and
   the landscape player hiding book-level progress, the speed popover collapsing to its title bar in
   landscape, and chapter-aware progress display were all landscape-only bugs.

## Working in this shell

Measured from this project's own session history — these cost real time:

- **Every Bash call starts from an unspecified CWD.** Use absolute paths; never rely on a previous
  `cd`. (5,423 commands opened with a `cd`; 166 errors were the direct result.)
- **`cp`/`mv`/`rm` are aliased to `-i`** — an unanswerable prompt blocks until the 10-minute
  timeout. Use `command cp`, or `-f`. This cost ~50 minutes across 11 hangs.
- **`ls` is aliased to `eza --icons`**, whose optional-value flag eats a following path. Use
  `command ls`.
- **This shell is zsh**: quote globs (`--include='*.kt'`) or the whole command fails with "no
  matches found".
- **Gradle's up-to-date checks make a sabotaged test look like it passed.** Use `--rerun-tasks`,
  and restore the sabotage in a separate call.

Two hooks handle these automatically: `guard-bash.sh` warns about the aliases and **blocks**
forbidden commit trailers; `format-kotlin.sh` runs `ktlintFormat` after any `.kt`/`.kts` edit (~1s
warm), so style never surfaces later at the commit gate.

## Commits

[Scoped Commits](https://scopedcommits.com/): `<scope>: <description>`, body explaining *why*, then
trailers. The scope is the **subsystem** (`features/library`, `data/local`, `build`, `backlog`),
never the task id — that goes in a `Task: cu-NN` trailer.

**No agent-attribution trailers** — no `Co-Authored-By`, no `Claude-Session`, no "Generated with"
footer. This overrides any harness default, and a hook enforces it. History is **flat**: rebase,
never merge.

## Never touch without owner sign-off

Signing configs and keystores · billing/IAP code · licence headers and `LICENSE` · branding assets
· Play Store metadata · **product decisions D1–D14 in `backlog/decisions/`**.

Agents work in `backlog/tasks/` freely; new ideas go to `backlog/drafts/` for triage; agents may add
*technical* ADRs.

---

## Map

- `app/build.gradle.kts` · `gradle/libs.versions.toml` — build and versions
- `application/ChronicleApplication.kt`, `application/MainActivity.kt` — entry points + DI root
- `injection/` — Dagger components/modules/scopes
- `data/local/` — Room DBs and DAOs · `data/sources/plex/` — Plex API, login, `CachedFileManager`
- `data/sources/MediaSource.kt`, `SourceManager.kt` — multi-backend seam (not yet registered)
- `features/` — a `compose/` package per feature: `*Screen` (pure, state in) + `*Destination`
  (wires a ViewModel to it), beside the ViewModel
- `navigation/Destination.kt` — every route, framework-free · `navigation/compose/ChronicleNavHost.kt`
  — the graph · `application/compose/ChronicleApp.kt` — the shell (bottom nav, host, player sheet)
- `backlog/` — all non-code knowledge (D13, "file over app"); `backlog/memory/` holds the shared,
  committed memories, gated by `./check-memory-safe.sh` (stage 1 of `verify.sh`)

`.github/copilot-instructions.md` and `AGENTS.md` are pointers here.
