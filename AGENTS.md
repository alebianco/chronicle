# Agent instructions

Read [`CLAUDE.md`](./CLAUDE.md) — the entry point for all coding agents (Claude Code, Copilot, or
otherwise) and humans working on this repo.

It is deliberately short and routes to:

- [`backlog/docs/reference/00-constitution.md`](./backlog/docs/reference/00-constitution.md) —
  principles, conventions, testing, definition of done, never-touch list
- [`backlog/docs/reference/09-enforced-rules.md`](./backlog/docs/reference/09-enforced-rules.md) —
  the build gates that fail on a forbidden pattern
- [`backlog/docs/reference/10-tech-stack.md`](./backlog/docs/reference/10-tech-stack.md) and
  [`11-verify-loop.md`](./backlog/docs/reference/11-verify-loop.md)
- `.claude/skills/` — domain traps (Room, Plex, playback, UI, device verification, backlog
  workflow). Claude Code loads these automatically when the work matches; other agents should read
  the relevant `SKILL.md` directly.
