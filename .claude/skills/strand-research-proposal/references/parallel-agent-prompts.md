# When to decompose with parallel research agents — and how to prompt them

The decision and the prompt template.

## When to spawn parallel agents

Decompose when ALL of these hold:

1. **Multiple independent sub-questions.** The work breaks into pieces whose conclusions don't depend on each other. (Counter-example: "how should recursive types interact with type inference" — both halves are entangled and need to be solved together.)

2. **Each sub-question requires non-trivial reading.** If each takes 30+ minutes of doc-reading and analysis, parallelism pays off. If each is a quick check, the overhead of dispatch isn't worth it.

3. **You can write self-contained prompts.** Each agent must operate with no shared context — they need explicit project background, exact file paths to read, and clear output requirements.

When in doubt, **start single-track**. You can always decompose mid-research if you discover the question splits cleanly. The reverse (regretting decomposition) is harder to recover from.

## How many agents

Practical experience: 2–4 agents in parallel works. More than 4 starts producing diminishing returns — the coordination cost of synthesizing 5+ proposals exceeds the time saved.

If a topic has more than 4 sub-questions, group them into 3–4 clusters.

## Spawning pattern

Spawn all agents in **a single message** with multiple `Agent` tool calls. This is what the Agent tool documentation calls out explicitly: "If the user specifies that they want you to run agents 'in parallel', you MUST send a single message with multiple Agent tool use content blocks."

The agents run concurrently and you receive all their results together.

## Prompt template for each agent

Each agent prompt should have these sections, adjusted for the specific question:

```
You're [researching / designing] [specific topic] for Strand, a graph-based research language.

## Context

Strand is a Kotlin/JVM reference implementation of a graph-native language. Programs are content-addressed graphs of typed nodes. Read these files to ground yourself:

1. `D:\Projects\strand-language\CLAUDE.md` — project conventions
2. `D:\Projects\strand-language\impl\CLAUDE.md` — implementation state and Known gaps section
3. [other specific design docs the question concerns]
4. [other ADRs the question depends on]

## The problem

[Concrete description of the design question. Cite specific sections of the design docs. Include any constraints or prior decisions that constrain the answer.]

## Your task

Propose a design for [specific topic] that:
1. [Specific goal — closes the gap, fits a specific model, etc.]
2. [Constraint — fits Strand's existing X, doesn't break Y, etc.]
3. [Output — specifies what shape the proposal takes]

## What to investigate

- [Prior art to consider — name specific languages and approaches]
- [Specific design choices the proposal must commit to]
- [Concerns to flag if encountered]

## Output

Produce a markdown design proposal (~1500–2500 words) with:

1. **Problem statement** (1 paragraph) — the gap in concrete terms
2. **Prior art** (3–5 bullets) — verify claims, don't invent
3. **Proposed mechanism** — pick ONE approach and commit
4. **Canonical encoding** — show byte-level encoding if relevant
5. **Verifier rules** — well-formedness, new error variants
6. **Runtime semantics** — interpreter changes
7. **Test scenarios** — 5–10 concrete cases
8. **Tradeoffs and open questions** — what's deferred, why
9. **Implementation sketch** — files in `impl-kotlin/` that change, scope per file. Do NOT write the implementation; just sketch.

Do NOT write code. Do NOT modify any files. Produce a self-contained design proposal that the project lead can review and either approve or send back for revision.
```

## Things to emphasize in every prompt

Each agent operates with no memory of this conversation or other agents' work. The prompt must be self-contained. Specifically:

- **Read paths must be absolute.** `D:\Projects\strand-language\...` not `./...`.
- **Output format is markdown text in the response.** Don't have the agent write files (this can lead to conflicting writes if multiple agents try to deposit into the same directory).
- **"Do NOT write code" is load-bearing.** Without it, agents will sometimes spend significant time implementing rather than designing.
- **Word count guidance prevents under-detail and over-detail.** ~1500–2500 words is the sweet spot for a proposal that's detailed enough to act on but readable in a single sitting.

## What to do with the results

When all agents return, you have N markdown proposals as tool-result text. Don't deposit them directly into `proposals/` — synthesize first:

1. Read each proposal and identify:
   - Recommendations the user should see
   - Open questions that need their input
   - Any cross-cutting concerns between proposals (e.g., identifier coordination — recursive types and effect handlers both wanted N-041 in one round)

2. Present a synthesis to the user before storing the proposals. The synthesis is the value-add — it sequences the proposals, flags interactions, makes a recommendation about which to advance first.

3. After the user approves, edit each agent's output for the standard proposal-doc structure (see `proposal-structure.md`) and store in `proposals/<topic>.md`.

The synthesis step is what makes this skill more than "spawn 4 agents and dump their output." Skipping it loses the value.

## Example: a multi-agent dispatch that worked well

From this session, four agents were dispatched in parallel for:

1. **Recursive types** — biggest research gap, needed prior-art deep dive (Unison, μ-binders)
2. **Refinement-lattice capability matching** — extending existing infrastructure, mostly engineering
3. **Effect handlers** — restricted-form design choice required commitment
4. **State machines runtime** — biggest design space, multi-step shipping required

Each was genuinely independent. Each agent could read 4–6 design docs and produce 1500–3000 words. The synthesis identified an identifier conflict (recursive types claimed N-041+N-042, effect handlers also wanted N-042 → coordination needed N-043 for handlers).

That dispatch took ~5 minutes of agent runtime in parallel, versus an estimated 30–45 minutes serially. The synthesis took another 10 minutes. Net savings: substantial, and the proposals were higher-quality because each agent could go deep on one topic.

## When NOT to use parallel agents

- The question is small or contained — synthesis overhead exceeds research time
- Sub-questions interact heavily — you can't write self-contained prompts
- The user wants a conversation, not a polished document — agents produce documents, not conversations
- You're partway through a task and need to escalate one specific sub-question — a single targeted agent is usually better than a parallel sweep
