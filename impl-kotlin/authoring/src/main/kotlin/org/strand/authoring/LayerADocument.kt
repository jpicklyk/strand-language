package org.strand.authoring

/**
 * In-memory representation of a parsed Layer A document.
 *
 * Layer A is the LLM-facing compact text projection of Strand's dag-json
 * flat-form authoring format (Q-034 step 1). One node per line; each line
 * is a `<author-id> <code> <arg>...` tuple where `<code>` is a fixed
 * three-character mnemonic for the node category and the remaining
 * positional arguments encode the node's fields per the code's schema
 * (see [LayerAGrammar]).
 *
 * The first slice (this implementation) covers Layer 1 node categories
 * only: literals, types, lambda, application, let, varref, NodeRef,
 * TypeAbstraction, ForallType. Effects, foreign nodes, control flow,
 * state machines, schemas, and recursive types are deferred to follow-up
 * slices that extend the grammar.
 *
 * Round-trip property: for every Layer A document with no syntax errors,
 * `JsonIngest.parse(DagJsonEmitter.emit(parse(text)))` produces a
 * RawNodeStore byte-identical to the canonical dag-json's RawNodeStore.
 * The authoring layer is purely a syntax-level projection — no type
 * checking, no inference, no elaboration. Those land in step 2 (Layer C).
 */
data class LayerADocument(
    val version: Int,
    val rootId: String,
    val nodes: List<NodeDecl>,
)

/**
 * One node declaration in a Layer A document. Carries the author id, the
 * fixed three-character code (e.g., "ILT" for IntLit, "APP" for
 * Application), and the positional argument list per the code's schema.
 *
 * The validity of [args] given [code] is checked by [DagJsonEmitter] —
 * the parser is generic and accepts any token sequence; per-code shape
 * errors surface at emit time with structured [AuthoringError] reports.
 */
data class NodeDecl(
    val id: String,
    val code: String,
    val args: List<Arg>,
    /** Source line number (1-based) for error reporting. */
    val line: Int,
)

/**
 * A single Layer A argument. The parser distinguishes only by lexical
 * form; semantic interpretation (is this an author-id reference, a
 * primitive-kind keyword, or a payload value?) is per-code at emit time.
 */
sealed class Arg {
    /**
     * A bare identifier-looking token: an author-id reference, a
     * keyword like `Int` or `external`, or the literal `_` (which is
     * lifted to [Null]). Examples: `T_a`, `Int`, `intT`, `external`.
     */
    data class Bare(val text: String) : Arg()

    /** A double-quoted string with standard escapes (`\"`, `\\`, `\n`, `\t`). */
    data class Str(val value: String) : Arg()

    /** A signed decimal integer literal. Examples: `42`, `-3`, `0`. */
    data class IntL(val value: Long) : Arg()

    /** A floating-point literal recognized by the dot (`3.14`, `-0.5`). */
    data class FloatL(val value: Double) : Arg()

    /** A boolean literal — `true` or `false`. */
    data class BoolL(val value: Boolean) : Arg()

    /**
     * A bracketed list `[a b c]` of arguments. Empty list is `[]`.
     * Lists do not nest in the first slice — every node category whose
     * fields take a list takes a flat list of bare refs.
     */
    data class Listing(val items: List<Arg>) : Arg()

    /** The null/absent marker `_`. Used for nullable fields (e.g., SumTypeCase.caseType = `_`). */
    object Null : Arg()

    /**
     * Slice 10 (Layer A density v4) — nested expression at a reference
     * position. Written as `(CODE args...)` inside a `[...]` list or in
     * a singleton REFERENCE / NULLABLE_REF slot. The emitter synthesizes
     * a fresh `__expr<n>` child node from the inner code + args and points
     * the parent reference at it. Composes with Slices 1-3 (reserved
     * names, inline literals, auto-VarRef) — for example `(APP sub [n 1])`
     * uses Slice 3 auto-VarRef on `n` and Slice 2 inline-literal on `1`.
     *
     * Only codes that produce values are legal here. Type-position codes
     * (PRM, FNT, PRD, PRF, SUM, SCS, FAL, TPM, RT, RS) and structural-only
     * codes (PRC, PLT/PVR/PWC/PCN patterns, EFC, EFD, ESE/ESI/ESO, TR, SCH,
     * INV, MC) are rejected at emit time with an
     * [AuthoringError.ArgShapeMismatch].
     */
    data class Nested(val code: String, val args: List<Arg>) : Arg()
}
