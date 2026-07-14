# Backlog — Chronicle Unabridged

All project knowledge that isn't code lives here as markdown (decision D13 / [[decision-13]], "file over app"). Plain files, greppable, forge-agnostic. The [Backlog.md CLI](https://github.com/MrLesk/Backlog.md) (`brew install backlog-md`, then `backlog board`) is an optional viewer — editing files directly is always canonical.

## Layout

| Path | What | Who writes it |
|---|---|---|
| `tasks/` | The work. One file per task (`task-<id> - <Title>.md`): frontmatter (status/labels/dependencies/priority) + acceptance criteria. Labels carry the release (`R0`–`R4`) and area. | agents + owner |
| `drafts/` | Ideas not yet committed to tracked work — need an owner decision before promotion to `tasks/`. | agents propose, owner triages |
| `decisions/` | Decision records (`decision-<n> - <Title>.md`): context → decision → consequences. Product decisions (D1–D14) and technical ADRs both live here. | **owner only** for product decisions; agents may add technical ADRs |
| `docs/reference/` | Architecture knowledge base (project overview, architecture, data flow, components, glossary) — explains the code *as it is*. | agents keep in sync with behavior |
| `docs/analysis/` | *Optional* deep-reference for debt items (C/H/M) — problem/current-state/risk — linked from a task only when too large to inline. `archive/` holds stale ones. A task's own plan/notes live in the task file, not here. | reference |
| `docs/research/` | Evidence base: `RESEARCH_FINDINGS.md`, `COMMERCIAL_VIABILITY_REPORT.md`. | reference |
| `docs/research/design-references/` | Competitor/design screenshots (third-party — uncommitted assets). | reference |

## Status flow

`To Do → In Progress → In Review → Done` (drafts sit at `Draft` until promoted). Lifecycle and definition-of-done: see [`/CLAUDE.md`](../CLAUDE.md) §Workflow.

## Where the old files went (2026-07-13 consolidation)

- `PRODUCT_BACKLOG.md` → decisions D1–D14 became `decisions/decision-1..14`; framing/release-map/won't-do/risks are captured across those decisions (esp. [[decision-9]], [[decision-11]], [[decision-14]]); the release grouping is just the `R0`–`R4` labels on tasks.
- `docs/09-project-analysis-and-tasks.md` → its 26 debt items became tasks cu-8/15/23/42–51 + drafts cu-52/53; its plans are in `docs/analysis/` (mapping table in that folder's README).
- `docs/01–08` + `docs/README.md` → `docs/reference/`.
- `todo.md` (upstream's list) → live items became tasks/drafts (cu-46 audit); DONE history stays in git.
