# Copilot Repository Instructions — Chronicle

All agent and contributor instructions start at [`/CLAUDE.md`](../CLAUDE.md) (single source of
truth per backlog decision D10/D12). It is a short router; read it first.

The durable standards live in `backlog/docs/reference/`:

| File | Contents |
|---|---|
| `00-constitution.md` | Development principles, conventions, testing, definition of done, never-touch list |
| `09-enforced-rules.md` | Build gates — rules enforced by tests that fail the build |
| `10-tech-stack.md` | Versions, architecture, variants (build files are the authority) |
| `11-verify-loop.md` | `verify.sh`, the coverage ratchet, release and instrumented tests |
| `02-architecture.md`, `05-data-flow.md`, `04-key-components.md` | How the code works |

Domain-specific traps (Room migrations, Plex metadata, playback, Compose/ViewBinding, device
verification, backlog workflow) live in `.claude/skills/*/SKILL.md`. Read the one matching your
change — they exist because each trap cost a real debugging session.

Docs are updated in the same PR as the behavior they describe.
