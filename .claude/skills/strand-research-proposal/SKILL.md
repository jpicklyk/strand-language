---
name: strand-research-proposal
description: How to research and document an unresolved Strand design question, producing a durable proposal in `proposals/<topic>.md` that a future session can implement against. Use this skill PROACTIVELY whenever the user wants to think through, explore, investigate, research, or draft a proposal for any open Strand design problem — INCLUDING when the question is framed as uncertainty rather than as an explicit research request. Trigger phrasings include: "I'm not sure how X should work", "the spec is ambiguous about Y", "what would it look like if Strand had Z", "how should Strand model W", "help me think through how Strand handles N", "we need a clarifying design pass on X", "draft a proposal for Y", "research the open question about W", "explore tradeoffs for Z", "investigate how Strand should integrate with X", any reference to a Q-NNN in `open-questions.md` that doesn't yet have a proposal, any mention of "Milestone 2.X" research work, or any request to study an open design problem before implementing it. Even soft framings like "I've been wondering about X" or "what's the right approach for Y" should trigger the skill if Y is an open Strand design question rather than a settled one. The skill orchestrates the research-to-document workflow: identifies relevant design docs, optionally decomposes via parallel research agents when the question naturally splits into independent sub-questions, synthesizes into the standard proposal-doc structure (see `references/proposal-structure.md`), registers a Q-NNN identifier in `open-questions.md`, and links from `impl/CLAUDE.md` so future sessions surface the work during orientation. Does NOT trigger for quick factual questions about what the spec already says ("what does ADR-003 conclude about X") — only for open questions that warrant a proposal.
---

# Researching and documenting a Strand design question

This skill captures the workflow for taking an unresolved design question and producing a self-contained proposal document — one that any future session can read in isolation and implement against. The procedure was used to produce three proposals in `proposals/` (refinement-lattice capability matching, effect handlers, state machines runtime) and is repeatable.

## Why this is a skill

Three things make design research for Strand non-trivial:

1. **The design corpus is dense and the right starting point varies.** A question about effect handlers points at `design/effects-and-capabilities.md` and ADR-004; a question about node hashing points at ADR-003 and `design/node-algebra.md` § Hash construction. Knowing where to begin reading is half the work.

2. **Some questions decompose into independent sub-questions that can be researched in parallel.** During this session, four independent design questions (recursive types, refinement matching, effect handlers, state machines) were dispatched to four parallel research agents and synthesized into four proposals. Knowing when to decompose vs do it inline is a real call.

3. **The proposal-document structure matters.** A proposal that lacks an implementation sketch leaves the implementing session guessing; a proposal that lacks "deferred concerns" lets scope creep in; a proposal without a final review hides judgment calls. The standard structure (see `references/proposal-structure.md`) is load-bearing for downstream usefulness.

## When to use this skill vs other approaches

- **Use this skill** when the user wants a design pass on an unresolved or open question — research mode, not implementation mode.
- **Don't use this skill** when the user just wants to implement something that's already specified (use `strand-add-node` for new node categories).
- **Don't use this skill** when the user wants a quick conversation-level answer ("what does the spec say about X"). Just answer it directly.
- **The line between "research" and "implementation"** is whether the design is settled. If you have a proposal file, the design is settled and you should be in implementation mode. If you're still figuring out what the design should be, you're in research mode.

## The procedure (high level)

1. Orient: read project conventions and the existing design corpus around the question
2. Decide whether to decompose with parallel research agents
3. Conduct the research (yourself, or via spawned agents)
4. Synthesize into the standard proposal-doc structure
5. Store in `proposals/<topic>.md` and update `proposals/README.md`
6. Register a Q-NNN in `open-questions.md` with status `Proposed` and resolution summary
7. Link the proposal from `impl/CLAUDE.md` "Known gaps" section
8. Final review with the user — surface judgment calls explicitly

## Step 1: Orient

Read:

- `CLAUDE.md` (root) — project framing, non-negotiable conventions
- `impl/CLAUDE.md` — implementation state and the "Known gaps" section
- Existing design docs in `design/` related to the question (the user's prompt usually names them, or your search for relevant Q-NNN in `open-questions.md` will)
- Existing proposals in `proposals/` if any might overlap or be related

The most common starting points by question topic:

| Question topic | Read first |
|---|---|
| Effects, capabilities, handlers | `design/effects-and-capabilities.md`, ADR-004 |
| State machines | `design/state-machines.md`, ADR-007, `design/distribution-model.md` |
| Type system, node algebra, encoding | `design/node-algebra.md`, ADR-001, ADR-003 |
| Foreign functions, IO | `design/security-model.md`, ADR-005 |
| Encryption, key management | `design/encryption-model.md`, ADR-006 |
| Distribution, scheduling | `design/distribution-model.md`, ADR-007 |
| Rendering, structured outputs | `design/rendering-and-views.md`, ADR-009 |

## Step 2: Decide whether to decompose

The question is a candidate for parallel decomposition if:

- It naturally breaks into independent sub-questions (e.g., "how should Strand handle recursion AND refinement matching AND state machines" → three independent agents)
- Each sub-question requires non-trivial reading of different design docs
- The sub-questions don't depend on each other's resolutions

Stay single-agent (do it yourself or one focused agent) if:

- The question is contained — one design doc, one ADR, one cluster of concerns
- The sub-questions interact (e.g., "how should recursive types interact with type inference" — both halves need to be solved together)
- The total reading load is small enough that switching to subagent dispatch loses more in coordination than it gains in parallelism

When in doubt: **start with a single focused effort.** Decompose only if you find yourself needing to context-switch heavily.

When you do decompose, spawn agents in parallel (single message with multiple `Agent` tool calls). See `references/parallel-agent-prompts.md` for the prompt template.

## Step 3: Conduct the research

Whether single-agent (you) or multi-agent (delegate), the research output is the same: enough material to fill the standard proposal-doc structure. Don't write code. Don't modify any source files. Produce the proposal text.

For each sub-question:

- **Problem statement** — what's the gap, in concrete terms
- **Prior art** — 3–5 bullets on relevant approaches in other languages or in the literature; verify the claims (don't invent prior art)
- **Recommended approach** — be opinionated. Pick ONE design and commit. Hedging and "we could either..." is harder to act on than a clean recommendation with a noted alternative.
- **Detailed mechanism** — canonical encoding bytes, verifier rules, runtime semantics, as appropriate. Walk through a concrete example.
- **Test scenarios** — enumerate 5–10 concrete cases the test suite should cover. Include error paths, not just happy paths.
- **Tradeoffs and deferred concerns** — what's intentionally out of scope, and why. The "what we didn't solve" list is as important as the "what we solved" list.
- **Implementation sketch** — file-by-file change estimates with scope (small/medium/large). What files in `impl/` change? Any new modules? Any new dependencies?

## Step 4: Synthesize into the standard proposal-doc structure

See `references/proposal-structure.md` for the canonical template. The shape is:

```markdown
# <Topic Title>

**Document:** `proposals/<topic>.md`
**Status:** Draft proposal
**Date:** YYYY-MM-DD
**Concerns:** <list of related docs and Q-NNN>
**Scope:** <small / small-medium / medium / medium-large / large>

<Brief framing paragraph: what slice of the implementation this proposal covers.>

## 1. Problem statement
## 2. Prior art (or Decisions to make)
## 3. Recommended approach
## 4. <Detailed mechanism / canonical encoding / verifier rules / runtime semantics>
## 5. Verifier rules
## 6. Interpreter / runtime semantics
## 7. Test scenarios
## 8. Tradeoffs and open questions
## 9. Implementation sketch — file table with scope estimates
## References (outgoing + incoming)
```

The exact section numbering varies — see existing proposals for examples. The required sections are: problem, recommended approach, test scenarios, tradeoffs, implementation sketch, references.

**Voice convention** — proposals use the same neutral specification voice as design docs. Not narrative ("we discovered..."), not exploratory ("one option might be..."). State the recommendation as the design under consideration; cite alternatives in the tradeoffs section. See `references/conventions.md` for examples (shared with the `strand-add-node` skill).

## Step 5: Store in `proposals/<topic>.md`

- File name: short hyphenated topic identifier, e.g., `refinement-lattice-capability-matching.md`, `effect-handlers.md`, `state-machines-runtime.md`
- Update `proposals/README.md`:
  - Add a row to the current-proposals table
  - Update the reading-order recommendation if the new proposal changes the natural ordering
  - Update sequencing notes if the new proposal interacts with others

## Step 6: Register Q-NNN

- Check `INDEX.md` identifier-registry section for the next free Q-NNN
- Open the question in `open-questions.md`:
  - Add a new section if the question doesn't fit any existing topic group, or extend an existing topic group
  - Status: `Proposed`
  - Resolution summary: 2–4 sentences capturing the proposal's recommendation, ending with a pointer to `proposals/<topic>.md`
  - Update `**Last revised:**` line at the top
- Update `INDEX.md`:
  - Identifier-registry blurb: "Q-001 through Q-NNN are currently assigned"
  - `**Last revised:**` line if appropriate

## Step 7: Link from `impl/CLAUDE.md`

The "Known gaps and design questions" section in `impl/CLAUDE.md` is what future sessions read during orientation. Add an entry pointing at the new proposal so the work surfaces naturally:

```markdown
- **<Topic name>**. See [`proposals/<topic>.md`](../proposals/<topic>.md) (Q-NNN). <One-sentence summary of what the proposal closes and any context needed for the next session.>
```

Group with related gaps. Distinguish gaps that have proposals from those that don't.

## Step 8: Final review with the user

Before declaring the proposal complete, surface any judgment calls explicitly:

- Decisions where you picked one approach and the alternative is genuinely viable
- Open questions where the spec is ambiguous and your reading might be wrong
- Scope choices that the user might want to revise (e.g., "I scoped this to no-continuation handlers; multi-shot is deferred — confirm or push back")

Don't merge the proposal into "accepted" status. Status stays `Draft proposal` until the implementing session (running `strand-add-node` or doing the work directly) confirms with the user.

## Conventions to enforce

- Same voice and formatting rules as design docs (neutral, no emoji, no header punctuation)
- Each proposal is **self-contained** — a session reading just the proposal can implement against it without scrolling through chat transcripts
- Include both **happy-path AND error-path** test scenarios
- Be honest about what's deferred and why
- Don't pretend you've solved more than you have — if a sub-question is genuinely unresolved, list it in tradeoffs

## What a good proposal looks like

The three proposals currently in `proposals/` are the reference standard:

- `proposals/refinement-lattice-capability-matching.md` — touches many existing files; the implementation sketch breaks down the change into 10+ touchpoints with scope labels
- `proposals/effect-handlers.md` — introduces a new node category; commits to a restricted form (no continuations) with explicit "deferred to step 3b" carve-outs
- `proposals/state-machines-runtime.md` — proposes a multi-step shipping strategy; the step-1 details are concrete enough to execute, the step-2 and step-3 sketches are honest about what's underdetermined

Read at least one before writing your first proposal — they show the working shape.

## What this skill does NOT do

- It does not implement the proposal. That's `strand-add-node` for new node categories, or direct implementation for non-algebra-extending features.
- It does not promote a proposal to ADR status. ADRs are accepted decisions; proposals are draft research. The promotion happens when the user explicitly accepts the proposal and a session implements it.
- It does not relitigate settled decisions. ADR-001 through ADR-004 and the established node-algebra rules are settled per `CLAUDE.md` (root). If the research surfaces what looks like a contradiction with a settled decision, flag it explicitly rather than quietly proposing a reversal.
