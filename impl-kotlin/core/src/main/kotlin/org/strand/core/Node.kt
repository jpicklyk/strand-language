package org.strand.core

/**
 * In-memory representation of a Strand graph node.
 *
 * Layer 1 covers N-001 through N-019 plus N-034 (TypeAbstraction) and N-035
 * (ForallType), which provide explicit type abstraction and quantified type
 * expressions for System F-style polymorphism. Effects (N-021/N-022), foreign
 * nodes (N-020), Match/Pattern (N-023..N-025), Fixpoint (N-026), state
 * machines (N-027..N-029), metadata (N-030/N-031), and the structured outputs
 * nodes (N-032/N-033) are out of scope here and will be added by later
 * layers.
 *
 * [Node] is pure immutable data. Layer 2 content addressing (BLAKE3 over
 * canonical CBOR per ADR-003) is implemented in the `:hashing` module, which
 * computes hashes externally and returns a `Map<NodeId, Hash>` rather than
 * mutating these nodes. Keeping the hash off the node lets the same node
 * participate in multiple hash schemes (e.g., BLAKE3 alongside SHA-256 per
 * ADR-003's multi-hash format) without ADT changes.
 */
sealed class Node {

    // ----- Literals (N-001 through N-006) -----

    /** N-001. Signed 64-bit integer literal. */
    data class IntLit(val value: Long) : Node()

    /** N-002. IEEE 754 double literal. */
    data class FloatLit(val value: Double) : Node()

    /** N-003. UTF-8 string literal. */
    data class StringLit(val value: String) : Node()

    /** N-004. Boolean literal. */
    data class BoolLit(val value: Boolean) : Node()

    /** N-005. The unit value. No content fields. */
    object UnitLit : Node() {
        override fun toString(): String = "UnitLit"
    }

    /** N-006. Arbitrary byte sequence literal. */
    data class BytesLit(val value: ByteArray) : Node() {
        override fun equals(other: Any?): Boolean =
            other is BytesLit && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    // ----- Types (N-007 through N-013) -----

    /** N-007. A primitive type. */
    data class PrimitiveType(val kind: Primitive) : Node()

    /** N-008. A product type whose fields are [fields] (each a ProductTypeField). */
    data class ProductType(val fields: List<NodeId>) : Node()

    /**
     * N-009. A field within a product type. [fieldName] is a *structural*
     * identifier per `design/node-algebra.md` § Hash construction: it
     * participates in the canonical encoding of the containing ProductType
     * (fields are sorted by name and field names contribute to the parent's
     * hash). It is not metadata in the sense of being excluded from hashing.
     */
    data class ProductTypeField(
        val fieldName: String,
        val fieldType: NodeId
    ) : Node()

    /** N-010. A sum type whose cases are [cases] (each a SumTypeCase). */
    data class SumType(val cases: List<NodeId>) : Node()

    /**
     * N-011. A case in a sum type. [caseType] is null for nullary cases.
     * [caseName] is a *structural* identifier per `design/node-algebra.md`
     * § Hash construction: SumType canonical encoding sorts cases by name
     * and includes the case name in each case's hash, so two SumTypes that
     * differ in any case name hash differently.
     */
    data class SumTypeCase(
        val caseName: String,
        val caseType: NodeId?
    ) : Node()

    /**
     * N-012. A function type. [effects] is the list of EffectCategory nodes
     * the function may exercise; an empty list denotes a pure function. Two
     * FunctionTypes are equal only when their parameters, result, AND
     * effect sets are equal.
     */
    data class FunctionType(
        val parameters: List<NodeId>,
        val result: NodeId,
        val effects: List<NodeId> = emptyList()  // each an EffectCategory
    ) : Node()

    /**
     * N-013. A type parameter. The optional [bound] is parsed but currently
     * unused by the Layer 1 verifier (the type system has no subtyping yet).
     * Two TypeParameter nodes with the same id are the same type variable;
     * alpha equivalence at the canonical encoding level is a Layer 2 concern.
     *
     * A TypeParameter is bound by an enclosing [TypeAbstraction] (in term
     * position) or [ForallType] (in type position) that lists it in its
     * `typeParameters` field. References outside such a binder are ill-formed.
     */
    data class TypeParameter(val name: String, val bound: NodeId?) : Node()

    /**
     * N-035. A universally quantified type. Represents the type expression
     * `forall a1, ..., an. body`. The [typeParameters] are bound within
     * [body]; each must be a TypeParameter node. The body must be a
     * well-formed type expression in the extended type-parameter scope.
     */
    data class ForallType(
        val typeParameters: List<NodeId>, // each a TypeParameter
        val body: NodeId
    ) : Node()

    /**
     * N-041. A recursive type expression `μ. body`. Introduces a positional
     * self-binder over [body]; any [RecursiveSelf] node inside the body
     * (at depth 0 in the binder stack) refers back to this RecursiveType.
     * This is the mechanism by which Strand expresses recursive type
     * definitions (linked lists, trees) without self-referential hashes —
     * mirroring how Lambda/VarRef and ForallType/TypeParameter close their
     * respective binder loops via positional encoding.
     *
     * The body must be contractive: every path from the binder to a
     * RecursiveSelf reference must traverse at least one type constructor
     * (a ProductTypeField, SumTypeCase payload, FunctionType parameter or
     * result). The verifier reports `NonContractiveRecursiveType` for
     * bodies like `RecursiveSelf` directly under a `RecursiveType`.
     */
    data class RecursiveType(val body: NodeId) : Node()

    /**
     * N-042. A reference to an enclosing [RecursiveType] binder, identified
     * by [depth] as a de Bruijn index (0 = the immediately-enclosing
     * RecursiveType, 1 = the next one out, ...). The canonical encoding
     * emits this depth directly.
     *
     * Default `depth = 0` preserves the original "innermost only" semantics
     * — every existing program that uses RecursiveSelf without specifying a
     * depth continues to behave identically and produces identical hashes.
     * Programs that nest RecursiveType bodies (e.g., the post-Slice-3
     * `JsonValue` with `JsonArray(List<JsonValue>)`) reference outer binders
     * by setting `depth` to the count of intermediate RecursiveType binders.
     *
     * A RecursiveSelf whose depth exceeds the count of enclosing
     * RecursiveType binders is ill-formed (`UnboundRecursiveSelf`).
     */
    data class RecursiveSelf(val depth: Int = 0) : Node()

    /**
     * N-027. A state machine: a long-running computation modeled as a fixpoint
     * over an event stream. The [transitionFn] is a Lambda of type
     * `(State, Event) -> (State, OutputBatch)`; [initialState] is its starting
     * state; [inputStreams] / [outputStreams] are the EventStreams the machine
     * consumes from / emits to; [effects] is the union of effect categories the
     * runtime expects the machine to need (the transition function's closure
     * plus implicit StateMachine.Send/Receive for the wired streams).
     *
     * Layer 6 step 1 well-formedness rule: `inputStreams.size == 1`. Multi-
     * stream machines are step 2.
     *
     * StateMachine is not an expression — it does not evaluate to a Value at
     * runtime. It is consumed by the runtime via the `runtime/` module's
     * `StateMachineRuntime.runMachine(machine, events)` API.
     */
    data class StateMachine(
        val transitionFn: NodeId,            // a Lambda
        val initialState: NodeId,            // an Expression
        val inputStreams: List<NodeId>,      // each an EventStream
        val outputStreams: List<NodeId>,     // each an EventStream
        val effects: List<NodeId> = emptyList()  // each an EffectCategory
    ) : Node()

    /**
     * N-028. An event stream: declares an event type and a stream kind
     * (external source, internal stream between machines, or output sink).
     * EventStream is purely declarative — no effects on the stream itself; the
     * runtime wiring drives flow.
     *
     * Layer 6 step 3 slice 3.1 adds two optional content fields:
     *  * [bufferSize] — the per-stream channel capacity. Default 1024 (matches
     *    [org.strand.runtime.MachineGroup.bufferCapacity]'s step-2 default).
     *  * [overflowPolicy] — the per-stream policy applied when a producer tries
     *    to send into a full channel. Default [OverflowPolicy.BlockProducer]
     *    (matches step 2's behavior exactly). The other three variants are
     *    [OverflowPolicy.DropNewest], [OverflowPolicy.DropOldest], and
     *    [OverflowPolicy.Sample] — see Q-015's four canonical policies.
     *
     * Layer 6 step 3 slice 3.6 adds [consumerMode] for fan-out:
     *  * [ConsumerMode.Single] (default) — at most one consumer machine. The
     *    runtime allocates a `Channel<Value>` and rejects multi-consumer
     *    topologies for this stream.
     *  * [ConsumerMode.Broadcast] — any number of consumers. The runtime
     *    wraps the stream in a `MutableSharedFlow<Value>`; each consumer
     *    coroutine subscribes and sees every emitted event.
     *
     * All three fields are optional in the canonical encoding: when each
     * equals its default the encoder omits it and the resulting hash matches
     * the pre-step-3 EventStream encoding byte-for-byte (additive versioning
     * per ADR-003). Pre-step-3 corpus programs hash unchanged.
     */
    data class EventStream(
        val eventType: NodeId,               // a Type
        val streamKind: StreamKind,
        val bufferSize: Int? = null,
        val overflowPolicy: OverflowPolicy? = null,
        val consumerMode: ConsumerMode? = null,
    ) : Node()

    /**
     * N-029. A guarded transition rule: when the [guard] (if present)
     * evaluates true, the [body] expression is evaluated. Typically appears
     * inside a transition function as a guarded alternative to Match.
     *
     * Layer 6 step 1 parses Transition but does not exercise it in any corpus
     * program; this is forward schema stability.
     */
    data class Transition(
        val guard: NodeId?,                  // an Expression of Bool; null means unconditional
        val body: NodeId                     // an Expression
    ) : Node()

    /**
     * N-043. A no-continuation effect handler. Intercepts every Application
     * within [body] whose callee declares the [intercept] effect category;
     * when such an interception fires, the [handle] function is invoked with
     * the intercepted call's value arguments and its return value replaces
     * the call's result. The body's evaluation continues from there.
     *
     * The handler is just a function — there is no `resume`, no captured
     * continuation, no abort. The body always runs to completion; each
     * intercepted call is replaced by the handler's return. See
     * `proposals/implemented/effect-handlers.md` for the full algebra.
     *
     * The Handler is the only node category whose effect closure *removes*
     * an effect: `closureOf(handler) = (closureOf(body) - {intercept}) ∪
     * closureOf(handle)`. CapabilityScope narrows the runtime context but
     * does not change the static closure; Handler is what enables a body
     * that depends on an effect category to run in a surrounding context
     * that does not grant it.
     */
    data class Handler(
        val intercept: NodeId,   // an EffectCategory
        val handle: NodeId,      // an Expression of FunctionType
        val body: NodeId         // an Expression
    ) : Node()

    /**
     * N-044. A first-class declaration of a tool the LLM-generation builtins
     * (`Anthropic.Messages.Create`, `OpenAI.Chat.Completions`,
     * `Gemini.GenerateContent`) may invoke at runtime.
     *
     * Edges:
     *  - [parameterSchema] points at an N-032 Schema describing the input
     *    value the tool accepts. The Schema's `valueType` must project to
     *    JSON Schema via `JsonSchemaProjection` (verified statically — else
     *    `ToolParamTypeUnsupported`).
     *  - [implementation] is the Strand callable the runtime dispatches when
     *    the model emits a tool-use block matching this tool. Its type must
     *    be `parameterSchema.valueType -> R` for some result `R`; the
     *    verifier checks this shape.
     *
     * Content fields:
     *  - [name] and [description] are metadata-only UTF-8 strings used at
     *    the provider boundary (Anthropic/OpenAI/Gemini all accept `name`
     *    and `description` on tool definitions). They are NOT included in
     *    the canonical encoding (consistent with ParameterDecl.name
     *    treatment). Two ToolDefs that share parameterSchema and
     *    implementation but differ only in name or description hash
     *    identically.
     *
     * Structural identity: the canonical encoding depends only on the
     * `parameterSchema` and `implementation` edges (per
     * `design/node-algebra.md` § Agent-native capabilities). The metadata
     * exclusion lets agents rename a tool without churning the program's
     * hash.
     *
     * See `proposals/implemented/agent-native-capabilities.md` § 3.8 for
     * the tool-use protocol semantics.
     */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameterSchema: NodeId,    // a Schema (N-032)
        val implementation: NodeId,     // an Expression (Lambda, ForeignNode, or TypeAbstraction over one)
    ) : Node()

    // ----- Functions and binding (N-014 through N-018, plus N-034) -----

    /**
     * N-014. A function definition. [effects] lists the EffectCategory nodes
     * the lambda may exercise; the verifier enforces that the lambda's body
     * closure does not introduce effects outside this list (coverage). An
     * empty list denotes a pure function. A Lambda alone does not bind
     * TypeParameters; to express a polymorphic function, wrap the Lambda in
     * a [TypeAbstraction] whose `typeParameters` cover every TypeParameter
     * referenced by the Lambda's parameter types.
     */
    data class Lambda(
        val parameters: List<NodeId>, // each a ParameterDecl
        val body: NodeId,
        val effects: List<NodeId> = emptyList()  // each an EffectCategory
    ) : Node()

    /**
     * N-034. Explicit type abstraction. Binds the [typeParameters] (each a
     * TypeParameter node) over [body], yielding a polymorphic term whose
     * type is a [ForallType] quantified over the same parameters. Required
     * to make a Lambda polymorphic: a Lambda whose parameter types reference
     * a TypeParameter must be enclosed by a TypeAbstraction that lists that
     * TypeParameter.
     */
    data class TypeAbstraction(
        val typeParameters: List<NodeId>, // each a TypeParameter
        val body: NodeId
    ) : Node()

    /** N-015. A parameter declaration intrinsic to a containing Lambda. */
    data class ParameterDecl(
        val name: String,
        val paramType: NodeId
    ) : Node()

    /**
     * N-016. A function application.
     *
     * [typeArguments] carries explicit type-node ids that instantiate the
     * function's TypeParameter nodes in declaration order. Empty for
     * monomorphic calls. Type inference is not performed; every polymorphic
     * call site supplies its instantiation explicitly.
     *
     * [effectInstances] carries one [EffectDecl] per [EffectCategory] declared
     * by the callee's `FunctionType.effects`. Each EffectDecl's parameter
     * expressions supply the concrete refinement values used at the call site
     * (the "host" and "port" arguments to a `connect` builtin become the
     * EffectDecl's parameters, typically as VarRefs into the application's
     * arguments). Empty when the callee declares no effects. The verifier
     * enforces 1:1 coverage between EffectCategories declared on the function
     * and EffectDecls supplied here; the interpreter evaluates these
     * expressions before dispatch and consults the runtime CapabilitySet for
     * refinement-lattice matching.
     */
    data class Application(
        val function: NodeId,
        val arguments: List<NodeId>,
        val typeArguments: List<NodeId> = emptyList(),
        val effectInstances: List<NodeId> = emptyList()  // each an EffectDecl
    ) : Node()

    /** N-017. A local binding. */
    data class Let(
        val name: String,
        val value: NodeId,
        val body: NodeId
    ) : Node()

    /**
     * N-018. A variable reference. [binder] points to either a ParameterDecl
     * or a Let in scope.
     */
    data class VarRef(val binder: NodeId) : Node()

    // ----- References (N-019, N-020) -----

    /**
     * N-019. A reference to another node by content hash (Layer 2 step 2).
     *
     * [target] is the BLAKE3 multi-hash of the referenced node's canonical
     * encoding under an empty binder stack. Two NodeRefs to the same target
     * (in the content-addressed sense) carry byte-identical hashes and are
     * structurally equal. The verifier enforces that the target subgraph is
     * closed — it contains no [VarRef] or bound [TypeParameter] reaching to
     * binders outside the target — so the target's hash is context-
     * independent and reachable in any [NodeRef] position.
     *
     * During JSON ingest, NodeRef slots are populated as
     * [StoredNode.RawNodeRef] carrying the in-document target [NodeId]; the
     * hashing module's `finalize` step resolves those NodeIds to hashes and
     * admits the canonical [NodeRef] to the [NodeStore].
     */
    data class NodeRef(val target: Hash) : Node()

    /**
     * N-020. A foreign function binding. The [target] identifier names a
     * binding the runtime looks up to invoke foreign code (for example,
     * `"strand-builtin:Int.Add"` or `"wasm:my-module/exported.fn"`). The
     * [foreignType] is the function signature in Strand types. The [effects]
     * are the EffectCategory nodes the foreign code performs at runtime; the
     * verifier treats these as authoritative per ADR-005.
     *
     * Layer 4 step 1 supports only a small in-process registry of trusted
     * builtins; sandboxed bindings (WebAssembly, seccomp processes, TEE)
     * are deferred to Milestone 2.4.
     */
    data class ForeignNode(
        val target: String,
        val foreignType: NodeId,                  // a FunctionType
        val effects: List<NodeId> = emptyList()   // each an EffectCategory
    ) : Node()

    // ----- Control flow (N-023..N-026) -----

    /**
     * N-023. Pattern match over a value. Cases are tried in declaration
     * order; the first whose pattern matches is taken. Layer 5 step 1 does
     * not enforce exhaustiveness — a Match with no matching case raises
     * a runtime error.
     */
    data class Match(
        val scrutinee: NodeId,         // an Expression
        val cases: List<NodeId>        // each a MatchCase, in declaration order
    ) : Node()

    /** N-024. One arm of a Match. */
    data class MatchCase(
        val pattern: NodeId,           // a Pattern
        val body: NodeId               // an Expression evaluated when the pattern matches
    ) : Node()

    /**
     * N-025. A pattern. Variants:
     *  - [LiteralPattern] matches if the scrutinee's value equals the
     *    referenced literal node's value.
     *  - [VariablePattern] always matches and binds the scrutinee to its
     *    own NodeId for use within the case body (VarRef.binder points at
     *    the VariablePattern's NodeId).
     *  - [WildcardPattern] always matches and binds nothing.
     *
     * Constructor patterns (sum-case and product-field destructuring) are
     * deferred — they require value-constructor nodes that have not yet
     * been specified.
     *
     * Every pattern declares its [patternType]; the verifier checks that
     * each case's pattern type matches the enclosing Match's scrutinee
     * type.
     */
    sealed class Pattern : Node() {
        abstract val patternType: NodeId  // a Type

        /** A literal pattern: matches the [literal] node's value by equality. */
        data class LiteralPattern(
            override val patternType: NodeId,
            val literal: NodeId  // an IntLit/FloatLit/StringLit/BoolLit/UnitLit/BytesLit
        ) : Pattern()

        /**
         * A variable pattern: always matches and binds the scrutinee to its
         * own NodeId. VarRef.binder may point at this node to refer to the
         * matched value inside the case body. [name] is metadata.
         */
        data class VariablePattern(
            override val patternType: NodeId,
            val name: String
        ) : Pattern()

        /** A wildcard pattern: always matches; binds nothing. */
        data class WildcardPattern(
            override val patternType: NodeId
        ) : Pattern()

        /**
         * A constructor pattern: matches a SumValue whose case equals
         * [caseName] and (if [payloadPattern] is present) whose payload
         * matches that sub-pattern. The [patternType] must be a SumType
         * declaring a case named [caseName]. VariablePatterns reached via
         * [payloadPattern] (transitively) introduce bindings into the
         * enclosing MatchCase's body scope.
         */
        data class ConstructorPattern(
            override val patternType: NodeId,   // a SumType
            val caseName: String,
            val payloadPattern: NodeId?         // a Pattern; null only when the case has no payload
        ) : Pattern()
    }

    /**
     * N-026. A fixed-point over a self-referential function. The
     * [recursionType] declares the FunctionType the Fixpoint's value has at
     * call sites — what callers see. The [body] is a Lambda whose first
     * parameter is the recursive call slot: its paramType must equal
     * [recursionType], and the rest of its parameters and result must
     * match the parameters and result of [recursionType]. At runtime, when
     * the Fixpoint's value is applied, the runtime supplies the same value
     * as the first argument so that calls through that parameter recurse.
     *
     * This is the mechanism by which Strand expresses recursion without
     * self-referential hashes — see ADR-003 § Decision. The body Lambda's
     * canonical encoding has no special structure; the recursion happens
     * at evaluation time, not in the graph topology.
     */
    data class Fixpoint(
        val recursionType: NodeId,  // a FunctionType
        val body: NodeId            // a Lambda
    ) : Node()

    // ----- Composite values (N-037..N-039) -----

    /**
     * N-037. A value of a declared ProductType. The [fields] list must
     * cover every field declared in [ofType] exactly once. Field
     * declaration order in this list does not affect identity — the
     * canonical encoder sorts by `fieldName` so two ProductValues with the
     * same field-name-to-value mapping hash identically.
     */
    data class ProductValue(
        val ofType: NodeId,        // a ProductType
        val fields: List<NodeId>   // each a ProductFieldValue
    ) : Node()

    /**
     * N-038. One field of a [ProductValue]: the [fieldName] (structural,
     * per design/node-algebra.md § Hash construction) plus a [value]
     * expression. The value's type must match the corresponding
     * ProductTypeField's declared type.
     */
    data class ProductFieldValue(
        val fieldName: String,
        val value: NodeId          // an Expression
    ) : Node()

    /**
     * N-039. Reads one field of a product value by name. [target] must
     * evaluate to a [ProductValue] (statically: its type is a ProductType
     * declaring a field named [fieldName]). The result's type is that
     * field's declared type.
     */
    data class ProductFieldGet(
        val target: NodeId,        // an Expression of a ProductType
        val fieldName: String
    ) : Node()

    /**
     * N-040. A value of a declared SumType, tagged by [caseName]. The
     * [caseName] is a structural identifier (per design/node-algebra.md
     * § Hash construction): two SumValues differing in case name are
     * distinct values, even of the same SumType. The [payload] edge is
     * present iff the corresponding SumTypeCase declared a non-null
     * caseType; the verifier enforces this.
     */
    data class SumValue(
        val ofType: NodeId,        // a SumType
        val caseName: String,
        val payload: NodeId?       // an Expression matching the case's caseType; null for nullary cases
    ) : Node()

    // ----- Effects and capabilities (N-021, N-022, N-036) -----

    /**
     * N-021. An effect category. [categoryName] is the structural identifier
     * (e.g. "Network.Connect", "Time.Now"); [parameters] is the list of
     * parameter-type NodeIds that further refine the category (e.g.,
     * Network.Connect{host: String, port: Int}).
     *
     * Per design/node-algebra.md § Hash construction, [categoryName] is a
     * structural identifier (included in canonical encoding), not metadata.
     * EffectCategory nodes are themselves content-addressed: two EffectCategory
     * nodes with the same name and parameter types are the same effect.
     */
    data class EffectCategory(
        val categoryName: String,
        val parameters: List<NodeId> = emptyList()  // each a Type
    ) : Node()

    /**
     * N-022. An effect declaration: an instance of an EffectCategory with
     * concrete parameter expressions. Layer 3 step 1 admits EffectDecl in
     * the schema for forward compatibility but does not yet use the
     * parameter values for capability matching (matching is by
     * EffectCategory identity only; refinement-lattice matching is deferred).
     */
    data class EffectDecl(
        val effectType: NodeId,                    // an EffectCategory
        val parameters: List<NodeId> = emptyList() // each an Expression
    ) : Node()

    /**
     * N-036. A capability-narrowing scope. The [body] expression is evaluated
     * in a capability context that retains only the capabilities the
     * surrounding context held *and* that appear in [capabilities].
     * Narrowing cannot add capabilities, only remove. This is the language's
     * primary mechanism for designating that a sub-computation should run
     * with less authority than the surrounding code.
     */
    data class CapabilityScope(
        val capabilities: List<NodeId>,  // each an EffectCategory — the narrowed-to set
        val body: NodeId                 // expression to evaluate in the narrowed context
    ) : Node()

    // ----- Structured outputs (N-032, N-033) -----

    /**
     * N-032. A schema: a library-supplied refinement of a structural type
     * whose well-formedness the verifier checks at graph-construction time.
     * A Schema appears in any type position; the verifier resolves it to a
     * `TypeExpr.SchemaType(schemaId, valueType, invariants)` and uses the
     * `invariants` list to evaluate well-formedness when a value flows into
     * a schema-typed position.
     *
     * The [schemaName] is a library-scoped structural identifier — included
     * in the canonical encoding, so two schemas with different names hash
     * differently even with the same `valueType` and `invariants`. The
     * [valueType] is the underlying structural type values must inhabit.
     * The [invariants] are the Invariant nodes the verifier evaluates at
     * Schema-into-type-position resolutions.
     *
     * `libraryBinding` (a Provenance edge per `design/rendering-and-views.md`)
     * is metadata-excluded from the canonical encoding, matching the
     * precedent set by ForeignNode's `binding` Provenance edge. The
     * reference implementation does not yet carry `libraryBinding` in the
     * in-memory ADT; the field is reserved for the security-model work
     * that lands alongside ForeignNode-backed checkers.
     */
    data class Schema(
        val schemaName: String,
        val valueType: NodeId,            // a Type
        val invariants: List<NodeId>      // each an Invariant
    ) : Node()

    /**
     * N-033. An invariant: a predicate the verifier checks for every value
     * flowing into a schema-typed position. The [body] is a Strand
     * expression of function type `(valueType) -> Bool` — in practice a
     * Lambda or a VarRef to a Lambda bound at a Let. Layer 7 step 1 ships
     * pure-expression invariants only; ForeignNode-typed bodies are
     * rejected as `SchemaInvariantBodyMustBePure` until the security-model
     * work for checker bindings lands.
     *
     * The [invariantName] is a library-scoped structural identifier
     * (included in the canonical encoding). [targetSchema] is the Schema
     * this invariant is associated with — defensively checked by the
     * verifier to ensure topological consistency with the Schema's own
     * `invariants` list.
     */
    data class Invariant(
        val invariantName: String,
        val targetSchema: NodeId,         // a Schema
        val body: NodeId                  // an Expression of FunctionType (valueType) -> Bool
    ) : Node()
}

/** The closed set of primitive type kinds. */
enum class Primitive {
    Int, Float, String, Bool, Unit, Bytes
}

/**
 * The stream-kind discriminator carried by an [Node.EventStream] node.
 *
 *  * [External] — the stream originates outside the Strand graph (network
 *    input, timer ticks, hardware interrupts, foreign producer).
 *  * [Internal] — the stream connects one state machine's output to another
 *    machine's input within the same graph.
 *  * [Output] — the stream is a sink that emits events out of the graph
 *    (network output, log writes, foreign consumer).
 *
 * The kind is part of the EventStream's identity: two EventStreams with the
 * same eventType but different kinds are distinct nodes. The canonical
 * encoder emits the kind as a small integer discriminator (0=External,
 * 1=Internal, 2=Output) — these ordinal positions are stable forever.
 */
enum class StreamKind {
    External, Internal, Output
}

/**
 * The four canonical backpressure policies an [Node.EventStream] may carry
 * (Layer 6 step 3 slice 3.1, Q-015). Selects the runtime behavior when a
 * producer attempts to send into a full channel.
 *
 *  * [BlockProducer] — suspend the producer until capacity is available
 *    (matches step 2's behavior). The default when [Node.EventStream.overflowPolicy]
 *    is null.
 *  * [DropNewest] — discard the incoming event; the channel's state is
 *    unchanged; the producer returns immediately. A drop counter ticks.
 *  * [DropOldest] — drain one event from the head of the channel, then enqueue
 *    the new one. Both an enqueue and a drop are recorded.
 *  * [Sample] — drop events that arrive less than [Sample.intervalNanos]
 *    nanoseconds after the most recently accepted event. Coarse rate-limiting.
 *
 * The canonical encoding tags these as small ints (0=Block, 1=DropNew,
 * 2=DropOld, 3=Sample); [Sample] additionally carries its `intervalNanos`
 * parameter. These ordinal positions are stable forever.
 */
sealed class OverflowPolicy {
    object BlockProducer : OverflowPolicy() {
        override fun toString(): String = "BlockProducer"
    }
    object DropNewest : OverflowPolicy() {
        override fun toString(): String = "DropNewest"
    }
    object DropOldest : OverflowPolicy() {
        override fun toString(): String = "DropOldest"
    }
    /**
     * Coarse rate-limiting: drop incoming events that arrive sooner than
     * [intervalNanos] nanoseconds after the previously accepted event. The
     * clock source is wall-clock (`System.nanoTime`) — Sample streams break
     * deterministic replay and should not appear on code paths the host
     * wants to replay (see OQ-S3-c in the step-3 proposal).
     */
    data class Sample(val intervalNanos: Long) : OverflowPolicy() {
        init { require(intervalNanos > 0) { "Sample.intervalNanos must be > 0, got $intervalNanos" } }
    }
}

/**
 * Fan-out mode for an [Node.EventStream] (Layer 6 step 3 slice 3.6).
 *
 *  * [Single] — at most one consumer machine may list the stream in its
 *    `inputStreams`. The runtime allocates a `Channel<Value>` and a
 *    `MachineGroupValidationError.InternalStreamMultipleConsumers` is raised
 *    if the topology violates this constraint. This is the step-2 default
 *    and remains the default when [Node.EventStream.consumerMode] is null.
 *  * [Broadcast] — any number of consumers may subscribe. The runtime wraps
 *    the stream in a `kotlinx.coroutines.flow.MutableSharedFlow<Value>` (the
 *    modern replacement for the deprecated `BroadcastChannel`). Each
 *    consumer machine's actor launches a collection coroutine bridging the
 *    SharedFlow into a per-consumer Channel<Value>, so the actor's existing
 *    `select`-based input loop is preserved unchanged.
 *
 * The canonical encoding tags these as small ints (0=Single, 1=Broadcast).
 * These ordinal positions are stable forever.
 */
enum class ConsumerMode {
    Single, Broadcast
}
