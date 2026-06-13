package org.strand.hashing

/**
 * The 4-byte category tag prefixed to each node's canonical encoding, per
 * design/node-algebra.md § Hash construction and § Versioning. Tags are stable
 * numeric ids drawn from the N-NNN identifier registry; existing ids are never
 * reused or reassigned. A node's category tag may not change once published,
 * because doing so would invalidate every hash that depended on it.
 *
 * Layer 1 uses N-001 through N-019 plus N-034 (TypeAbstraction) and N-035
 * (ForallType). Two of these node categories are *intrinsic* in the sense
 * that they never receive a standalone canonical encoding:
 *
 *   * `ParameterDecl` (N-015) is intrinsic to its containing Lambda. Lambda's
 *     canonical encoding embeds each parameter's type directly; the
 *     ParameterDecl itself is never independently hashed. Tag 15 is reserved
 *     for forward compatibility but is unused in Layer 2 step 1's encoding.
 *
 *   * `TypeParameter` (N-013) has no standalone canonical encoding because
 *     its identity is positional (de Bruijn). Tag 13 is used for the
 *     *reference* form `(depth, index)` that appears wherever a bound type
 *     variable is referenced from a type position.
 */
@JvmInline
value class CategoryTag(val value: Int) {

    /** Big-endian 4-byte encoding of the tag value. */
    fun toBytes(): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    companion object {
        // Literals (N-001..N-006)
        val IntLit = CategoryTag(1)
        val FloatLit = CategoryTag(2)
        val StringLit = CategoryTag(3)
        val BoolLit = CategoryTag(4)
        val UnitLit = CategoryTag(5)
        val BytesLit = CategoryTag(6)

        // Types (N-007..N-013, N-035)
        val PrimitiveType = CategoryTag(7)
        val ProductType = CategoryTag(8)
        val ProductTypeField = CategoryTag(9)
        val SumType = CategoryTag(10)
        val SumTypeCase = CategoryTag(11)
        val FunctionType = CategoryTag(12)

        /**
         * Used inline at every reference to a TypeParameter that is bound by
         * an enclosing TypeAbstraction or ForallType. Carries the de Bruijn
         * `(depth, index)` pair, not a hash. TypeParameter nodes themselves
         * are never independently hashed.
         */
        val TypeParameterRef = CategoryTag(13)

        val ForallType = CategoryTag(35)
        val RecursiveType = CategoryTag(41)
        val RecursiveSelf = CategoryTag(42)
        val RecursiveProjection = CategoryTag(48)

        // Functions and binding (N-014..N-018, N-034)
        val Lambda = CategoryTag(14)
        // N-015 ParameterDecl is intrinsic; reserved but unused as a tag.
        val Application = CategoryTag(16)
        val Let = CategoryTag(17)
        val VarRef = CategoryTag(18)
        val TypeAbstraction = CategoryTag(34)

        // References (N-019, N-020)
        val NodeRef = CategoryTag(19)
        val ForeignNode = CategoryTag(20)

        // Effects and capabilities (N-021, N-022, N-036)
        val EffectCategory = CategoryTag(21)
        val EffectDecl = CategoryTag(22)
        val CapabilityScope = CategoryTag(36)

        // Control flow (N-023..N-026)
        val Match = CategoryTag(23)
        val MatchCase = CategoryTag(24)
        // N-025 Pattern is one node category in the algebra; the variant is
        // encoded as a small discriminator within the canonical bytes
        // (literal=0, variable=1, wildcard=2) so all patterns share tag 25.
        val Pattern = CategoryTag(25)
        val Fixpoint = CategoryTag(26)

        // State machines (N-027..N-029)
        val StateMachine = CategoryTag(27)
        val EventStream = CategoryTag(28)
        val Transition = CategoryTag(29)

        // Composite values (N-037..N-040)
        val ProductValue = CategoryTag(37)
        val ProductFieldValue = CategoryTag(38)
        val ProductFieldGet = CategoryTag(39)
        val SumValue = CategoryTag(40)

        // Effect handlers (N-043)
        val Handler = CategoryTag(43)

        // Structured outputs (N-032, N-033)
        val Schema = CategoryTag(32)
        val Invariant = CategoryTag(33)

        // Agent-native capabilities (N-044, N-045)
        val ToolDef = CategoryTag(44)
        val ResponseSchemaSpec = CategoryTag(45)

        // Composition and distribution (N-046)
        val ModuleManifest = CategoryTag(46)

        // Error recovery (N-047)
        val Attempt = CategoryTag(47)
    }
}
