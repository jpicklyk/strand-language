# Node Algebra {#node-algebra}

**Document:** `design/node-algebra.md`
**Status:** Wave 3 draft
**Last revised:** 2026-05-26 (N-045 ResponseSchemaSpec added to the "Agent-native capabilities" subsection — symmetric Schema-bearing wrapper for the `GenerateRequest.responseSchema` field, completing the pair started by N-044 ToolDef. The wrapper has a single `schema → Schema` edge, no metadata content fields; the verifier statically projects the Schema's `valueType` to JSON Schema at admission (`ResponseSchemaTypeUnsupported` on rejection). Both N-044 ToolDef and N-045 ResponseSchemaSpec now cover the two Schema-bearing positions on `GenerateRequest` through structural graph edges rather than the `Option<JsonValue>` expedient.) 2026-05-26 (N-044 ToolDef added under a new "Agent-native capabilities" subsection — promotes the Q-037 Phase 1 ToolDef from a runtime `ProductV` convention to a structural graph node per the original proposal § 3.8 design. Closes the Q-037 deviation #1 partial `ToolParamTypeUnsupported` verifier walk: the rule now fires on every static ToolDef by construction. The `parameterSchema` edge points at an N-032 Schema, bringing tool parameters into Strand's structural-reasoning machinery (Schema + SchemaChecker + JsonSchemaProjection). `name` and `description` are metadata-only and excluded from the canonical encoding, matching ParameterDecl.name treatment.) 2026-05-24 (N-032 Schema and N-033 Invariant inventory rows added under a new "Structured outputs" subsection — slots were pre-reserved by `rendering-and-views.md` since Wave 3 design and are now implemented in the Kotlin/JVM reference implementation per `proposals/implemented/schema-and-invariant.md`. Full edge schemas, well-formedness rules, and canonical encoding remain documented in `rendering-and-views.md` § Schema mechanism; the inventory rows here are minimal pointers. One implementation deviation worth noting: `Invariant.targetSchema` is excluded from canonical encoding to break the Schema↔Invariant hash cycle.) 2026-05-23 (N-034 TypeAbstraction and N-035 ForallType added; N-013, N-014, N-016 revised; § Hash construction clarified — `fieldName`, `caseName`, `categoryName`, and `streamKind` re-marked as structural identifiers rather than metadata, and the metadata-exclusion rule restated to depend on edge role rather than target category; N-036 CapabilityScope added — formalizes the capability-narrowing node described in `effects-and-capabilities.md` § Capability mechanism; N-020 ForeignNode entry extended with the explicit `target` structural content field that ADR-005 describes but the original inventory left implicit, and the `binding` Provenance edge marked optional; N-018 VarRef and § Variables and binding revised to recognize Pattern.VariablePattern as a binding occurrence; N-037 ProductValue, N-038 ProductFieldValue, N-039 ProductFieldGet, and N-040 SumValue added — closes the value-construction gap for product and sum types declared by N-008/N-009/N-010/N-011. The N-025 Pattern category gains a constructor variant for matching against sum cases, with arbitrary nested patterns over the payload; N-041 RecursiveType and N-042 RecursiveSelf added — closes the recursive-type gap via μ-binder positional encoding. § Well-formedness "Termination of typing" rule updated to name RecursiveType as the proper mechanism for recursive types; N-043 Handler added — formalizes the no-continuation effect handler described in `effects-and-capabilities.md` § Effect handlers, with the closure-subtraction rule that makes it the only node category that removes effects)

## Summary

This document specifies the inventory of node types that constitute a Strand graph, the edge schemas that connect them, and the well-formedness rules that distinguish admissible graphs from inadmissible ones. The decisions established in [ADR-001](../decisions/ADR-001-graph-not-text.md) (graph-native representation), [ADR-003](../decisions/ADR-003-content-addressing.md) (content addressing), and [ADR-004](../decisions/ADR-004-effects-as-edges.md) (effects as edges) provide the context; this document gives the technical detail. The node algebra is the foundation on which every other Wave 3 specification builds.

The design adopts roughly 30 node types organized into eight categories: literals, types, functions and binding, references, effects and capabilities, control flow, state machines, and metadata. The categorization reflects how graphs are constructed and reasoned about; it does not impose runtime distinctions beyond what each type's semantics requires.

Resolves [Q-001](../open-questions.md#Q-001) (node inventory), [Q-019](../open-questions.md#Q-019) (iterative computation), and [Q-024](../open-questions.md#Q-024) (versioning) as proposed designs. Identifiers N-001 through N-031 are assigned below, alongside N-034 (TypeAbstraction) and N-035 (ForallType) which extend the function-and-binding and type groups for explicit type abstraction in the System F style. Schema and Invariant (N-032, N-033) are specified separately in [`rendering-and-views.md`](rendering-and-views.md).

## Foundations {#foundations}

A Strand graph is a finite directed labeled graph in which every node has a typed category drawn from the inventory below. Edges are typed: each edge connects two nodes and carries a label drawn from a closed set of edge categories specified per node type. Multiplicity constraints (zero-or-one, exactly-one, zero-or-more, one-or-more) are part of each edge's specification.

A graph is *well-formed* when (a) every node belongs to a declared category, (b) every edge belongs to a declared category for the source node's type, (c) all multiplicity constraints are satisfied, (d) the target of each edge has the expected category, and (e) the type and effect closures terminate without contradictions. Well-formedness is decidable in time linear in graph size; the verifier rejects any operation that would produce a graph violating these constraints.

The fundamental graph operation is *node creation*: given a node category and edges to existing nodes, the runtime computes the new node's canonical encoding, derives its content hash, and admits the node to the graph if well-formedness holds. Graphs grow by accretion; nodes are immutable once admitted. Deletion is by garbage collection, governed by reachability from named roots ([ADR-003](../decisions/ADR-003-content-addressing.md)).

## Hash construction {#hash-construction}

The canonical encoding of a node is a deterministic byte sequence derived from (a) a four-byte category tag identifying the node type, (b) the structured content fields specific to the category, encoded in fixed order, and (c) the hashes of nodes referenced by outgoing edges, in fixed edge-label order. The encoding is independent of insertion order, of any in-memory representation, and of metadata that is explicitly excluded.

Whether an outgoing edge contributes to the canonical encoding is determined by the edge's *role*, not by the target node's category. An edge is *metadata* when its content is attribution, provenance, documentation, or any other information that does not affect program semantics. Metadata edges are excluded from the canonical encoding; attaching, replacing, or removing a metadata edge does not change the hash of the node it annotates. The currently-defined metadata category is Provenance (N-031); the algebra reserves room for Contract (for pre/post-conditions) and Documentation (for free-form notes) to be added under the same rule.

Edges that carry *structural identifiers* — `ProductTypeField.fieldName`, `SumTypeCase.caseName`, `EffectCategory.categoryName`, `EventStream.streamKind`, and any future edge marked `(structural)` in the inventory — are NOT metadata, even when their target category is Name (N-030). Their string content participates in the canonical encoding of the containing node: two product types that differ in any field name are distinct types and hash differently; two effect categories that differ in their category name are distinct effects. The UTF-8 byte content of a structural identifier is emitted into the parent's canonical encoding as a CBOR byte string (RFC 8949 major type 2) at the edge's fixed position; implementations that represent structural identifiers as a separately content-addressed Name node must inline the UTF-8 bytes at the canonical-encoding boundary so all implementations produce byte-identical encodings for the same logical input.

Name (N-030) and Provenance (N-031) are both nodes in the algebra. Provenance is always metadata. Name is role-dependent: structural when it appears at a structural-identifier edge, metadata when it appears as an alternate human-readable label attached to a hash-identified node via a tooling-grade naming edge (the use case for renaming without changing the hash).

For lambda nodes, alpha equivalence is enforced by the canonical encoding: parameter binding sites are encoded by their position within the lambda, and variable references in the body are encoded by the position of their binder rather than by any name or stable identifier of the binder. Two lambdas that differ only in parameter naming hash to the same value.

The hash function is a multi-hash as specified in [ADR-003](../decisions/ADR-003-content-addressing.md), with BLAKE3 as the default. The hash output is a 32-byte digest prefixed with a one-byte function identifier; references between nodes carry the full prefixed digest.

## Node inventory {#node-inventory}

The inventory is given as a table. The "edges" column lists the outgoing edge categories and their multiplicities (`1` exactly-one, `?` zero-or-one, `*` zero-or-more, `+` one-or-more). The "target" column gives the node category expected at the edge's terminus.

### Literals (N-001 through N-006)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-001 | IntLit | (none) | Integer literal; content field is a signed 64-bit value |
| N-002 | FloatLit | (none) | IEEE 754 double; content field is the 64-bit encoding |
| N-003 | StringLit | (none) | UTF-8 string; content field is the byte sequence |
| N-004 | BoolLit | (none) | Boolean; content field is one byte |
| N-005 | UnitLit | (none) | The unit value; no content fields |
| N-006 | BytesLit | (none) | Arbitrary byte sequence; content field is the byte sequence |

Literals have no outgoing edges; their identity is determined entirely by content. Their type is determined by category and is not represented by a separate edge.

### Types (N-007 through N-013)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-007 | PrimitiveType | (none) | Content field is a primitive identifier (Int, Float, String, Bool, Unit, Bytes) |
| N-008 | ProductType | `field`+ → ProductTypeField | Record types with named fields |
| N-009 | ProductTypeField | `fieldType`(1) → Type, `fieldName`(1) → Name (structural) | A field declaration within a product type. The `fieldName` is structural: ProductType canonical encoding sorts fields by `fieldName` and includes the UTF-8 bytes, so two product types that differ in any field name hash differently. |
| N-010 | SumType | `case`+ → SumTypeCase | Variant types with named cases |
| N-011 | SumTypeCase | `caseType`(?) → Type, `caseName`(1) → Name (structural) | A case declaration. The `caseName` is structural: SumType canonical encoding sorts cases by `caseName` and includes the UTF-8 bytes, so two sum types that differ in any case name hash differently. |
| N-012 | FunctionType | `parameter`* → Type, `result`(1) → Type, `effect`* → EffectCategory | Function types with parameter types, return type, and effect set |
| N-013 | TypeParameter | `bound`(?) → Type | A type variable for parametric polymorphism, optionally bounded. A TypeParameter is bound by the enclosing TypeAbstraction (in term position) or ForallType (in type position) that lists it in its `typeParameter` edges; a reference outside any such binder is ill-formed. Node identity is significant: every reference to the same TypeParameter node denotes the same variable. |

A universally quantified type expression is given by ForallType (N-035), specified below alongside its corresponding term-level binder.

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-035 | ForallType | `typeParameter`+ → TypeParameter, `body`(1) → Type | A universally quantified type. The `typeParameter` edges name the bound TypeParameter nodes; `body` is a type expression well-formed under those bindings. A ForallType in type position represents `forall a1, ..., an. T`. |

Recursive type expressions are given by RecursiveType (N-041), which introduces a positional self-binder over its body. References to the recursive type within the body are made through a designated reference node (N-042 RecursiveSelf) that resolves positionally rather than by hash, exactly analogous to how `ForallType`/`TypeParameter` and `Lambda`/`VarRef` handle their respective binders. This is the only mechanism by which a type may refer to itself.

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-041 | RecursiveType | `body`(1) → Type | Introduces a positional self-binder over `body`, denoting the type `μ. body`. The body is a well-formed type expression in a scope extended by one anonymous recursive-self slot at depth 0. Recursion is single-arity (one self per RecursiveType); mutually recursive families are encoded by nested RecursiveType + projection. The body must be *contractive*: every path from the binder to a `RecursiveSelf` reference must traverse at least one type constructor (a ProductTypeField, SumTypeCase payload, FunctionType parameter or result). Non-contractive bodies such as `μ. RecursiveSelf` are rejected by the verifier. |
| N-042 | RecursiveSelf | (none) | A reference to the innermost enclosing RecursiveType binder. Its canonical encoding emits the de Bruijn depth from the innermost enclosing binder (depth 0 = the immediate enclosing RecursiveType). A RecursiveSelf outside any enclosing RecursiveType is ill-formed and rejected as `UnboundRecursiveSelf`. |

Strand adopts equirecursive semantics for RecursiveType: a `RecursiveType(body)` and its one-step unfolding (the body with `RecursiveSelf` replaced by the RecursiveType itself) are the same type. Because the canonical encoder fixes the positional representation, two recursive types with structurally identical bodies hash to the same value — equirecursive equality is decided by hash equality, exactly as alpha-equivalence is decided by the positional encoding of term-level binders. No additional coinductive equality algorithm is required at the language level.

Types are nodes; references to a type are references to a node by hash. Equality of types is equality of their hashes (with normalization in the canonical encoding to handle alpha equivalence of type parameters and recursive-binder positions).

### Functions and binding (N-014 through N-018, plus N-034)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-014 | Lambda | `parameter`* → ParameterDecl, `body`(1) → Expression, `effect`* → EffectCategory | A function definition with parameters, body, and declared effects. Lambda does not introduce type-parameter bindings; any TypeParameter referenced by a parameter type must be bound by an enclosing TypeAbstraction. |
| N-015 | ParameterDecl | `paramType`(1) → Type | A parameter declaration within a lambda |
| N-016 | Application | `function`(1) → Expression (must have FunctionType or ForallType), `argument`* → Expression, `typeArgument`* → Type | A function application. If the function's type is a plain FunctionType, the `typeArgument` list is empty. If the function's type is a ForallType quantified over `n` parameters, the `typeArgument` list has length `n`; the type arguments are substituted positionally into the ForallType's body, which must reduce to a FunctionType against which the value arguments are checked. Partial instantiation, where substitution yields another ForallType, is not admitted by Layer 1. |
| N-017 | Let | `value`(1) → Expression, `body`(1) → Expression | A local binding: evaluate `value`, bind, evaluate `body` |
| N-018 | VarRef | `binder`(1) → ParameterDecl, Let, or Pattern.VariablePattern | A variable reference back to its introducing binder |
| N-034 | TypeAbstraction | `typeParameter`+ → TypeParameter, `body`(1) → Expression | Explicit type abstraction. The `typeParameter` edges name the bound TypeParameter nodes; `body` is any expression well-formed under those bindings. The type of a TypeAbstraction is the ForallType (N-035) over the same `typeParameter` edges with body equal to the body's type. TypeAbstraction is the sole introduction form for polymorphic values. |

A *binding occurrence* is a ParameterDecl (in a lambda), a Let, or a variable-shaped Pattern (the `variable` form of N-025, bound inside a single MatchCase body). A *use occurrence* is a VarRef whose `binder` edge points to a binding occurrence in an enclosing scope (transitively reachable via the body / argument structure). The well-formedness check confirms that every VarRef's binder is in scope at the VarRef's position. WildcardPattern and LiteralPattern bind nothing.

Type-parameter binding is structurally analogous. A TypeAbstraction (in term position) and a ForallType (in type position) each introduce a list of TypeParameter nodes that are bound within the binder's body. A TypeParameter reference is well-formed only when it lies inside a binder that lists that TypeParameter; a reference outside any such binder is rejected. The two binders share their scoping rule but occupy different positions: TypeAbstraction is the introduction form for polymorphic values, while ForallType is the type expression that classifies them and that can appear at any type position (in particular, as a parameter type, which is what makes higher-rank polymorphism expressible).

Bindings are not part of the canonical encoding by identity; they are encoded positionally. The hash of a lambda is invariant under renaming of its parameters and under reordering of `Let` bindings that do not affect dataflow.

### References (N-019, N-020)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-019 | NodeRef | `target`(1) → any (by hash) | Reference to another graph node by hash; allows references that cross module-like boundaries |
| N-020 | ForeignNode | `foreignType`(1) → FunctionType, `effect`* → EffectCategory, `binding`(?) → Provenance (metadata) | A foreign function binding; see [ADR-005](../decisions/ADR-005-foreign-nodes.md). The `target` content field (UTF-8 string) is structural: it identifies the binding the runtime looks up to invoke the foreign code (e.g., `"strand-builtin:Int.Add"`, `"wasm:my-module/exported.fn"`). Two ForeignNodes with the same target, signature, and effects are the same node; two ForeignNodes targeting the same library but signed by different sources differ via their (optional) `binding` Provenance edge. The `binding` edge is metadata-excluded from canonical encoding; the trust model that decides whether a binding is admissible is the subject of [ADR-005](../decisions/ADR-005-foreign-nodes.md) and `design/security-model.md`. |

NodeRef is used when a function would otherwise need to cite another function whose definition is large or distant in the graph; the indirection is also the mechanism for recursive references (a function references its own NodeRef indirectly, breaking the cycle that direct self-reference would create).

### Effects and capabilities (N-021, N-022, N-036, N-043)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-021 | EffectCategory | `categoryName`(1) → Name (structural), `parameter`* → Type | An effect category, possibly parameterized (e.g., `Network.Connect{host: String, port: Int}`). The `categoryName` is structural: an EffectCategory's identity is its name plus its parameter list, so `Network.Connect` and `Filesystem.Read` are distinct categories. |
| N-022 | EffectDecl | `effectType`(1) → EffectCategory, `parameter`* → Expression | An effect declaration with category-instance parameters; attached as an `effect` edge on the node that performs the effect |
| N-036 | CapabilityScope | `capability`* → EffectCategory, `body`(1) → Expression | A graph operation that evaluates its `body` in a narrowed capability context. The narrowed context retains only those capabilities the surrounding context held *and* that appear in this node's `capability` edges; narrowing cannot add capabilities, only remove. This is the security-relevant operation: authority changes are visible in the graph topology. See [effects-and-capabilities.md](effects-and-capabilities.md) § Capability mechanism. |
| N-043 | Handler | `intercept`(1) → EffectCategory, `handle`(1) → Expression (of FunctionType), `body`(1) → Expression | A no-continuation effect handler. Intercepts every Application within `body` whose called function declares `intercept`; the handler function (`handle`) is invoked with the intercepted call's value arguments and its return value replaces the call's result. The handler is the only node category whose closure rule *removes* an effect: `closureOf(handler) = (closureOf(body) - {intercept}) ∪ closureOf(handle) ∪ <handle function's declared effects>`. CapabilityScope (N-036) narrows the runtime context without changing the closure; Handler changes the closure. See [effects-and-capabilities.md](effects-and-capabilities.md) § Effect handlers. |

Effect categories are themselves nodes and are themselves content-addressed. The closed initial set of effect categories is described in [effects-and-capabilities.md](effects-and-capabilities.md); user extensions are possible by introducing new EffectCategory nodes.

### Control flow (N-023 through N-026)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-023 | Match | `scrutinee`(1) → Expression, `case`+ → MatchCase | Pattern match over a value |
| N-024 | MatchCase | `pattern`(1) → Pattern, `body`(1) → Expression | A single case of a match |
| N-025 | Pattern | `patternType`(1) → Type, content fields describing the match shape | A pattern matching values of a given type; sub-patterns are nested via edges (literal patterns, constructor patterns, variable patterns, wildcards) |
| N-026 | Fixpoint | `recursionType`(1) → FunctionType, `body`(1) → Lambda | A fixpoint over a self-referential function; the lambda's body may reference the fixpoint via a designated parameter |

The Fixpoint node is the principal mechanism for recursive computation. A `Fixpoint` over a lambda of type `(A, B) → C` produces a function that may invoke itself through its first parameter, treating it as the recursive call slot. This resolves [Q-019](../open-questions.md#Q-019) by adopting the Fixpoint-node approach. Iteration is then expressible as recursion through Fixpoint; explicit loop constructs are not part of the core algebra.

### Composite values (N-037 through N-040)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-037 | ProductValue | `ofType`(1) → ProductType, `field`+ → ProductFieldValue | A value of a declared product type. The `field` edges must cover every field declared in the `ofType` exactly once: no duplicates, no missing fields, no extras. The verifier checks this. The order of the `field` edges does not affect identity — the canonical encoding sorts fields by `fieldName` so two ProductValues with the same field-name-to-value mapping hash identically (matching ProductType's canonical-field-ordering rule). |
| N-038 | ProductFieldValue | `value`(1) → Expression | Provides the value expression for one field of a containing ProductValue. The `fieldName` content field (UTF-8 string, structural) identifies which field this is; the value's type must match the corresponding ProductTypeField's `fieldType`. |
| N-039 | ProductFieldGet | `target`(1) → Expression (of a ProductType) | Reads one field of a product value by name. The `fieldName` content field (UTF-8 string, structural) names the field. The result type is the corresponding field's declared type. |
| N-040 | SumValue | `ofType`(1) → SumType, `payload`(?) → Expression | A value of a declared sum type. The `caseName` content field (UTF-8 string, structural) identifies which case is being constructed; it must match one of the cases declared by `ofType`. The `payload` edge is present iff the corresponding SumTypeCase declared a `caseType` — and when present, the payload's type must equal that caseType. |

Pattern matching on sum values uses a *constructor* form of N-025 Pattern, distinguishing the cases of the scrutinee's sum type. The constructor pattern carries a structural `caseName` content field and an optional `payloadPattern` edge to a sub-pattern that destructures the payload (if any). The payload sub-pattern is itself a Pattern node — typically a VariablePattern that binds the payload for use in the case body, or another ConstructorPattern for nested sum destructuring. Any VariablePatterns reached via a ConstructorPattern's payload tree are binding occurrences for the enclosing MatchCase's body — `VarRef.binder` may point at any of them.

### State machines (N-027 through N-029)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-027 | StateMachine | `transitionFn`(1) → Lambda (typed `(State, Event) → (State, [Event])`), `initialState`(1) → Expression, `inputStream`+ → EventStream, `outputStream`* → EventStream, `effect`* → EffectCategory | A state machine definition; see [state-machines.md](state-machines.md) |
| N-028 | EventStream | `eventType`(1) → Type, `streamKind`(1) → Name (structural; values: `external`, `internal`, `output`) | An event stream node; sources and sinks are distinguished by `streamKind`. The `streamKind` is structural — it determines the stream's role in the system, so an external and an internal stream over the same event type are distinct nodes that hash differently. |
| N-029 | Transition | `guard`(?) → Expression (Bool), `body`(1) → Expression | A guarded transition within a transition function; used as an alternative to Match for state machines where the case structure is on event type |

### Metadata (N-030, N-031)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-030 | Name | (none) | A UTF-8 identifier; content field is the byte sequence |
| N-031 | Provenance | `signer`(?) → Bytes (signature), `source`(?) → StringLit (URL), `timestamp`(?) → IntLit (Unix time), `keyFingerprint`(?) → BytesLit | Provenance metadata; attached to ForeignNode declarations and to graph roots that need attestation |

### Structured outputs (N-032, N-033)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-032 | Schema | `valueType`(1) → Type, `invariant`* → Invariant, `libraryBinding`(?) → Provenance (metadata), `schemaName`(1) → Name (structural) | A library-supplied refinement of `valueType` whose well-formedness the verifier checks at graph-construction time. A Schema may appear in any type position. `schemaName` is structural — two schemas with the same `valueType` and `invariants` but different names hash differently. The optional `libraryBinding` is metadata-excluded from the canonical encoding, matching the precedent set by ForeignNode's `binding` edge. The Schema's canonical encoding sorts `invariant` edges by hash (set semantics — invariant declaration order does not affect identity). See [rendering-and-views.md](rendering-and-views.md) § Schema mechanism for full semantics. |
| N-033 | Invariant | `body`(1) → Expression of FunctionType `(valueType) → Bool`, `targetSchema`(1) → Schema (metadata for verifier topology check; excluded from canonical encoding), `invariantName`(1) → Name (structural) | A predicate the verifier checks for every value flowing into the parent Schema's type position. The `body` must be monomorphic and effect-free in the first implementation slice (Layer 7 step 1); ForeignNode-backed checkers await the security-model extension for checker bindings. `targetSchema` is excluded from the canonical encoding to break the Schema↔Invariant hash cycle (recoverable from the parent Schema's `invariant` edges, matching the N-009 ProductTypeField / N-011 SumTypeCase precedent). |

### Agent-native capabilities (N-044, N-045)

| ID | Category | Edges | Notes |
|----|----------|-------|-------|
| N-044 | ToolDef | `parameterSchema`(1) → Schema, `implementation`(1) → Expression (a Lambda, ForeignNode, or TypeAbstraction over one), `name`(1) → Name (metadata), `description`(1) → Name (metadata) | A first-class declaration of a tool the LLM-generation builtins (`Anthropic.Messages.Create`, `OpenAI.Chat.Completions`, `Gemini.GenerateContent`) may invoke. `parameterSchema` is the N-032 Schema describing the input value the tool accepts; the verifier requires the schema's `valueType` to project to JSON Schema via `JsonSchemaProjection` (else `ToolParamTypeUnsupported`). `implementation` is the callable the runtime dispatches when the model emits a tool-use block matching this tool; its type must be `parameterSchema.valueType → R` for some result `R`. `name` and `description` are metadata content fields (UTF-8 byte sequences) used only at the provider boundary — they are NOT in the canonical encoding (consistent with ParameterDecl.name treatment). Two ToolDefs that share parameterSchema and implementation but differ only in name or description hash identically. See [`proposals/implemented/agent-native-capabilities.md`](../proposals/implemented/agent-native-capabilities.md) § 3.8 for the tool-use protocol semantics. |
| N-045 | ResponseSchemaSpec | `schema`(1) → Schema | A first-class wrapper that carries a Schema reference into the value position the LLM-generation builtins use for constrained-decoding output. `GenerateRequest.responseSchema` accepts a value of this node category (transported as a `Value.ResponseSchemaSpecV` runtime carrier) rather than a JsonValue tower; the verifier requires the schema's `valueType` to project to JSON Schema via `JsonSchemaProjection` (else `ResponseSchemaTypeUnsupported`) at the wrapper's admission. The wrapper has no metadata content fields — the schema reference is its entire structural identity, and two wrappers around equal Schemas hash identically. See [`proposals/implemented/agent-native-capabilities.md`](../proposals/implemented/agent-native-capabilities.md) § 3.7 for the constrained-decoding contract semantics. |

ToolDef brings tool parameters into Strand's structural-reasoning machinery (Schema + SchemaChecker + JsonSchemaProjection). The verifier walk for `ToolParamTypeUnsupported` fires on every static ToolDef by construction — a graph that lists a ToolDef whose schema's valueType cannot project to JSON Schema is rejected at admission. At the LLM.Generate Application call site, the verifier additionally walks the `tools` argument (a `Cons`/`Nil` list of NodeRef-to-ToolDef references) and re-confirms each ToolDef has been verified; this is mostly cosmetic when the per-ToolDef rule has already fired but carries the call-site NodeId for diagnostics. Tool implementations may be effectful — the call-site capability check includes the union of each tool's implementation effects, so a tool that performs `Fs.Write` requires the surrounding capability context to grant `Filesystem.Write`.

ResponseSchemaSpec performs the same admission-time check for the response-shape side of the LLM tool-use protocol. The two wrapper nodes (N-044 ToolDef and N-045 ResponseSchemaSpec) cover the two Schema-bearing positions on `GenerateRequest`; both reach the Schema through a structural graph edge so the verifier can project at admission rather than at call time, and both produce a runtime carrier value that the provider library consumes directly. The wrapper is intentionally thinner than ToolDef — there is no implementation to dispatch and no metadata name/description to forward — because the semantics is one-shot: the provider library reads the projected JSON Schema from the wrapper and submits it as part of the constrained-decoding request.

Metadata edges are excluded from the canonical encoding of the nodes they annotate (see § Hash construction). Whether a given edge is metadata depends on the edge's role: an edge whose content is attribution, provenance, or documentation is metadata, regardless of the target node's category. An edge whose content is a structural identifier (e.g., `ProductTypeField.fieldName`, `EffectCategory.categoryName`) is *not* metadata even when its target is a Name (N-030) node — the string is part of the parent node's structure and contributes to its hash.

Provenance (N-031) is always metadata. Name (N-030) is metadata when it appears as an alternate human-readable label attached to a hash-identified node via a tooling-grade naming edge — the use case for renaming without changing the hash — and structural when it appears at an edge marked `(structural)` in the inventory above.

Additional metadata categories — Contract (for pre/post-conditions), Documentation (for free-form notes), and others — may be added without changing the algebra; they share the property of being excluded from canonical encoding whenever they appear at metadata edges.

## Edge taxonomy {#edge-taxonomy}

Edge categories used in the inventory above are a closed set, defined per node type. The reference implementation maintains a registry mapping each (NodeCategory, EdgeCategory) pair to its multiplicity constraint and target node category. The registry is part of the algebra and is updated when new node categories are added.

Edges are not themselves nodes — they do not have content hashes. The graph encoding represents edges as pairs of (source node hash, target node hash) labeled with the edge category. Edges are recoverable from the canonical encoding of their source node, so persistent storage need only persist nodes; edges follow.

## Well-formedness rules {#well-formedness}

A graph is well-formed if every node satisfies the following conditions, checked locally and propagated transitively.

**Category and edge integrity.** The node's outgoing edges match exactly the edge categories declared for its node category in the inventory, with multiplicity satisfied and each edge pointing to a node of the expected category.

**Scope.** For every VarRef, its `binder` edge points to a ParameterDecl or Let that is in scope at the VarRef's position. Scope is the standard lexical scope under the body / argument structure of enclosing lambdas, lets, and match cases.

**Type consistency.** For every Application, the type of the `function` expression is a FunctionType, the number of arguments matches the function's parameter count, and each argument's type is a subtype of the corresponding parameter type. For every Match, every case's pattern has a type compatible with the scrutinee's type and the case bodies have a common type. For every Let, the value's type is the type used wherever the binder is referenced.

**Effect coverage.** For every node, the declared effects on that node are a superset of the effects in the closure of the nodes it references. The runtime check at evaluation time confirms that the calling capability context covers the declared effects.

**Termination of typing.** Type-checking each node terminates. Recursive types must go through `RecursiveType` (N-041), whose body's contractivity guarantees that any monotone fold over the type terminates after one unfolding step. Direct cyclic structures (a type whose canonical encoding would require its own hash) are excluded by canonical encoding, which does not terminate on a cycle; the `RecursiveSelf` positional reference is the only mechanism that closes a type-level back-edge.

The verifier maintains type and effect closures as the graph grows. Adding a node forces recomputation only over the new node and its immediate context; the rest of the closure is cached.

## Type system {#type-system}

Strand's type system is structural, parametric, and effect-aware. Types are nodes; type equality is hash equality. The system supports:

- Primitive types (Int, Float, String, Bool, Unit, Bytes).
- Product types (records with named fields and named-field access; field order is normalized in the canonical encoding so that two product types with the same fields in any declaration order hash identically).
- Sum types (variants with named cases).
- Function types with effect annotations on the arrow.
- Type parameters with optional bounds for parametric polymorphism.
- Universal quantification via ForallType (N-035): a type expression of the form `forall a1, ..., an. T`, with the bound parameters scoping over `T`.
- Parametric application: an Application whose function has a ForallType supplies a positional list of type arguments; the verifier substitutes them into the ForallType's body to obtain a FunctionType for value-argument checking. No separate ParametrizedType node is required.

Subtyping is structural: a product with fields `{a: Int, b: Bool}` is a subtype of a product with fields `{a: Int}` (width subtyping); function subtyping is contravariant in arguments and covariant in returns; effect subtyping is on effect-set inclusion (a function with fewer declared effects is a subtype of a function with more declared effects). The full subtyping algorithm is part of the reference implementation; the design adopts the standard structural subtyping rules without modification.

Type inference is not performed by the language; type annotations are mandatory at function boundaries. This is consistent with the agent-generation use case: an agent emits typed nodes, the verifier confirms; the language does not infer types because the agent already knows what types it intends to assign.

## Variables and binding {#variables}

The binding structure deserves explicit discussion because content-addressing imposes constraints that conventional language treatments do not face.

A `ParameterDecl` is a node intrinsic to its containing Lambda. Its identity, for canonical encoding purposes, is its position in the parameter list. Multiple lambdas with the same parameter types and identical bodies (modulo parameter naming) hash to the same value.

A `Let` introduces a binding into its body. The body may reference the Let through `VarRef` nodes whose `binder` edge points to the Let. The Let itself is content-addressed; multiple Lets with the same value expression and binder-name metadata hash to the same node *only if* their containing contexts are also identical (because the Let's hash depends on the value expression and on the body expression, and the body expression depends on its variable references).

A `VarRef` is content-addressed by the path-style address of its binder relative to the enclosing lambda: this is the de Bruijn-like encoding. Two VarRefs at different positions referring to differently-named binders may hash identically if their structural positions match.

This treatment ensures that the alpha-equivalence question is settled at hash computation time: two lambdas equal under alpha conversion are the same lambda by hash; no separate equality check is needed at the language level.

## Iterative computation {#iterative-computation}

[Q-019](../open-questions.md#Q-019) asks how iteration is expressed. The design adopted here is `Fixpoint` (N-026): a lambda referencing itself through a designated parameter slot, with the runtime supplying the recursive call at evaluation time. This admits all primitive recursive computations and, with side-effects, general recursive computation including unbounded loops.

For specific patterns where bounded iteration is preferable (e.g., counted loops with known termination), the design does not provide a primitive loop construct. Instead, the agent generates a Fixpoint application whose termination is determined by a guard in the body. Static analysis can recognize bounded patterns and the compiler can emit loop-like code for them, but at the algebra level the construct is uniform.

This decision favors uniformity and verifiability over surface convenience. An agent generating a counted loop would use either a Fixpoint or a library function over an iteration count.

## Versioning of the algebra {#versioning}

[Q-024](../open-questions.md#Q-024) asks how the algebra itself migrates as Strand evolves. The design adopted here is conservative.

The category tag in the canonical encoding identifies node category by a numeric ID. The current inventory uses N-001 through N-031. New node categories receive higher numbers; existing numbers are not reused or reassigned. A graph that uses only category tags valid in version V can be loaded by any runtime that supports version ≥ V. Adding a new node category does not invalidate older graphs because their hashes remain stable; the older runtime simply does not understand the new category.

When a node category's edge schema changes (e.g., a new optional edge is added), the change is treated as a new category with a different category tag. The original category remains valid for existing graphs; new graphs may use the new category. The category tag space is sized to accommodate this growth without contention.

Effect categories and the algebra of types may extend over time. Strand commits to maintaining backward compatibility at the level of node category tags; semantics-level changes (e.g., refinement of subtyping rules) are version-marked and may require migration of analysis tools.

## References

**Outgoing references:**
- [`ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md) — graph foundation
- [`ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md) — hash construction
- [`ADR-004-effects-as-edges.md`](../decisions/ADR-004-effects-as-edges.md) — effect edges
- [`ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — ForeignNode category
- [`ADR-007-state-machines.md`](../decisions/ADR-007-state-machines.md) — StateMachine, EventStream
- [`effects-and-capabilities.md`](effects-and-capabilities.md) — effect category inventory
- [`state-machines.md`](state-machines.md) — state machine semantics
- [`open-questions.md`](../open-questions.md) — Q-001, Q-019, Q-024 resolved here
- [`proposals/implemented/agent-native-capabilities.md`](../proposals/implemented/agent-native-capabilities.md) — ToolDef (N-044) tool-use protocol, ResponseSchemaSpec (N-045) constrained-decoding wrapper

**Incoming references:**
- [`decisions/ADR-001-graph-not-text.md`](../decisions/ADR-001-graph-not-text.md)
- [`decisions/ADR-003-content-addressing.md`](../decisions/ADR-003-content-addressing.md)
- [`decisions/ADR-008-compilation-target.md`](../decisions/ADR-008-compilation-target.md)
- [`effects-and-capabilities.md`](effects-and-capabilities.md)
- [`state-machines.md`](state-machines.md)
- [`research-plan.md`](../research-plan.md)
- [`rendering-and-views.md`](rendering-and-views.md) — extends node algebra with Schema (N-032) and Invariant (N-033)
- [`decisions/ADR-009-structured-outputs.md`](../decisions/ADR-009-structured-outputs.md) — schema mechanism additions
- [`proposals/implemented/agent-native-capabilities.md`](../proposals/implemented/agent-native-capabilities.md) — extends node algebra with ToolDef (N-044) and ResponseSchemaSpec (N-045)
