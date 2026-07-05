package org.strand.verifier

import java.util.ServiceLoader

/**
 * Q-056: the verifier's view of the builtin registry's declared signature
 * and effect ground truth for a `strand-builtin:` target.
 *
 * The truth authority is the co-resident builtin signature table (the
 * `:authoring` `BuiltinSignatures` table plus the implicit-prelude reserved
 * ForeignNodes), but the module dependency direction is
 * `authoring -> verifier -> core`, so the verifier cannot reference it
 * directly. As with the Q-065 [BuiltinDeterminismOracle], the oracle is
 * resolved through [ServiceLoader]: the `:authoring` module publishes a
 * table-backed implementation under
 * `META-INF/services/org.strand.verifier.BuiltinSignatureOracle`, and any
 * classpath that carries it (the CLI, the corpus and runtime test suites,
 * every host that can actually author or execute a program) resolves it
 * automatically. On a classpath without a provider (a host embedding the
 * verifier with no builtin table) the oracle answers null for every target
 * and the ForeignNode admission cross-check DEGRADES TO SKIP — the same
 * conservative reading the determinism oracle takes: with no table present
 * there is no table claim to check.
 */
interface BuiltinSignatureOracle {
    /**
     * The declared effect-category NAME set for [target] (a
     * `strand-builtin:` string), or null when [target] is not a known
     * builtin. A registered pure builtin returns the empty set; a
     * registered effectful builtin returns the names of the
     * EffectCategory nodes its ForeignNode is expected to declare (e.g.
     * `{"Filesystem.Write"}`).
     */
    fun effectNamesFor(target: String): Set<String>?

    /**
     * The canonical monomorphic signature shape for [target], or null.
     *
     * Null covers two distinct cases the caller distinguishes via
     * [effectNamesFor]:
     *  - [effectNamesFor] also null: [target] is not a known builtin.
     *  - [effectNamesFor] non-null: [target] is a known builtin whose
     *    signature is polymorphic or agent-typed (it mentions a type
     *    variable, or is an opaque-payload family the table excludes from
     *    a single canonical shape). The verifier then checks only what is
     *    cheaply checkable — the parameter [arity][BuiltinShape.Fun] — and
     *    defers the structural remainder rather than over-rejecting a
     *    legitimate polymorphic use.
     */
    fun signatureShapeFor(target: String): BuiltinShape.Fun?
}

/**
 * A structural signature shape, canonicalized so the oracle (building it
 * from the co-resident table) and the verifier (building it from a
 * resolved [TypeExpr]) can compare for equality without either side
 * depending on the other's internal type representation.
 *
 * Only the shapes reachable in monomorphic builtin signatures are modeled.
 * Product/Sum keep ordered fields/cases; recursive shapes use a positional
 * [RecSelf] depth exactly as [TypeExpr.RecursiveSelf] does, so a
 * structurally-identical declared recursive type canonicalizes identically.
 */
sealed class BuiltinShape {
    data class Prim(val kind: String) : BuiltinShape()
    data class Fun(val parameters: List<BuiltinShape>, val result: BuiltinShape) : BuiltinShape()
    data class Product(val fields: List<Pair<String, BuiltinShape>>) : BuiltinShape()
    data class Sum(val cases: List<Pair<String, BuiltinShape?>>) : BuiltinShape()
    data class Recursive(val body: BuiltinShape) : BuiltinShape()
    data class RecSelf(val depth: Int) : BuiltinShape()
}

/**
 * Resolution seam for the [BuiltinSignatureOracle], mirroring
 * [ReplayDeterminism]. Verifier-module tests (which have no `:authoring`
 * provider on the classpath) install a fake oracle through [override];
 * production resolves the [ServiceLoader] provider once and caches it.
 */
object BuiltinSignatures {

    /**
     * Test seam: when non-null, consulted instead of the
     * [ServiceLoader]-resolved provider. Tests must reset to null in
     * teardown and must not run in parallel with other oracle-reading tests.
     */
    @Volatile
    var override: BuiltinSignatureOracle? = null

    private val loaded: BuiltinSignatureOracle? by lazy {
        ServiceLoader.load(BuiltinSignatureOracle::class.java).firstOrNull()
    }

    private fun active(): BuiltinSignatureOracle? = override ?: loaded

    /** True when a builtin-signature oracle is resolvable (else the check skips). */
    fun oracleAvailable(): Boolean = active() != null

    /** Declared effect-category name set for [target], or null (no oracle / unknown target). */
    fun effectNamesFor(target: String): Set<String>? = active()?.effectNamesFor(target)

    /** Canonical monomorphic signature shape for [target], or null (no oracle / polymorphic / unknown). */
    fun signatureShapeFor(target: String): BuiltinShape.Fun? = active()?.signatureShapeFor(target)
}
