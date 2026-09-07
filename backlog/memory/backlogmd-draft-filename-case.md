---
name: backlogmd-draft-filename-case
description: Backlog.md drafts need a lowercase draft-<n> filename but an uppercase DRAFT-<n> frontmatter id
metadata: 
  node_type: memory
  type: reference
  originSessionId: eb8384f7-4315-4572-b92d-890d4987c009
  modified: 2026-09-05T10:49:56.333Z
---

A Backlog.md draft is invisible to `backlog draft list` **and** to `backlog draft DRAFT-<n>` unless
its **filename** uses the lowercase `draft-<n>` prefix, while its frontmatter `id` uses the
uppercase `DRAFT-<n>`. The two genuinely differ. The file parses fine and sits in place, so nothing
reports an error — it simply never appears.

Renaming needs a temporary name in between (`mv X tmp && mv tmp x`), because a case-insensitive
macOS filesystem treats `DRAFT-170` and `draft-170` as the same path and `mv` refuses.

Found 2026-09-05 filing draft-170/171; recorded in CLAUDE.md beside the existing `DRAFT-` id-prefix
note, which documented the frontmatter half only. Related: [[backlogmd-completed-search-gap]].
