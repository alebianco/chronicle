export const meta = {
  name: 'deliver-tasks',
  description: 'Implement a batch of Chronicle tasks: dev in an isolated worktree, adversarial QA, one rework, then report',
  phases: [
    { title: 'Dev', detail: 'implement one task per worktree, TDD, to the acceptance criteria' },
    { title: 'PO', detail: 'answer product questions from recorded decisions; escalate only what is new' },
    { title: 'QA', detail: 'run verify.sh and adversarially check each criterion' },
    { title: 'Rework', detail: 'one fix pass for anything QA rejected' },
  ],
}

// ---------------------------------------------------------------- schemas --

const DEV_RESULT = {
  type: 'object',
  properties: {
    taskId: { type: 'string' },
    outcome: {
      type: 'string',
      enum: ['implemented', 'blocked_on_decision', 'blocked_on_device', 'abandoned'],
    },
    summary: { type: 'string', description: 'What actually changed, 1-3 sentences' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    testsAdded: { type: 'array', items: { type: 'string' } },
    verifyPassed: { type: 'boolean' },
    verifyOutput: { type: 'string', description: 'Last ~20 lines of verify.sh, or the failure' },
    touchedAScreen: { type: 'boolean' },
    productChoiceMade: { type: 'boolean' },
    decisionNeeded: { type: 'string', description: 'The product question, empty if none' },
    worktree: { type: 'string' },
    branch: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['taskId', 'outcome', 'summary', 'verifyPassed'],
}

const PO_RESULT = {
  type: 'object',
  properties: {
    taskId: { type: 'string' },
    resolution: { type: 'string', enum: ['answered', 'escalate'] },
    answer: { type: 'string', description: 'One-line answer when answered; empty when escalating' },
    citation: {
      type: 'string',
      description: 'Decision record number and the sentence that settles it. Required to answer.',
    },
    residueForOwner: {
      type: 'string',
      description: 'Part of the question still the owners, if any; empty when fully answered',
    },
    escalation: {
      type: 'string',
      description: 'The Question/Why-not-settled/Options/What-it-blocks block, when escalating',
    },
  },
  required: ['taskId', 'resolution'],
}

const QA_RESULT = {
  type: 'object',
  properties: {
    taskId: { type: 'string' },
    verdict: { type: 'string', enum: ['pass', 'reject'] },
    verifyReproduced: { type: 'boolean', description: 'QA ran verify.sh itself and it passed' },
    criteriaChecked: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          criterion: { type: 'string' },
          met: { type: 'boolean' },
          evidence: { type: 'string' },
          machineProvable: { type: 'boolean' },
        },
        required: ['criterion', 'met', 'evidence'],
      },
    },
    defects: { type: 'array', items: { type: 'string' } },
    sabotageChecked: { type: 'boolean', description: 'A new guard test was proven able to fail' },
    recommendedStatus: { type: 'string', enum: ['Done', 'In Review', 'To Do'] },
    whatNeedsOwnerEye: { type: 'string' },
  },
  required: ['taskId', 'verdict', 'recommendedStatus'],
}

// ------------------------------------------------------------- the rules --
// Repeated into every agent prompt. These are the project's own rules, and each
// one exists because it was broken at least once.

const RULES = `
Project rules — non-negotiable:

- ./verify.sh is the definition of "the build is fine". All stages must pass:
  check-memory-safe, ktlint, unit tests, coverage ratchet, debug APK, lint, and
  the RELEASE variant compiling. Not CI, not "tests passed".
- Run ./setup-repo.sh first in a fresh worktree. local.properties is gitignored,
  so without it every Gradle task fails with "SDK location not found" — which
  reads like a broken build rather than missing setup.
- Tests are required for touched repositories, ViewModels, sync/download/chapter
  logic. A NEW GUARD TEST MUST BE SABOTAGE-VERIFIED: break the production code,
  watch it fail, restore. Use --rerun-tasks; Gradle's up-to-date checks make a
  sabotaged test look like it passed, and restore in a SEPARATE call.
- Commit format: "<scope>: <description>" where scope is the subsystem, never the
  task id; body says WHY; "Task: cu-NN" trailer. NO Co-Authored-By, NO
  Claude-Session, NO "Generated with" footer — a hook blocks them.
- Shell traps: every Bash call starts from an unspecified CWD, so use absolute
  paths. cp/mv/rm are aliased to -i and will hang until timeout — use "command cp"
  or -f. ls is aliased to eza --icons and eats a path argument — use "command ls".
  Quote globs: --include='*.kt'.
- NEVER touch: backlog/decisions/ D1-D14, signing configs, keystores, billing/IAP
  code, licence headers, branding assets, Play Store metadata.
- Read the .claude/skills/ file matching the area you are touching. Those hold the
  traps that cost real debugging sessions.

If you hit a PRODUCT decision — a default, a sort order, a threshold tuned by ear,
a user-facing format, a set of presets — STOP that task and report it as
decisionNeeded. Do not guess, and do not widen scope to route around it. A
well-posed question is a better result than an invented default.
`

// ------------------------------------------------------------------ flow --

const tasks = (args && args.taskIds) || []
const goal = (args && args.goal) || 'deliver the named tasks'

if (!tasks.length) {
  log('No taskIds passed. Pass args: {taskIds: ["cu-183", ...], goal: "..."}')
  return { error: 'no taskIds', delivered: [] }
}

log(`Delivering ${tasks.length} task(s): ${tasks.join(', ')} — goal: ${goal}`)

function poPrompt(taskId, dev) {
  return `A dev agent working on Chronicle task ${taskId} is blocked on a product question.

The question:
${dev.decisionNeeded || '(the dev did not state it clearly — say so and escalate)'}

Context — what the dev was doing:
  ${dev.summary || '(no summary)'}
  files touched so far: ${(dev.filesChanged || []).join(', ') || '(none)'}

Decide whether the record already settles this. Answer ONLY with a citation you can quote from
backlog/decisions/ or the constitution; escalate anything needing new taste — a sort order, a
default, a threshold, presets, wording, an icon, a user-facing format.

Answering a settled question saves the owner an interruption. Inventing an answer costs far more
than a question that waits. If you cannot cite it, escalate.`
}

function devPrompt(taskId, rework, ruling) {
  return `You are implementing Chronicle task ${taskId}.

${rework ? `THIS IS A REWORK PASS. QA rejected the previous attempt:\n${rework}\n\nFix exactly those defects. Do not widen scope.\n` : ''}${ruling ? `A PRODUCT QUESTION YOU RAISED HAS BEEN ANSWERED by the PO pass, from the project's own record:\n\n  Ruling: ${ruling.answer}\n  Basis:  ${ruling.citation}\n\nProceed on that basis and finish the task. Do not re-litigate it. If the ruling does not in fact\nunblock you, say so via decisionNeeded rather than guessing.\n` : ''}
Steps:
1. Read the task file: backlog/tasks/ — find the one whose name starts with "${taskId} ".
   Read its Description and Acceptance Criteria.
2. Read CLAUDE.md, then backlog/docs/reference/00-constitution.md, then the
   .claude/skills/ file matching the area you are touching.
3. Work in an ISOLATED GIT WORKTREE — you have been given one. Run ./setup-repo.sh
   in it before any Gradle command.
4. Implement to the acceptance criteria, test-first. Add or extend tests for what
   you touch.
5. Run ./verify.sh and make it green. Report its real outcome — never claim a pass
   you did not see.
6. Commit with the project's format. Do NOT merge, do NOT push, do NOT touch develop.

${RULES}

Report honestly. If verify.sh fails and you cannot fix it, say so with the output —
outcome "abandoned" is a valid and useful result. If an acceptance criterion is a
device/visual check you cannot perform, leave it unmet and say so: this project's
rule is that such a task goes to In Review with the box unticked, and ticking it on
the strength of a test that cannot see the screen is worse than leaving it.

Set touchedAScreen true if you changed any layout, Compose UI, wording or icon.
Set productChoiceMade true if you chose a default, ordering, threshold or format.`
}

function qaPrompt(taskId, dev) {
  return `You are QA for Chronicle task ${taskId}. Your job is to try to BREAK the
claim that this work is done. Be adversarial: a pass you give wrongly costs the
owner a broken build or a wasted review.

The dev agent reported:
  outcome: ${dev.outcome}
  summary: ${dev.summary}
  verifyPassed: ${dev.verifyPassed}
  worktree: ${dev.worktree || '(unknown)'}
  branch: ${dev.branch || '(unknown)'}
  filesChanged: ${(dev.filesChanged || []).join(', ') || '(none reported)'}
  testsAdded: ${(dev.testsAdded || []).join(', ') || '(none reported)'}
  touchedAScreen: ${dev.touchedAScreen}
  productChoiceMade: ${dev.productChoiceMade}

Do this:
1. Go to the worktree. Run ./verify.sh YOURSELF. Do not take the dev's word for it.
   Set verifyReproduced from what you actually saw.
2. Read the task's acceptance criteria and check each one against the DIFF and the
   TESTS — not against the dev's summary. For each, record evidence (a test name, a
   gate, a measurement) and whether it is machine-provable.
3. Look for this codebase's real defect classes:
   - silent failures (an error path that logs nothing, a null coalesced to empty)
   - a fix applied to one of two branches (Audiobook.merge has two arms; only one
     runs for a given pair, so a test can pass on the fixed path)
   - a fixture written to match the code rather than a captured response
   - a test that cannot fail — if a new guard was added, verify it was
     sabotage-verified, and say so in sabotageChecked
   - per-second work: any new query on Audiobook/MediaItemTrack re-emits at tick rate
4. Decide recommendedStatus by ONE question: CAN A MACHINE PROVE THIS WAS RIGHT?
   - Yes, and no screen changed and no product choice was made -> "Done"
   - Touched a screen, made a product choice, or has an unperformed device/visual
     criterion -> "In Review", and write whatNeedsOwnerEye as a specific phrase
     ("the shelf's sort order"), never "please review"
   - Not actually finished -> "To Do" with defects listed
5. verdict "reject" if there are real defects to fix; "pass" otherwise. A task that
   is correctly In Review still gets verdict "pass" — In Review is a healthy
   outcome, not a failure.

${RULES}`
}

const results = await pipeline(
  tasks,

  // --- Dev -----------------------------------------------------------------
  (taskId) =>
    agent(devPrompt(taskId, null), {
      label: `dev:${taskId}`,
      phase: 'Dev',
      schema: DEV_RESULT,
      isolation: 'worktree',
    }),

  // --- PO: try to answer the product question from the record --------------
  // Most product questions here are already settled — 22 decision records plus
  // the constitution, and a spot check of realistic questions found three of four
  // answerable from them. Escalating a settled question wastes the owner's
  // attention, which 52 queued In Review tasks are already competing for. The PO
  // may only answer with a citation; anything needing new taste escalates.
  (dev, taskId) => {
    if (!dev || dev.outcome !== 'blocked_on_decision') return dev

    log(`${taskId}: product question — asking PO before the owner`)

    return agent(poPrompt(taskId, dev), {
      label: `po:${taskId}`,
      phase: 'PO',
      schema: PO_RESULT,
      agentType: 'product-owner',
    }).then((po) => {
      if (!po || po.resolution !== 'answered' || !po.citation) {
        // Unanswerable, or answered without a citation — the citation IS the bar.
        return { taskId, status: 'blocked', dev, po, decision: dev.decisionNeeded }
      }
      // Answered: hand the ruling back to dev and let it finish the task.
      return agent(devPrompt(taskId, null, { answer: po.answer, citation: po.citation }), {
        label: `dev2:${taskId}`,
        phase: 'Dev',
        schema: DEV_RESULT,
        isolation: 'worktree',
      }).then((d2) => (d2 ? { ...d2, _poAnswer: po } : { taskId, status: 'needs-human', dev, po, reason: 'dev did not resume after the PO ruling' }))
    })
  },

  // --- QA ------------------------------------------------------------------
  (dev, taskId) => {
    if (!dev) return { taskId, status: 'needs-human', reason: 'dev agent produced no result' }
    if (dev.status) return dev // already terminal (blocked / needs-human) from the PO stage
    if (dev.outcome === 'blocked_on_decision') {
      // Still blocked after the PO pass.
      return { taskId, status: 'blocked', dev, decision: dev.decisionNeeded }
    }
    if (dev.outcome === 'blocked_on_device') {
      return { taskId, status: 'needs-human', dev, reason: 'needs on-device verification' }
    }
    if (dev.outcome === 'abandoned') {
      return { taskId, status: 'needs-human', dev, reason: dev.notes || 'dev abandoned the task' }
    }
    return agent(qaPrompt(taskId, dev), {
      label: `qa:${taskId}`,
      phase: 'QA',
      schema: QA_RESULT,
      // self-review already encodes this codebase's defect classes and the
      // In Review vs Done rule. Reusing it keeps QA's judgment from drifting
      // away from the reviewer the rest of the repo uses.
      agentType: 'self-review',
    }).then((qa) => ({ taskId, dev, qa }))
  },

  // --- One rework pass, then stop ------------------------------------------
  (r, taskId) => {
    if (!r || r.status) return r // already terminal
    if (!r.qa) return { taskId, status: 'needs-human', dev: r.dev, reason: 'QA produced no result' }
    if (r.qa.verdict === 'pass') {
      return {
        taskId,
        status: r.qa.recommendedStatus === 'Done' ? 'done' : 'needs-review',
        dev: r.dev,
        qa: r.qa,
      }
    }

    const defects = (r.qa.defects || []).join('\n- ')
    log(`${taskId}: QA rejected — one rework pass`)

    return agent(devPrompt(taskId, `- ${defects}`), {
      label: `rework:${taskId}`,
      phase: 'Rework',
      schema: DEV_RESULT,
      isolation: 'worktree',
    })
      .then((fixed) => {
        if (!fixed) return { taskId, status: 'needs-human', dev: r.dev, qa: r.qa, reason: 'rework produced no result' }
        return agent(qaPrompt(taskId, fixed), {
          label: `qa2:${taskId}`,
          phase: 'QA',
          schema: QA_RESULT,
          agentType: 'self-review',
        }).then((qa2) => {
          if (!qa2 || qa2.verdict === 'reject') {
            // Deliberately NOT a loop. Two failed QA passes means the task needs a
            // human — looping burns tokens on a misunderstanding.
            return {
              taskId,
              status: 'needs-human',
              dev: fixed,
              qa: qa2 || r.qa,
              reason: 'rejected twice by QA — needs a human look',
            }
          }
          return {
            taskId,
            status: qa2.recommendedStatus === 'Done' ? 'done' : 'needs-review',
            dev: fixed,
            qa: qa2,
          }
        })
      })
  },
)

// ---------------------------------------------------------------- summary --

const out = results.filter(Boolean)
const by = (s) => out.filter((r) => r.status === s)

// A question the PO settled from the record never reached the owner. Report that
// count — it is the whole point of the PO pass, and a PO that answers nothing (or
// everything) is a PO whose bar needs looking at.
const poAnswered = out.filter((r) => r._poAnswer)

log(
  `Done: ${by('done').length} | Needs review: ${by('needs-review').length} | ` +
    `PO-resolved: ${poAnswered.length} | Escalated to owner: ${by('blocked').length} | ` +
    `Needs a human: ${by('needs-human').length}`,
)

const dropped = tasks.length - out.length
if (dropped > 0) log(`NOTE: ${dropped} task(s) returned nothing at all — not silently ignored, report them.`)

return {
  goal,
  requested: tasks,
  delivered: out,

  // Answered from the record, without interrupting the owner. Surfaced anyway so a
  // wrong ruling is visible and can be overturned — silent precedent is the risk.
  resolvedByPO: poAnswered.map((r) => ({
    taskId: r.taskId,
    answer: r._poAnswer.answer,
    citation: r._poAnswer.citation,
    residueForOwner: r._poAnswer.residueForOwner || '',
  })),

  // Genuinely new — these need the owner. Each carries options, not an open question.
  decisionsForOwner: by('blocked').map((r) => ({
    taskId: r.taskId,
    question: r.decision,
    options: (r.po && r.po.escalation) || '(PO did not produce options — ask it directly)',
  })),

  needsOwnerEye: by('needs-review').map((r) => ({
    taskId: r.taskId,
    what: (r.qa && r.qa.whatNeedsOwnerEye) || '(unspecified — ask QA)',
  })),
}
