# Strand — session brief for Claude

Strand is a research project to design and build a programming language for AI agents to **generate**, not for humans to author. Programs are content-addressed graphs of typed nodes with mandatory effect declarations. There is no concrete syntax. There is no human-readable projection layer.

The design corpus (Wave 1–3) is substantially complete. Active work is the Kotlin/JVM reference implementation under `impl/`. Milestone 2.1, Layer 1 (pure-computation core, N-001..N-019) is the current slice.

## Read these before doing anything substantive

- [`INDEX.md`](INDEX.md) — document tree, concept index, and the **authoritative identifier registry** (ADR-NNN, N-NNN, E-NNN, Q-NNN). Read INDEX before assuming what exists.
- [`impl/README.md`](impl/README.md) — implementation state, layer scope, module layout.
- [`impl/CLAUDE.md`](impl/CLAUDE.md) — implementation-side conventions, loaded when working inside `impl/`.
- [`proposals/README.md`](proposals/README.md) — draft design proposals for unimplemented features (Q-030, Q-031, Q-032). The next session can advance any of them by reading the corresponding document, confirming with Jeff, and executing.

For specific work, also read the documents most relevant to it. ADR-001 through ADR-004 and `design/node-algebra.md` are load-bearing for any verifier/interpreter work.

## Non-negotiable conventions

These are settled. Do not relitigate without asking.

- **No concrete syntax, no human-readable projection.** ADR-001 and ADR-002 are decided. The graph is the source.
- **Stable identifiers never change.** ADR-NNN, N-NNN, E-NNN, Q-NNN. Extend the sequence; never reuse or renumber.
- **Neutral specification voice in design documents.** Not narrative ("we decided..."), not exploratory ("one approach might..."). Documents describe the design as it stands.
- **Open questions are separate.** Unresolved issues go in `open-questions.md` with a Q-NNN identifier. Spec documents do not carry inline caveats; they reference the question by id.
- **References section at the end of every design document.** Both outgoing and incoming citations, maintained in both directions.
- **`INDEX.md` is updated in the same pass** that adds a document or assigns an identifier.
- **No emoji. No headers ending in punctuation. No bullets where prose works.** The corpus reads like a research paper.

These conventions apply to documents in the design corpus (root, `decisions/`, `design/`). They do **not** apply to implementation code under `impl/`, which follows standard Kotlin idioms.

## Locked implementation decisions

- **Kotlin/JVM for the Milestone 2.1 prototype.** Iteration-speed choice over the research plan's Rust suggestion. Rust is still expected at Milestone 2.3 (bytecode VM); that rewrite is anticipated, not regretted.
- **JSON for authoring and on-wire; canonical CBOR for the content hash.** IPLD dag-json ↔ dag-cbor pattern. BLAKE3 over canonical CBOR per ADR-003. Children appear by hash, not inlined (Merkle DAG).
- **Implementation lives under `impl/`.** Design corpus at the root is untouched by implementation work.

## How to work with Jeff

- He expects substantive engagement, not validation. Push back when you disagree; bring independent thinking.
- He prefers concrete artifacts to discussion — explore briefly, then commit to code or documentation.
- He prefers **batched work** with independent research on Strand, not per-step review gates.
- Strand is research and is **distinct from his commercial Android work**. Do not import Android conventions (MVVM, lifecycle, Activity/Fragment, multi-module-because-Android) into Strand. The "high-level first, add complexity as needed" preference still applies.

## What not to do

- Do not regenerate or restructure the design corpus without being asked. It is settled.
- Do not propose a concrete syntax or a human-readable projection layer.
- Do not let the Kotlin implementation drift toward Android patterns.
- Do not skip the verifier — every node entering the store verifies first.
- Do not skip canonical encoding when computing hashes. Two implementations must produce identical hashes for identical graphs.
- Do not invent prior art. Citations rest on actual research; new claims about related work are verified, not invented.
