# Canonical Encoding {#canonical-encoding}

**Document:** `design/canonical-encoding.md`
**Status:** Normative specification, extracted from and continuously validated against the Kotlin reference implementation
**Last revised:** 2026-06-10 (initial version — specifies the byte-level canonical encoding and hash construction implemented by `impl-kotlin/hashing`, precisely enough that a second implementation can reproduce identical hashes without reading the Kotlin source)

## Summary

This document specifies the canonical byte encoding from which every Strand node's content hash is computed. It is the language-independent contract behind the rule that two implementations must produce identical hashes for identical graphs. [`node-algebra.md`](node-algebra.md) § Hash construction states the design principles (category tags, fixed field order, metadata exclusion, positional binding); this document gives the exact bytes: the digest and multihash format, the framing of each node category, the canonical-CBOR subset, the complete category-tag and discriminator tables, every presence-prefix and default-gating rule, the de Bruijn rules for binder references, and the metadata fields excluded from encoding. The committed conformance vectors in [`corpus/golden-hashes.json`](../corpus/golden-hashes.json) pin the result for every corpus program; `CanonicalEncodingSpecTest` in the reference implementation validates the byte traces in this document against the live encoder.

Normative statements describe the encoding as implemented. Where the implementation makes a choice that a second implementation could plausibly get wrong (string sort order, raw IEEE 754 bits, sentinel values), the choice is called out explicitly.

## Digest and serialized form {#digest}

The hash of a node is a *multihash* per [ADR-003](../decisions/ADR-003-content-addressing.md): a one-byte hash-function identifier followed by that function's digest bytes. The default and only implemented function is BLAKE3 with a 256-bit output, identified by the multicodec prefix byte `0x1e`; a Strand hash is therefore 33 bytes (1 prefix + 32 digest). SHA-256 is reserved under a different prefix by ADR-003 but is not implemented; an encoder must not emit it.

The digest input is the node's canonical encoding exactly as specified below, including the four-byte category tag. The digest is the default-parameter BLAKE3 hash (no key, no derive-key context) truncated to its standard 32-byte output.

The canonical serialized text form of a hash is the lowercase hexadecimal rendering of all 33 bytes — 66 hex characters beginning `1e`. This is the form used by `golden-hashes.json`, by the `targetHash` field of cross-store NodeRefs in dag-json documents, and by every tool surface that prints a hash.

## Framing {#framing}

The canonical encoding of a node is the concatenation

```
(4-byte big-endian category tag) || field_1 || field_2 || ... || field_n
```

where each field is encoded as described per category below. The result is deliberately **not** a single CBOR document: the leading tag is four raw bytes, not a CBOR item, and one field form (the inline bound-type-parameter reference) embeds further raw tag bytes inside positions that a CBOR decoder would expect to hold data items. The encoding is a byte string to be hashed, not a serialization format to be parsed; no decoder for it exists or is required.

Fields are fixed in number and order per category, except where an explicit presence prefix or default-gating rule (specified per category) varies the field count. There is no top-level length, no delimiter between fields, and no padding. Unambiguity is by construction: every variable-length field is a canonical-CBOR item carrying its own length, and the gated layouts are arranged so distinct logical contents never share a byte sequence.

Most fields are canonical-CBOR data items of one of four kinds, written in this document as:

| Notation | Meaning |
|----------|---------|
| `uint(n)` | CBOR major type 0 (unsigned integer), shortest form |
| `int(n)` | `uint(n)` when `n >= 0`; CBOR major type 1 with argument `-1 - n` when `n < 0` |
| `bytes(b)` | CBOR major type 2 (byte string) over the byte sequence `b`, definite length, shortest length form |
| `array(e_1, ..., e_k)` | CBOR major type 4 header with count `k`, followed by the concatenated encodings of the elements |

Two derived field forms reference child nodes:

| Notation | Meaning |
|----------|---------|
| `H(c)` | *Hash reference*: `bytes(m)` where `m` is the 33-byte multihash of child `c`'s canonical encoding, computed in the current binder context. With BLAKE3 the byte string is always 33 bytes, so its header is `0x58 0x21`. |
| `T(c)` | *Type reference*: if `c` is a TypeParameter bound in the current binder context, the inline positional form `tag(13) uint(depth) uint(index)` (six or more raw bytes, beginning with the four tag bytes `00 00 00 0d`); otherwise identical to `H(c)`. |

`T(c)` appears exactly at the positions marked "type reference" in the per-category layouts; everywhere else children are referenced by `H(c)`. UTF-8 string content (structural identifiers, literal strings, foreign targets) is always encoded as `bytes(utf8(s))` — a CBOR *byte* string, never a CBOR text string (major type 3 is unused). CBOR major types 1 and 0 are used as described under `int`; major types 3, 5, 6, and 7 are never emitted (booleans encode as `uint(0)`/`uint(1)`, floats as raw bit patterns inside byte strings).

### Canonical CBOR subset {#canonical-cbor}

The CBOR items above follow RFC 8949 § 4.2.1 canonical form restricted to major types 0, 1, 2, and 4: definite-length encoding always, and the shortest argument encoding for every integer value and length (an argument in `0..23` is packed into the initial byte; `24..255` uses the one-byte form `0x18+`; up to `65535` the two-byte form; up to `4294967295` the four-byte form; otherwise the eight-byte form). Arguments are treated as unsigned 64-bit values.

## Hashes are contextual; node identity is positional {#context}

A node's hash is a function of the node *and of the binder context in which it is encoded*. The context is a stack of binder frames (§ Binder references below); the hash of a subgraph that references an enclosing binder changes with the binder's position, which is exactly what makes alpha-equivalent terms hash identically. Consequently the per-program `node → hash` map produced by the reference implementation records, for each reachable node, its hash in the context of its position in the program; a shared subtree reached from positions with identical contexts hashes once (deduplication), while the same `VarRef` node under different binder depths would hash differently.

Only `NodeRef` targets are guaranteed context-independent: the verifier's closure rule (`NodeRefTargetMustBeClosed`) rejects targets whose subgraph references outside binders, so a NodeRef target's hash — always computed under the empty context — is valid in any position. This is the property that makes cross-store references by bare hash sound.

Three node categories never receive a standalone hash: `ParameterDecl` (N-015) is intrinsic to its Lambda, which inlines the parameter *types* and represents the binding positionally; `TypeParameter` (N-013) has no content encoding at all — its name and optional bound are discarded and its identity is purely positional; `RecursiveSelf` (N-042) is intrinsic to its enclosing RecursiveType. The reachable-hash walk skips all three. `TypeParameter` and `RecursiveSelf` nevertheless have *encoding forms* (the tag-13 reference and the tag-42 depth record) that appear inline within parent encodings; asking the encoder for a standalone `ParameterDecl` encoding is an error.

## Binder references {#binders}

The encoder maintains one shared stack of binder frames spanning both term-level and type-level binders, plus a separate counter for recursive-type binders. A frame is an ordered list of binding nodes. Frames are pushed when encoding the *body* (and only the body) of the categories below:

| Category | Frame contents, in order |
|----------|--------------------------|
| Lambda (N-014) | the `parameters` list (ParameterDecl nodes), declaration order |
| Let (N-017) | a single entry: the Let node itself |
| TypeAbstraction (N-034) | the `typeParameters` list, declaration order |
| ForallType (N-035) | the `typeParameters` list, declaration order |
| MatchCase (N-024) | every VariablePattern in the case's pattern tree, in depth-first order following `ConstructorPattern.payloadPattern` edges; **no frame is pushed when this list is empty** |
| RecursiveType (N-041) | no frame on the shared stack; a dedicated recursive-binder counter is incremented instead |

A `VarRef` (N-018) resolves its `binder` node against the shared stack, scanning frames from innermost to outermost: `depth` is the number of frames between the reference and the matched frame (0 = innermost), `index` is the binder's position within that frame. A reference to a bound `TypeParameter` resolves identically against the same stack. Because the stack is shared, a term binder between a type binder and a type-parameter reference increases that reference's depth: in `Λa. λx:Int. (... a ...)`, a reference to `a` from inside the lambda body sees depth 1.

The MatchCase empty-frame rule is load-bearing: a case whose pattern binds nothing (wildcard, literal, binder-free constructor patterns) pushes no frame, so references from the case body to outer binders keep the depth they would have outside the Match.

`RecursiveSelf` does not search the stack. Its author-supplied `depth` field counts enclosing RecursiveType binders (0 = innermost) and is emitted directly after validation against the recursive-binder counter.

### Unbound sentinels {#sentinels}

Hashing runs before verification (the hash is needed to admit the node the verifier then inspects), so the encoder must produce bytes for graphs the verifier will reject. An unresolvable reference encodes a deterministic sentinel instead of failing:

| Situation | Sentinel bytes |
|-----------|----------------|
| `VarRef` whose binder is not on the stack | `uint(2147483647) uint(2147483647)` for `(depth, index)` — each `0x1a 0x7f 0xff 0xff 0xff` |
| `TypeParameter` reference not bound by any frame | same `(2147483647, 2147483647)` pair under tag 13 |
| `RecursiveSelf` whose depth is negative or ≥ the recursive-binder count | `uint(9223372036854775807)` (`Long.MAX_VALUE`) |

The sentinels cannot collide with real positions: real frames are bounded well below `2^31 - 1` entries and real recursion depths below `2^63 - 1`. A graph containing a sentinel still receives a stable hash; the verifier reports the actual ill-formedness downstream. One defensive corner follows from the `T(c)` rule: a *free* TypeParameter at a type-reference position fails the bound check, so it is encoded as `H(c)` — a hash reference whose preimage is the tag-13 sentinel form.

## Category tags {#category-tags}

Tags are stable numeric identifiers drawn from the N-NNN registry; once published a tag is never reused, renumbered, or reassigned (per [`node-algebra.md`](node-algebra.md) § Versioning). The complete current assignment:

| Tag | Category | N-NNN | Notes |
|-----|----------|-------|-------|
| 1 | IntLit | N-001 | |
| 2 | FloatLit | N-002 | |
| 3 | StringLit | N-003 | |
| 4 | BoolLit | N-004 | |
| 5 | UnitLit | N-005 | tag only, no fields |
| 6 | BytesLit | N-006 | |
| 7 | PrimitiveType | N-007 | |
| 8 | ProductType | N-008 | |
| 9 | ProductTypeField | N-009 | |
| 10 | SumType | N-010 | |
| 11 | SumTypeCase | N-011 | |
| 12 | FunctionType | N-012 | |
| 13 | TypeParameter reference | N-013 | positional `(depth, index)` form only; TypeParameter nodes are never independently hashed |
| 14 | Lambda | N-014 | |
| 15 | (reserved, unused) | N-015 | ParameterDecl is intrinsic to Lambda and has no standalone encoding |
| 16 | Application | N-016 | |
| 17 | Let | N-017 | |
| 18 | VarRef | N-018 | |
| 19 | NodeRef | N-019 | |
| 20 | ForeignNode | N-020 | |
| 21 | EffectCategory | N-021 | |
| 22 | EffectDecl | N-022 | |
| 23 | Match | N-023 | |
| 24 | MatchCase | N-024 | |
| 25 | Pattern | N-025 | one tag for all four variants; a leading kind discriminator (§ Discriminators) selects the variant |
| 26 | Fixpoint | N-026 | |
| 27 | StateMachine | N-027 | |
| 28 | EventStream | N-028 | |
| 29 | Transition | N-029 | |
| 32 | Schema | N-032 | |
| 33 | Invariant | N-033 | |
| 34 | TypeAbstraction | N-034 | |
| 35 | ForallType | N-035 | |
| 36 | CapabilityScope | N-036 | |
| 37 | ProductValue | N-037 | |
| 38 | ProductFieldValue | N-038 | |
| 39 | ProductFieldGet | N-039 | |
| 40 | SumValue | N-040 | |
| 41 | RecursiveType | N-041 | |
| 42 | RecursiveSelf | N-042 | |
| 43 | Handler | N-043 | |
| 44 | ToolDef | N-044 | |
| 45 | ResponseSchemaSpec | N-045 | |
| 46 | ModuleManifest | N-046 | |
| 47 | Attempt | N-047 | single child (the body), no content fields, introduces no binder |

Values 30 and 31 carry no assignment: N-030 Name and N-031 Provenance have no canonical encoding of their own. A structural Name's UTF-8 content is inlined into its parent's encoding (§ Hash construction of `node-algebra.md`), and Provenance is metadata, excluded entirely.

## Set-like and positional edge lists {#sets-and-lists}

Edge lists fall into three ordering disciplines. *Positional* lists encode in declaration order because order is semantically significant: `FunctionType.parameters`, `Lambda` parameter types, `Application.arguments` and `typeArguments`, `EffectCategory.parameters`, `EffectDecl.parameters`, `Match.cases` (first match wins), `StateMachine.inputStreams` and `outputStreams` (stream index is the runtime wiring key), `ModuleManifest.exports`, the effect-projection list (entry *i* covers effect *i*), and each projection's `sources` (entry *i* covers category parameter *i*).

*Hash-sorted sets* encode each element's 33-byte multihash and sort the hashes as byte strings, lexicographically by unsigned byte value, so declaration order does not affect identity: `Lambda.effects`, `FunctionType.effects`, `ForeignNode.effects`, `StateMachine.effects`, `Application.effectInstances`, `CapabilityScope.capabilities`, `Schema.invariants`, and each manifest export's `declaredEffects`. The encoder sorts but does not deduplicate; a duplicate entry appears twice (the verifier owns rejecting such graphs).

*Name-sorted* lists sort child nodes by a structural-identifier string before encoding their hashes: `ProductType.fields` and `ProductValue.fields` by `fieldName`, `SumType.cases` by `caseName`. The comparison is lexicographic over the string's **UTF-16 code units** (the natural ordering of a JVM string), not over its UTF-8 bytes. The two orders agree for all of the Basic Multilingual Plane below the surrogate range — in particular for all ASCII — but diverge when supplementary-plane code points (≥ U+10000) are compared against code points in U+E000..U+FFFF: UTF-16 code-unit order places the supplementary character first, UTF-8 byte order places it last. A second implementation whose native string comparison is UTF-8-based (e.g. Rust `str` ordering) must implement UTF-16 code-unit comparison for these three sorts. The sort is stable; for the ill-formed case of duplicate names (verifier-rejected, but hashable pre-verification) ties keep declaration order.

## Per-category encodings {#per-category}

Layouts are written as the field sequence following the four tag bytes. "outer context" and "body context" name the binder stack in which a child's hash is computed; absent a note, children are hashed in the context the parent was encoded in.

### Literals

| Category | Fields |
|----------|--------|
| IntLit (1) | `int(value)` — value is a signed 64-bit integer |
| FloatLit (2) | `bytes(b)` where `b` is the 8-byte big-endian IEEE 754 binary64 bit pattern |
| StringLit (3) | `bytes(utf8(value))` |
| BoolLit (4) | `uint(1)` for true, `uint(0)` for false |
| UnitLit (5) | no fields |
| BytesLit (6) | `bytes(value)` |

FloatLit encodes the *raw* bit pattern (`Double.toRawBits`), not a CBOR float: `0.0` and `-0.0` hash differently, and NaNs with distinct bit patterns are distinct nodes. No normalization is applied.

### Types

| Category | Fields |
|----------|--------|
| PrimitiveType (7) | `uint(kind)` per the Primitive table (§ Discriminators) |
| ProductType (8) | `array(H(f_1), ..., H(f_n))` — fields sorted by `fieldName` (§ Set-like and positional edge lists) |
| ProductTypeField (9) | `bytes(utf8(fieldName))`, `T(fieldType)` |
| SumType (10) | `array(H(c_1), ..., H(c_n))` — cases sorted by `caseName` |
| SumTypeCase (11) | `bytes(utf8(caseName))`, then `uint(0)` for a nullary case or `uint(1)` followed by `T(caseType)` |
| FunctionType (12) | `array(T(p_1), ..., T(p_n))`, `T(result)`, `array(sorted H(effect)...)`; when `effectProjections` is non-empty, one additional projection-list field (§ Effect projections) |
| ForallType (35) | `uint(arity)` — the count of `typeParameters` — then `T(body)` with a frame of those parameters pushed |
| RecursiveType (41) | `T(body)` with the recursive-binder counter incremented for the duration of the body |
| RecursiveSelf (42) | `uint(depth)`, or the sentinel (§ Unbound sentinels) when out of range |

ForallType and TypeAbstraction encode only the *arity* of their binder list; the TypeParameter nodes themselves contribute nothing (their names and bounds are discarded), which is what makes alpha-equivalent quantified types hash identically. The bound-TypeParameter reference form under tag 13 is `uint(depth) uint(index)` and appears only inline at `T(...)` positions.

### Functions and binding

| Category | Fields |
|----------|--------|
| Lambda (14) | `array(T(paramType_1), ..., T(paramType_n))` — each parameter's declared type, in the **outer** context — then `H(body)` in the body context (one frame of the ParameterDecls), then `array(sorted H(effect)...)` in the **outer** context |
| Application (16) | `H(function)`, `array(H(arg)...)`, `array(T(typeArg)...)`; when `effectInstances` is non-empty, an additional `array(sorted H(effectInstance)...)` |
| Let (17) | `H(value)` in the outer context, `H(body)` with a single-entry frame holding the Let node itself |
| VarRef (18) | `uint(depth)`, `uint(index)` of the binder, or the sentinel pair |
| TypeAbstraction (34) | `uint(arity)`, `H(body)` with a frame of the type parameters pushed |

The Application `effectInstances` field is gated on `size > 0`: a pure or pre-Q-031 Application encodes exactly three fields, and an Application with declared instances appends the sorted-set array as a fourth. The gate preserves pre-Q-031 hashes.

### References

| Category | Fields |
|----------|--------|
| NodeRef (19) | `bytes(target)` — the referenced node's 33-byte multihash, computed under the empty binder context |
| ForeignNode (20) | `bytes(utf8(target))` — the binding identifier string, e.g. `strand-builtin:Int.Add` — then `H(foreignType)`, `array(sorted H(effect)...)`; when `effectProjections` is non-empty, one additional projection-list field |

A NodeRef's encoding is the same whether the implementation holds the target inline (a local reference resolved during finalization) or only its hash (a cross-store reference): the bytes are the target's multihash either way. ForeignNode's optional `binding → Provenance` edge is metadata and absent from the encoding.

### Effects and capabilities

| Category | Fields |
|----------|--------|
| EffectCategory (21) | `bytes(utf8(categoryName))`, `array(T(param)...)` |
| EffectDecl (22) | `H(effectType)`, `array(H(param)...)` |
| CapabilityScope (36) | `array(sorted H(capability)...)`, `H(body)` |
| Handler (43) | `H(intercept)`, `H(handle)`, `H(body)` — three fixed hash references; Handler introduces no binder |

### Effect projections

When a FunctionType or ForeignNode carries a non-empty `effectProjections` list (Q-039), one additional field is appended after the effects array. Unlike the framing-level fields, this field and its contents are well-formed nested CBOR:

```
array( projection_1, ..., projection_n )            -- declaration order; entry i covers effects[i]
projection_i = array( H(category), array(source_1, ..., source_k) )   -- sources positional
source       = array( uint(0), uint(index) )        -- ArgRef: the function's argument at index
             | array( uint(1), H(target) )          -- LiteralNode: a binding-controlled literal node
```

The gate on non-empty preserves every pre-Q-039 hash. The projection list and each `sources` list are positional (their order parallels `effects` and the category's parameters respectively); only the `effects` array itself is hash-sorted.

### Control flow

| Category | Fields |
|----------|--------|
| Match (23) | `H(scrutinee)`, `array(H(case)...)` in declaration order |
| MatchCase (24) | `H(pattern)` in the **outer** context, `H(body)` in the body context (one frame of the pattern's VariablePatterns in depth-first order, omitted when the pattern binds nothing) |
| Fixpoint (26) | `H(recursionType)`, `H(body)` — both plain hash references; the body Lambda's recursive-call slot is a verification fact, not an encoding one |
| Attempt (47) | `H(body)` — a single hash reference; Attempt has no content fields and introduces no binder, so the body is hashed in the surrounding binder context |

Attempt's `Ok(T) | Err({kind, detail})` result type is a verification fact synthesized by the verifier, not an encoding one: nothing about the result sum, the catchable-failure taxonomy, or the error payload appears in the bytes. Two Attempts over hash-identical bodies are the same node.

Pattern (25) layouts share the tag and lead with a kind discriminator:

| Variant | Fields |
|---------|--------|
| LiteralPattern | `uint(0)`, `T(patternType)`, `H(literal)` |
| VariablePattern | `uint(1)`, `T(patternType)` — the bound name is metadata and absent |
| WildcardPattern | `uint(2)`, `T(patternType)` |
| ConstructorPattern | `uint(3)`, `T(patternType)`, `bytes(utf8(caseName))`, then `uint(0)` for no payload pattern or `uint(1)` followed by `H(payloadPattern)` |

### Composite values

| Category | Fields |
|----------|--------|
| ProductValue (37) | `H(ofType)`, `array(H(f)...)` — fields sorted by `fieldName` |
| ProductFieldValue (38) | `bytes(utf8(fieldName))`, `H(value)` |
| ProductFieldGet (39) | `H(target)`, `bytes(utf8(fieldName))` |
| SumValue (40) | `H(ofType)`, `bytes(utf8(caseName))`, then `uint(0)` for a nullary case or `uint(1)` followed by `H(payload)` |

### State machines

| Category | Fields |
|----------|--------|
| StateMachine (27) | `H(transitionFn)`, `H(initialState)`, `array(H(in)...)` positional, `array(H(out)...)` positional, `array(sorted H(effect)...)` |
| Transition (29) | `uint(0)` for no guard or `uint(1)` followed by `H(guard)`, then `H(body)` |

EventStream (28) carries the most intricate gating, accreted in two additive-versioning steps that each preserve all earlier hashes. The base fields are `H(eventType)` and `uint(streamKind)`. Three optional fields — `bufferSize`, `overflowPolicy`, `consumerMode` — are *all-default-omitted*: when `bufferSize` is unset, `overflowPolicy` is unset or `BlockProducer`, and `consumerMode` is unset or `Single`, none of the three is encoded. When at least one is set to a non-default value, **all three** are encoded in the order bufferSize, overflowPolicy, consumerMode, with sentinels for those left at default: `uint(bufferSize)` with `0` for unset, the overflow-policy tag (§ Discriminators) with the `Sample` variant followed by `uint(intervalNanos)`, and `uint(consumerMode)` with `0` (Single) for unset. Finally, the optional `source` edge (Q-046) is gated on non-null: when present, `H(source)` is appended as a single trailing field after whichever of the two layouts precedes it. The resulting field counts — 2 (all default), 3 (default plus source), 5 or 6 (non-default, Sample adding one), 6 or 7 (non-default plus source) — are collision-free: the only overlapping count pairs differ in the major type of the trailing field (byte string for `source` versus unsigned integer for `consumerMode`).

### Structured outputs and agent-native capabilities

| Category | Fields |
|----------|--------|
| Schema (32) | `bytes(utf8(schemaName))`, `H(valueType)`, `array(sorted H(invariant)...)` |
| Invariant (33) | `bytes(utf8(invariantName))`, `H(body)` — `targetSchema` is excluded (below) |
| ToolDef (44) | `H(parameterSchema)`, `H(implementation)` — `name` and `description` are excluded |
| ResponseSchemaSpec (45) | `H(schema)` |

`Invariant.targetSchema` is excluded to break the hash cycle Schema → invariants → targetSchema → Schema; the parent Schema's `invariants` list is the authoritative association, matching the precedent that ProductTypeField and SumTypeCase do not encode their parent. Two Invariants with equal `(invariantName, body)` hash identically regardless of which Schema they were authored against.

### Composition and distribution

ModuleManifest (46) encodes a single field, `array(export_1, ..., export_n)` in declaration order, where each export is well-formed nested CBOR:

```
export_i = array( bytes(target), array(sorted H(declaredEffect)...) )
```

`target` is the exported node's 33-byte multihash (NodeRef-style boundary; identical bytes whether resolved from an in-document node during finalization or supplied as a hash). Each export's `declaredEffects` is a hash-sorted set. `displayName` (per export) and `manifestSignature` (per manifest) are metadata, excluded.

## Metadata exclusions {#metadata-exclusions}

The following content present in the node algebra or the in-memory representation is excluded from every canonical encoding. Attaching, changing, or removing it never changes a hash.

| Owner | Excluded content |
|-------|------------------|
| ParameterDecl (N-015) | `name` (the entire node has no standalone encoding; only its `paramType` reaches the parent Lambda's bytes) |
| Let (N-017) | `name` |
| Pattern.VariablePattern (N-025) | `name` |
| TypeParameter (N-013) | `name` and the optional `bound` — identity is purely positional |
| ToolDef (N-044) | `name`, `description` |
| ModuleManifest (N-046) | `manifestSignature`; each export's `displayName` |
| Invariant (N-033) | `targetSchema` (cycle break, § above) |
| ForeignNode (N-020) | the optional `binding → Provenance` edge |
| Schema (N-032) | the reserved `libraryBinding → Provenance` edge |
| any node | Provenance (N-031) attachments generally, and Name (N-030) when attached as a tooling-grade label rather than appearing as a structural identifier |

Structural identifiers — `fieldName`, `caseName`, `categoryName`, `schemaName`, `invariantName`, `streamKind`, the ForeignNode `target` string, the SumValue and ConstructorPattern `caseName`, the ProductFieldValue and ProductFieldGet `fieldName` — are NOT metadata; their UTF-8 bytes (or discriminator value) appear at fixed positions per the layouts above.

## Discriminators and ordinals {#discriminators}

All discriminator assignments are frozen; extending an enumeration appends new values without renumbering.

| Enumeration | Assignment |
|-------------|------------|
| Primitive (PrimitiveType `kind`) | Int 0, Float 1, String 2, Bool 3, Unit 4, Bytes 5 |
| StreamKind (EventStream) | External 0, Internal 1, Output 2 |
| OverflowPolicy tag (EventStream) | BlockProducer 0, DropNewest 1, DropOldest 2, Sample 3 (followed by `uint(intervalNanos)`) |
| ConsumerMode (EventStream) | Single 0, Broadcast 1 |
| Pattern kind | LiteralPattern 0, VariablePattern 1, WildcardPattern 2, ConstructorPattern 3 |
| ProjectionSource tag | ArgRef 0, LiteralNode 1 |
| Multihash prefix | BLAKE3-256 `0x1e` (32-byte digest); SHA-256 reserved per ADR-003, unimplemented |

## Worked example {#worked-example}

Two nodes traced byte by byte. Both traces are asserted verbatim by `CanonicalEncodingSpecTest` in the reference implementation, and the first is corpus program 01, so its hash also appears in `golden-hashes.json`.

**IntLit 42.** Category tag 1, one field `int(42)`. The value 42 exceeds 23, so canonical CBOR uses the one-byte-argument form:

```
00 00 00 01   category tag 1, big-endian
18 2a         CBOR major type 0, argument 42
```

BLAKE3 over these six bytes, prefixed with `0x1e`, gives the multihash

```
1e6970ba0a8f8e82923c82d5b40927ba0ba9d0dcfc526c3308e509a28e9f16caad
```

which is the committed golden root hash of `corpus/01-int-literal.json`.

**The monomorphic identity lambda** `λx: Int. x`, encoded under the empty context. Three nodes contribute. The parameter type `PrimitiveType(Int)` encodes as tag 7 plus `uint(0)` (Int's ordinal):

```
00 00 00 07 00
→ H_Int = 1e2275d419cd5ebfc869bc21332c53331f3faa772e7fdd0a40ff200aee6d43bfdc
```

The body is a VarRef to the parameter. Inside the body the context holds one frame containing the ParameterDecl, so the reference resolves to depth 0, index 0:

```
00 00 00 12   category tag 18
00 00         uint(0) depth, uint(0) index
→ H_x = 1ed076d59afd79a1e7410badf0a9120a045b693832b29089774fa6903b5d1a3480
```

The Lambda itself is tag 14 followed by the parameter-type array (one element, a hash reference since `Int` is not a bound TypeParameter), the body's hash reference, and the empty effect set:

```
00 00 00 0e   category tag 14
81            CBOR array header, 1 element
58 21 <H_Int> hash reference: byte string of 33 bytes
58 21 <H_x>   hash reference to the body
80            CBOR array header, 0 elements (no declared effects)
→ 1ec30a2eadd3a4e604aeecb08d48c115207a4d3484f70f7360e5643de34c48e7c3
```

Renaming `x` changes nothing above — the name appears nowhere — which is alpha-equivalence by construction. Had the parameter type been a TypeParameter bound by an enclosing TypeAbstraction, the array element would instead be the six inline bytes `00 00 00 0d 00 00` (tag 13, depth 0, index 0) with no byte-string header.

## Conformance {#conformance}

[`corpus/golden-hashes.json`](../corpus/golden-hashes.json) commits the root hash of every corpus program (including deliberately verifier-failing fixtures, since hashing precedes verification) and of every Layer A fixture's compiled canonical form. A second implementation conforms when it reproduces every committed hash from the same inputs; the reference implementation's `CorpusGoldenHashTest` asserts the file continuously and enforces that corpus and goldens stay in bidirectional sync, and `CanonicalEncodingSpecTest` asserts the byte-level traces stated in this document. The regeneration procedure for legitimate corpus changes is documented in [`corpus/README.md`](../corpus/README.md); any hash change outside such a regeneration is a compatibility break.

## References

**Outgoing references:**
- [`node-algebra.md`](node-algebra.md) — node inventory, edge schemas, and the design principles of § Hash construction that this document refines to the byte level
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — content addressing, multihash format, BLAKE3 selection
- [`corpus/golden-hashes.json`](../corpus/golden-hashes.json) — committed conformance vectors for every corpus program
- [`corpus/README.md`](../corpus/README.md) — golden-vector regeneration procedure

**Incoming references:**
- [`node-algebra.md`](node-algebra.md) — § Hash construction defers byte-level detail here
- [`INDEX.md`](../INDEX.md)
