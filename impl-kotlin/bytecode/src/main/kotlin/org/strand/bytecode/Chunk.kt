package org.strand.bytecode

/**
 * One self-contained unit of bytecode (Q-017 step 1 § 4.1).
 *
 * A chunk corresponds to either the top-level program entry or one
 * Lambda / Fixpoint body. The lowering pass emits sub-chunks recursively;
 * the main chunk references them by index into the surrounding
 * [ChunkTable].
 *
 * Fields:
 *  * [name] — a debug-only label (the source NodeId in toString form).
 *  * [code] — the bytecode bytes. Opcodes are single bytes; operand-
 *    bearing opcodes (per [Opcode]) follow with a 4-byte little-endian
 *    int operand (or two such operands for `CALL_FIXPOINT` / `MAKE_CLOSURE`
 *    and similar 2-operand opcodes — see [Opcode] docs).
 *  * [constants] — per-chunk pool of literal values, hash references,
 *    and effect-category NodeIds. Operands index into this list.
 *  * [locals] — the number of local-variable slots the frame needs.
 *
 * Slice 1's chunks carry only Layer-1 opcodes; the constants pool holds
 * Long / Double / String / Boolean / Unit / ByteArray values plus
 * sub-chunk indices (for `MAKE_CLOSURE`).
 */
data class Chunk(
    val name: String,
    val code: ByteArray,
    val constants: List<Constant>,
    val locals: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return name == other.name &&
            code.contentEquals(other.code) &&
            constants == other.constants &&
            locals == other.locals
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + code.contentHashCode()
        result = 31 * result + constants.hashCode()
        result = 31 * result + locals
        return result
    }
}

/**
 * Per-chunk constant-pool entries. Sealed so the VM dispatches by variant
 * and produces a typed runtime value at each [Opcode.PUSH_*] site.
 *
 * `IntC` / `FloatC` / `StringC` / `BoolC` / `UnitC` / `BytesC` mirror the
 * Strand literal categories. `ChunkRefC` is used by `MAKE_CLOSURE` /
 * `MAKE_FIXPOINT` operands — the operand index identifies a sub-chunk
 * in the surrounding [ChunkTable].
 */
sealed class Constant {
    data class IntC(val value: Long) : Constant()
    data class FloatC(val value: Double) : Constant()
    data class StringC(val value: String) : Constant()
    data class BoolC(val value: Boolean) : Constant()
    object UnitC : Constant() {
        override fun toString() = "UnitC"
    }
    data class BytesC(val value: ByteArray) : Constant() {
        override fun equals(other: Any?): Boolean =
            other is BytesC && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    /**
     * Reference to another chunk in the surrounding [ChunkTable] (used by
     * `MAKE_CLOSURE` / `MAKE_FIXPOINT` to capture the chunk index of a
     * sub-Lambda or sub-Fixpoint body).
     */
    data class ChunkRefC(val chunkIndex: Int) : Constant()

    /**
     * Reference to a string ForeignNode target (used by `MAKE_FOREIGN`).
     * Holds the target string directly so the VM can dispatch via the
     * existing `Builtins.lookup`.
     */
    data class ForeignTargetC(val target: String) : Constant()

    /**
     * Ordered list of field names for a [Node.ProductValue] (Layer 5
     * step 3a). The `PRODUCT_NEW` opcode pops N values matching this
     * count and assembles a [Value.ProductV] using these names as the
     * keys. Names are recorded once in the constant pool per distinct
     * ProductType shape; dedupe is positional (different orderings get
     * separate entries).
     */
    data class ProductFieldsC(val names: List<String>) : Constant()

    /**
     * Sum-case descriptor for [Node.SumValue] (Layer 5 step 3b). The
     * `SUM_NEW` opcode reads this to know the case name and whether
     * to pop a payload value. Nullary cases (`hasPayload = false`)
     * skip the payload pop entirely.
     */
    data class SumCaseC(val caseName: String, val hasPayload: Boolean) : Constant()

    /**
     * List of EffectCategory NodeId .values for a callable's declared
     * effects (Layer 3). Used by MAKE_CLOSURE / MAKE_FIXPOINT / MAKE_FOREIGN
     * to record the callee's effects so the VM's CALL site can:
     *   1. Check active handlers for an intercept whose category is in this set.
     *   2. Check the current capability context covers this set.
     */
    data class EffectsC(val effectIds: IntArray) : Constant() {
        override fun equals(other: Any?): Boolean =
            other is EffectsC && effectIds.contentEquals(other.effectIds)
        override fun hashCode(): Int = effectIds.contentHashCode()
    }

    /**
     * Review H2: one per lowered Application, consumed by `CALL`. [site] is
     * the Application's NodeId value (so a VM denial names the same graph
     * site the interpreter's does). [categories] / [paramCounts] describe
     * the Application's `effectInstances` in declaration order: for each
     * EffectDecl, its EffectCategory id and the number of parameter values
     * the lowered code pushed after the call's arguments. The VM pops those
     * values into the same `category → evaluated parameters` map the
     * interpreter's `evalEffectInstances` builds and runs the refinement
     * check against it.
     */
    data class CallSiteC(
        val site: Int,
        val categories: IntArray,
        val paramCounts: IntArray,
    ) : Constant() {
        val totalParams: Int get() = paramCounts.sum()
        override fun equals(other: Any?): Boolean =
            other is CallSiteC && site == other.site &&
                categories.contentEquals(other.categories) && paramCounts.contentEquals(other.paramCounts)
        override fun hashCode(): Int =
            31 * (31 * site + categories.contentHashCode()) + paramCounts.contentHashCode()
    }

    /**
     * Review H2 / Q-039: a ForeignNode's `effectProjections`, carried by
     * `MAKE_FOREIGN` so the VM synthesizes the capability-check parameters
     * from the evaluated call arguments exactly as the interpreter's
     * `synthesizeProjectedInstances` does. Empty for unprojected bindings.
     */
    data class ProjectionsC(val projections: List<ProjectionC>) : Constant()

    /** One Q-039 projection: the category and its per-parameter sources. */
    data class ProjectionC(val category: Int, val sources: List<ProjectionSourceC>)

    /** A projection parameter source: a call argument position or a literal. */
    sealed class ProjectionSourceC {
        data class ArgRef(val index: Int) : ProjectionSourceC()

        /**
         * A Q-039 LiteralNode source. [check] is the literal node's runtime
         * schema obligations (Q-047), present only when the Lowerer was given
         * obligations and the literal node carries some: the interpreter
         * evaluates the literal node at each projected dispatch, so its
         * obligations fire there, and the VM runs [check] at the same point.
         */
        data class Literal(val value: Constant, val check: SchemaCheckC? = null) : ProjectionSourceC()
    }

    /**
     * Q-047: the runtime schema obligations of one expression node, consumed
     * by `CHECK_SCHEMA` (and by a projection [ProjectionSourceC.Literal]).
     * [site] is the node's NodeId value, the `at` of a violation. [checks]
     * lists one entry per invariant: the verifier's recorded obligation order,
     * then each schema's invariant declaration order, which is the order the
     * interpreter's `checkSchemaObligations` evaluates them in.
     */
    data class SchemaCheckC(val site: Int, val checks: List<InvariantCheckC>) : Constant()

    /**
     * One invariant to evaluate against a value: the [schema] and [invariant]
     * NodeId values a violation reports, and the sub-chunk that evaluates the
     * invariant's body expression to its predicate callable ([chunkIndex]).
     */
    data class InvariantCheckC(val schema: Int, val invariant: Int, val chunkIndex: Int)

    /**
     * N-044: the static fields of a ToolDef node, consumed by `MAKE_TOOLDEF`,
     * which pairs them with the evaluated implementation to build the
     * interpreter's `Value.ToolDefV`. [self] and [parameterSchema] are NodeId
     * values.
     */
    data class ToolDefC(
        val self: Int,
        val name: String,
        val description: String,
        val parameterSchema: Int,
    ) : Constant()
}

/**
 * The set of chunks produced by lowering one Strand program (Q-017 step 1).
 *
 * The root chunk (the program's entry point) is at index 0 by convention;
 * sub-chunks (Lambda bodies, Fixpoint bodies) occupy subsequent indices.
 * The VM starts execution at chunk 0; opcodes that reference sub-chunks
 * (`MAKE_CLOSURE`, `MAKE_FIXPOINT`) provide the sub-chunk's index via
 * their constant-pool operand.
 */
data class ChunkTable(
    val chunks: List<Chunk>,
    /**
     * Review H2: EffectCategory id → declared category name for every
     * category the lowered code references, so VM denial reports render the
     * category the way the interpreter's do (the VM holds no NodeStore).
     */
    val categoryNames: Map<Int, String> = emptyMap(),
    /**
     * EffectCategory id → the category's declared parameter count, for every
     * referenced id that is an EffectCategory. The VM's unrefined-grant rule
     * (the interpreter's `checkUnrefinedGrant`) applies only to parameterized
     * categories; an id absent here is treated as parameterless.
     */
    val categoryParamCounts: Map<Int, Int> = emptyMap(),
) {
    val root: Chunk get() = chunks[0]
    operator fun get(index: Int): Chunk = chunks[index]
}
