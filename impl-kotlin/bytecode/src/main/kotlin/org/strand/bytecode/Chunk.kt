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
) {
    val root: Chunk get() = chunks[0]
    operator fun get(index: Int): Chunk = chunks[index]
}
