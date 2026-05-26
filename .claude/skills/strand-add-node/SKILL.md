---
name: strand-add-node
description: How to add a new node category (N-NNN) to the Strand programming language reference implementation, or advance any existing `proposals/<topic>.md` from draft to working code. Use this skill PROACTIVELY whenever the user mentions implementing a new node type, advancing a proposal, wiring up a specific N-NNN identifier (e.g., "implement N-043 Handler"), extending the node algebra, or starting a new layer of implementation work. Also triggers for phrasings like "implement recursive types", "advance the effect-handlers proposal", "the next thing on my list is X", "next up is the state-machines work", "let's tackle Layer 6", "let's work on Layer N step M", "add the Handler node", "extend Strand with X", any explicit reference to a file under `proposals/`, or any request to introduce new structure into the node algebra. Even if the user uses ambiguous phrasings like "let's do the effect handler thing" or "ok, recursive types next" where the work is implied from session context, this skill should trigger. The skill orchestrates a precise multi-step workflow spanning spec docs, the identifier registry, six Kotlin modules, corpus seed programs, and convention checks — work that is mechanical but error-prone without explicit guidance. If the user's request turns out to be adding a new **builtin** (a `strand-builtin:` target in `interpreter/Builtins.kt`) rather than a new sealed-class variant under `Node`, the skill recognizes this early and routes the user to `strand-add-builtin` instead — that has its own three-step coordinated workflow (registry, Layer A implicit-prelude entries, system-prompt documentation). For verifier-only checks with no new node category (e.g., a new exhaustiveness rule), this skill runs the appropriate subset rather than the full workflow.
---

# Adding a new node category to Strand

This skill walks through the full implementation of a new node category. The procedure is repeatable — the same shape was followed for Match, Fixpoint, ProductValue family, SumValue, ConstructorPattern, and RecursiveType. It touches spec documents, the identifier registry, six Kotlin modules, corpus programs, and tests, in a specific order designed to surface errors early.

## Why this is a skill and not just a checklist

Three things make a new node category genuinely tricky to add right:

1. **Strand's `Node` sealed class hierarchy means every `when (node)` in every downstream module breaks when you add a variant.** The dependency order matters — compile early and often, fix non-exhaustive `when` errors as they appear, don't try to write everything before the first build.

2. **Conventions need to hold across many files at once.** Neutral spec voice in design docs, exhaustive `when` (never `else`), structured-data errors (never strings), positional encoding for any new binder, "Last revised" lines updated. Easy to forget any one of these without an explicit checklist.

3. **Cross-document updates have to stay in sync.** Adding N-NNN means updating `design/node-algebra.md`, `INDEX.md`, possibly `open-questions.md`, possibly `proposals/`, then `impl-kotlin/CLAUDE.md`. Missing one leaves the corpus inconsistent for future sessions.

## When to use this skill vs other approaches

- **Use this skill** when adding any new sealed-class variant under `Node` — a true new node category that needs an N-NNN identifier.
- **Don't use this skill** for adding a new builtin to `interpreter/Builtins.kt` (use `strand-add-builtin` — that owns the three-step coordinated workflow across the registry, the Layer A implicit prelude, and the system prompt), adding a new corpus program over existing nodes (no new types), or fixing bugs in existing node behavior.
- **For research that hasn't been decided yet**, use `strand-research-proposal` first to produce a `proposals/<topic>.md`, then come back and use this skill to implement against the accepted proposal.

## The procedure (high level)

1. Orient: read project conventions and the current state of the node registry
2. Confirm the design with the user (unless a proposal file already settles it)
3. Assign the next free N-NNN identifier
4. Update the spec documents (in order)
5. Implement across the six Kotlin modules (in dependency order, compiling as you go)
6. Add corpus programs paired with natural-language descriptions
7. Add unit tests (verifier + interpreter, error paths and happy paths)
8. Update `impl-kotlin/CLAUDE.md` to reflect the new layer/feature status
9. Final clean test run; report the new test count

Each step is detailed below. The reference files in `references/` give per-file change templates and convention checks — load them as you reach the relevant step.

## Step 1: Orient

Read these in order, even if you've read them before — Strand has strong conventions and they're easy to slip on:

- `CLAUDE.md` (root) — project framing, non-negotiable conventions, how to work with Jeff
- `impl-kotlin/CLAUDE.md` — implementation state, layer scope, "Known gaps" section, "Building and testing" section
- `INDEX.md` § Identifier registry — find the next free N-NNN

If implementing against a proposal:
- `proposals/<topic>.md` — read in full. The proposal commits to specific design decisions; don't relitigate them unless something new has come up.

If the new node extends existing semantics:
- `design/node-algebra.md` — the section most relevant to the new node (e.g., § Composite values for value constructors, § Effects and capabilities for effect-related nodes)

## Step 2: Confirm the design with the user

Skip this step **only** if:
- The user explicitly said "just do it" or "implement the recursive types proposal"
- The proposal file in `proposals/` contains a complete, opinionated recommendation

Otherwise, before touching code, summarize back:
- What you're adding (one paragraph)
- The proposed N-NNN identifier
- Which existing nodes it extends or interacts with
- Any tradeoffs or open questions you noticed in the design

This is cheap (2 minutes) and prevents an hour of rework if you misread the proposal. Jeff prefers substantive engagement over plowing ahead.

## Step 3: Assign N-NNN

- Check `INDEX.md` § Identifier registry → "Node types (N-NNN)" table
- The next free number is the highest currently assigned + 1
- Important: existing numbers are **never** reused or renumbered
- If multiple node categories are part of one feature (like `ProductValue` + `ProductFieldValue` + `ProductFieldGet`), assign sequential N-NNNs in declaration order

If a proposal already claimed an identifier (e.g., effect-handlers.md claims N-043), use that one and note any prior reservations in `INDEX.md`'s identifier-coordination commentary.

## Step 4: Update spec docs (this exact order)

Do **doc updates before code**. The spec is the source of truth. The code follows.

1. **`design/node-algebra.md`**
   - Add an inventory row in the appropriate subsection (§ Types for type-level nodes, § Composite values for value constructors, etc.)
   - The row format: `| N-NNN | NodeName | edge1(mult) → Target1, edge2(mult) → Target2 | Notes explaining role and any structural identifiers |`
   - Update the `**Last revised:**` line at the top with a brief reason
   - Add or extend prose subsections only if the node introduces a new concept (e.g., recursive types needed a new "Recursive type expressions" sub-section to explain μ-binders)
   - Maintain the References section at the end (both outgoing and incoming citations)

2. **`INDEX.md`**
   - Add the new N-NNN row to the "Node types (N-NNN)" table in the identifier registry
   - Update the `**Last revised:**` line at the top
   - If the spec change also opens or resolves a Q-NNN, update the question-count summary line

3. **`open-questions.md`** (only if there was a related Q-NNN)
   - Mark it `Resolved` with the resolution pointing at the relevant `design/` doc section
   - Update the `**Last revised:**` line

4. **`proposals/<topic>.md`** (only if implementing a proposal)
   - Mark the proposal "Implemented as of <feature-name>" at the top, OR move it to `proposals/implemented/` (create that subdirectory if it doesn't exist)
   - Update `proposals/README.md`'s table to reflect the new status

**Voice convention** — design docs use neutral specification voice. Not "We decided to..." or "One approach might be...". State the design as it stands. See `references/conventions.md` for examples.

## Step 5: Implement across Kotlin modules (in dependency order)

This is where the compile-checkpoint discipline matters. After each module change, run:

```
./gradlew :MODULE:compileKotlin
```

If you skip compile checkpoints, the sealed `Node` hierarchy will produce non-exhaustive `when` errors in 4+ files at once and you'll lose orientation. Compile after each module so you can react to one error at a time.

The dependency order:

1. **`impl-kotlin/core/src/main/kotlin/org/strand/core/Node.kt`** — add the data class (or `object` if the node has no content fields, like `UnitLit` or `RecursiveSelf`) to the sealed `Node` hierarchy. Document with KDoc that explains: what the node represents, what its content fields mean, what its edges point at, and any semantic constraints the verifier will enforce.

2. **`impl-kotlin/core/src/main/kotlin/org/strand/core/Json.kt`** — add an ingest case in `buildNode`'s `when (type)` block. Use `obj.requireRef(...)` for required edges, `obj.optionalRef(...)` for optional edges, `obj.requireRefList(...)` for required edge lists, `obj.requireString(...)` for required string content fields. Update the "Unknown node type" rejection message's identifier range. If the new node is user-visible, extend the schema doc comment at the top of the file.

3. **`impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/CategoryTag.kt`** — add `val NodeName = CategoryTag(NNN)` matching the N-NNN. Group it with related tags.

4. **`impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt`** — add a dispatch case in `encodeDispatch` and write an `encodeNodeName(node, stack)` helper. **Critical encoding decisions:**
   - If the new node introduces a binder (extends the de Bruijn stack — like Lambda, Let, TypeAbstraction, ForallType, RecursiveType), the body is encoded in the extended stack and the new binders are pushed before encoding.
   - If the new node *references* a binder positionally (like VarRef, RecursiveSelf, bound TypeParameter), it emits `(depth, index)` rather than a hash reference.
   - Set-like edges (effect lists, capability lists) are sorted by hash bytes before encoding so declaration order doesn't affect identity.
   - List-like ordered edges (Application.arguments, Match.cases) are encoded in declaration order — order is structural.
   - For structural identifiers (field names, case names), include the UTF-8 bytes inline.

5. **`impl-kotlin/hashing/src/main/kotlin/org/strand/hashing/Hasher.kt`** — extend the `walk` function. Most nodes go in the main `when` block, recursing into children with the appropriate stack push. Nodes that are **bound** (have no standalone hash because they're intrinsic to their parent — ParameterDecl, TypeParameter, RecursiveSelf) get added to the early-return list at the top of `walk`.

6. **`impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt`** — depends on whether the node is an expression, a type, or structural:
   - **Expression nodes** (Match, Fixpoint, SumValue, ProductValue, ProductFieldGet, etc.): add a dispatch case in `infer` and write an `inferNodeName(id, node, scope, typeParams)` helper. The helper returns the node's `TypeExpr`, records the closure via `recordClosure(id, ...)`, and reports any errors.
   - **Type nodes** (PrimitiveType, FunctionType, ProductType, SumType, ForallType, RecursiveType): extend `resolveType` instead.
   - **Structural pieces** (MatchCase, Pattern, ProductFieldValue, EffectDecl in expression position): add to the catch-all "<expression position>" branch that rejects with `CategoryMismatch`. The node is still valid graph-wise; it's just not an expression on its own.

7. **`impl-kotlin/verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt`** — add new sealed-class data variants for each new error condition. Update the `categoryName` function with the new node category. Errors are typed data, never strings.

8. **`impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`** — depends on whether the node is value-producing or structural:
   - **Value-producing expressions**: add an `eval` case and any helper functions. If the node is callable (like Lambda, ForeignNode, Fixpoint), add a new `Value.NodeNameFn` to `Value.kt` and a dispatch in `applyCall`.
   - **Structural pieces** (Pattern, MatchCase, ProductFieldValue, type nodes): add to the catch-all that throws `InterpretError.NotCallable`.
   - New runtime errors go in `InterpretError.kt` as sealed-class variants.

See `references/file-touchpoints.md` for a detailed change template per file, including signature patterns for `inferNodeName` and `encodeNodeName`.

## Step 6: Add corpus programs

Add 2-3 JSON corpus programs in `corpus/` demonstrating the new feature. Number them sequentially after the highest current corpus program.

**Each program MUST be paired with a one-paragraph natural-language description in `corpus/README.md`**. This is the Phase 1 Stage 1.1 seed-corpus bootstrap — every JSON program is also a candidate seed-corpus entry. The description explains what the program does and what feature it exercises.

Patterns for choosing programs:
- A minimum example demonstrating the happy path
- A program combining the new feature with existing nodes (Fixpoint + Match, ProductValue + ProductFieldGet, etc.)
- A "capstone" program if the feature unlocks a new class of program (e.g., `32-recursive-list-sum.json` is the capstone for recursive types)

## Step 7: Register corpus and write unit tests

1. **Register** the new corpus programs in:
   - `impl-kotlin/corpus/src/test/kotlin/org/strand/corpus/CorpusTest.kt` (verify+run cases with expected `Value` results)
   - `impl-kotlin/corpus/src/test/kotlin/org/strand/corpus/CorpusHashingTest.kt` (hash-determinism tests)

2. **Verifier unit tests** in `impl-kotlin/verifier/src/test/kotlin/org/strand/verifier/VerifierTest.kt`:
   - One happy-path test for each new node showing it type-checks correctly
   - One test per new `VerifyError` variant exercising the rejection condition

3. **Interpreter unit tests** in `impl-kotlin/interpreter/src/test/kotlin/org/strand/interpreter/InterpreterTest.kt`:
   - One test per evaluation semantics
   - One test per new `InterpretError` variant if applicable

## Step 8: Update `impl-kotlin/CLAUDE.md`

Touch these sections as appropriate:

- **Layer status header** at the top — note the new layer/feature is complete
- **"Deferred to later layers"** table — update the relevant row
- **JSON schema documentation block** — add the new node's schema if user-visible
- **"Known gaps"** section — remove any gap this feature closes; add any new known limitation discovered during implementation

## Step 9: Final clean test run

```
./gradlew clean test
```

Then a forced fresh run to confirm:

```
./gradlew test --rerun-tasks 2>&1 | grep -cE " PASSED$"
```

Compare the count to what you expected:
- ≈ (verifier tests added) + (interpreter tests added) + 2 × (corpus programs added)

The 2× on corpus is because each program runs in both `CorpusTest` (verify+run) and `CorpusHashingTest` (hash determinism).

If the count is lower than expected, find the missing tests and verify they're registered. If a new test fails, fix it before declaring the feature complete.

## Final summary to the user

When the feature is in, give a concise summary structured like the ones in this session's history:

- What landed (new nodes, new schema, new error variants)
- Canonical encoding details (which encoder additions, any subtleties)
- Verifier additions (helpers, error variants, what changed in catch-all)
- Interpreter additions (new Value types if any, eval cases)
- Corpus (N programs added, with one-line descriptions)
- Tests (count: N new, total: M passing on fresh run)
- Doc updates (which files changed, why)
- Any genuine open questions or subtleties surfaced during implementation

## Convention checks

See `references/conventions.md` for the full checklist. The high-frequency ones:

- Neutral spec voice in design docs (not narrative, not exploratory)
- No emoji anywhere in design docs
- No headers ending in punctuation
- Exhaustive `when` over sealed classes — never `else`
- Structured-data errors via sealed-class variants — never strings
- `**Last revised:**` line at the top of any modified design doc, noting the reason
- References sections maintained in both directions at the end of every design doc
- INDEX.md updated in the same pass that adds an identifier

## Things not to do

- Don't propose a concrete syntax. The graph is the source (ADR-001, ADR-002).
- Don't skip the verifier. Every node entering the store verifies first.
- Don't skip canonical encoding when computing hashes. Two implementations must produce identical hashes for identical graphs.
- Don't invent prior art. New claims about related work are verified, not invented.
- Don't import Android conventions (MVVM, lifecycle) — Strand is plain JVM Kotlin.
- Don't regenerate or restructure the design corpus without being asked. It is settled.
