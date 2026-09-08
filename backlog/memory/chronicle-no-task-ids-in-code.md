---
name: chronicle-no-task-ids-in-code
description: "Chronicle forbids task-id citations in code comments; TaskIdReferenceTest enforces it, and it only scans .kt"
metadata:
  node_type: memory
  type: feedback
---

**Never write `cu-NN` in a code comment.** `TaskIdReferenceTest` is an enforced build gate and fails
`verify.sh` on any `.kt` file that does. State the reasoning instead — the measurement, the bug's
shape, the constraint. `decision-NN` and upstream issue links are exempt and stay useful.

The rule's own justification: a task id is a citation that stops resolving once the task is archived
out of `backlog search`, and nothing ever checked that the id existed or still described the code
beside it.

**The gate only scans `.kt` under the app's source dirs.** A citation in `verify.sh`, a Gradle file,
or a shell script passes the gate and is still wrong for exactly the same reason — the id rots
identically. Check those by hand; the green gate is not evidence they are clean.

Writing the ticket and writing the comment happen in the same edit, which is why this keeps
recurring: the id is in working memory, and the KDoc that explains *why* a file exists is precisely
where it wants to go.

**Why:** hit three times in one programme — twice while writing the Okio and DataStore work, then
again on a new file whose whole purpose was documenting a defect a ticket described. Each one cost a
full `verify.sh` cycle to discover.
**How to apply:** when writing any comment that explains why code exists, especially a new file's
header KDoc. Grep `cu-[0-9]` across the diff — not just `.kt` — before running the verify loop. See
[[chronicle-sabotage-rerun-tasks]] for the neighbouring gate discipline.
