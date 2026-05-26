# Strand convention checks

Conventions to enforce while adding a node category. These are settled per `CLAUDE.md` (root) and `impl-kotlin/CLAUDE.md`. Don't relitigate without asking.

## Voice and formatting (design docs)

### Neutral specification voice

Design documents describe the design as it stands. Not narrative, not exploratory.

**Bad:**
- "We decided to use BLAKE3 because..."
- "One approach might be to..."
- "It turns out that..."
- "After some discussion, we settled on..."

**Good:**
- "The hash function is BLAKE3 over the canonical CBOR encoding."
- "Strand adopts equirecursive semantics for RecursiveType."
- "The body must be contractive."

### No emoji

Anywhere in design docs. Anywhere in spec text. Implementation code comments are also emoji-free per project style.

### No headers ending in punctuation

Section headers don't end in `?`, `!`, or `.`. Plain noun phrases or descriptive phrases.

**Bad:**
- `## Why this matters?`
- `## The cool part!`
- `## Recursive types in Strand.`

**Good:**
- `## Why this matters`
- `## Recursive types in Strand`
- `## Hash construction`

### "Last revised" lines

Any design document that gets revised gains or updates a `**Last revised:** YYYY-MM-DD (reason)` line at the top, right under the title and document path. The reason is a one-line summary of what changed.

Example:

```markdown
**Document:** `design/node-algebra.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-23 (N-041 RecursiveType and N-042 RecursiveSelf added — closes the recursive-type gap via μ-binder positional encoding)
```

When a doc has multiple revisions in one session, append to the existing line — don't overwrite. The convention is one cumulative line that reads as a changelog.

### References sections

Every design document ends with a `## References` section listing both:
- **Outgoing references:** which other corpus documents this one cites
- **Incoming references:** which other documents cite this one

When you add a new reference (either direction), update both ends. INDEX.md has a cross-reference table that should also be kept current.

## Implementation code style

### Plain JVM Kotlin

No Android SDK, no MVVM, no lifecycle, no Activity/Fragment, no DI framework. Standard Kotlin idioms.

### Sealed classes and exhaustive `when`

The node ADT and the typed-error hierarchies (`VerifyError`, `InterpretError`) are sealed. `when` over a sealed type must be exhaustive — **never an `else` branch**. If you find yourself wanting `else`, add the specific variants instead. The compiler-enforced exhaustiveness is one of the main reasons the project uses sealed classes.

The exception: when you genuinely want a default behavior (like "this is an unreachable case, throw a programmer-error"), use an explicit `else -> error(...)` only after listing all expected variants.

### Structured-data errors

Errors are sealed-class data variants, not strings. Every new failure mode extends the relevant sealed hierarchy with a `data class` carrying the structured reason data.

**Bad:**
```kotlin
throw IllegalStateException("expected SumType, got ${type::class.simpleName}")
```

**Good:**
```kotlin
report(VerifyError.CategoryMismatch(
    at = id,
    field = "SumValue.ofType",
    expectedCategory = "SumType",
    actualCategory = type::class.simpleName ?: "?"
))
```

### KDoc on new node types and helpers

Every new `Node` variant gets KDoc explaining what it represents, what its fields and edges mean, and any semantic constraints. Same for any new `TypeExpr` variant, `Value` variant, encoder helper, verifier helper.

### Author IDs vs NodeIds

JSON ingest converts author IDs (string keys in the JSON) to opaque `NodeId`s in a single pass. Forward references inside a document are allowed. Layer 2 will eventually replace `NodeId`s with BLAKE3 hashes; preserve that seam.

## Encoding correctness (Layer 2)

### Alpha-equivalence at canonical encoding

Any new binder (introduces NodeIds bound in its body) must use positional encoding for its bound references. The encoder maintains a `BinderStack`; pushing/popping is done in the binder's encoder helper.

References to bound variables emit `(depth, index)` rather than a hash reference. Two binders that differ only in the names of their bound variables hash identically.

The existing examples to look at:
- `encodeLambda` and `encodeVarRef` — term-level binder
- `encodeForallType` and bound TypeParameter — type-level binder
- `encodeRecursiveType` and `encodeRecursiveSelf` — recursive-type binder
- `encodeMatchCase` and `collectPatternBinders` — pattern-introduced binders

### Hash dedup for structural identifiers

If a new node has a string-valued structural identifier (field name, case name, category name, target identifier), include the UTF-8 bytes in the canonical encoding. Two nodes that differ only in this identifier must hash differently.

For ordered collections of named children (ProductType fields, SumType cases), sort by the structural identifier name before encoding so declaration order doesn't affect identity.

For set-like collections (effect lists, capability lists), sort by hash bytes before encoding.

### Bound nodes are not standalone-hashable

If a node only makes sense relative to a binder (ParameterDecl is intrinsic to Lambda; TypeParameter to ForallType; RecursiveSelf to RecursiveType), it doesn't get a standalone hash entry in `hashReachable`. Add it to the early-return guard at the top of `Hasher.walk`.

## Cross-document consistency

### INDEX.md updated in the same pass

When you assign a new identifier (N-NNN, Q-NNN, E-NNN, ADR-NNN), update INDEX.md in the same commit/change. Identifier registries must not lag behind the documents that introduce identifiers.

### Open questions are separate

Specification documents do not contain inline caveats about uncertainty. If something is unresolved, it goes in `open-questions.md` with a Q-NNN identifier, and the spec doc references the question by ID rather than including the caveat inline.

### Proposals get moved or marked when implemented

When a proposal in `proposals/` is implemented, mark it "Implemented in <feature>" at the top, OR move it to `proposals/implemented/` (create that subdirectory if not present). Update `proposals/README.md` table.

## Project context (for collaborator behavior)

### Jeff's working style

- Expects substantive engagement, not validation. Push back when you disagree.
- Prefers concrete artifacts over discussion. Explore briefly, then commit to code or doc.
- Prefers **batched work** with independent thinking. Don't seek per-step approval — show concrete progress and only escalate genuine decisions.
- Strand is research, distinct from his commercial Android work. Don't import Android conventions.

### Load-bearing decisions (don't relitigate)

- ADR-001: graph-native, no concrete syntax
- ADR-002: no human-readable projection layer
- ADR-003: content addressing with BLAKE3, alpha-equivalence at hash time (the spec was clarified during this session — see ADR-003 itself for the resolved form)
- ADR-004: effects as mandatory typed edges
- design/node-algebra.md: § Hash construction (clarified: structural identifiers are NOT metadata; the metadata-exclusion rule depends on edge role, not target category)

## Things never to do

- Don't propose a concrete syntax. The graph is the source.
- Don't add a human-readable projection layer.
- Don't skip the verifier — every node entering the store verifies first.
- Don't skip canonical encoding when computing hashes. Two implementations must produce identical hashes for identical graphs.
- Don't invent prior art. Citations rest on actual research; new claims about related work are verified.
- Don't let the Kotlin implementation drift toward Android patterns.
- Don't regenerate or restructure the design corpus without being asked. It is settled.
