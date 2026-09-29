package org.strand.verifier

import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.ProjectionSource
import org.strand.core.childNodeIds

/**
 * Q-072: the machine-facing reasoning surface (ADR-010).
 *
 * A pure query layer computed from `(store, hashToNodeId, VerifyResult.Ok)`,
 * exposing typed per-subgraph queries over the *verified artifact*. It is the
 * third first-class entry point alongside verify/run — where those run a
 * program, this reasons about it: the audience is a machine consumer (an
 * orchestrating host, or the generating agent) making an admit / grant / run
 * decision from the artifact.
 *
 * Every query is keyed by a subgraph [NodeId] (defaulting to the program root)
 * and returns typed data — never a rendered string. The surface reads the
 * verifier's own effect closures ([VerifyResult.Ok.nodeClosures] and
 * [VerifyResult.Ok.latentClosures]), so it is Handler-aware by construction:
 * the closures already applied the Handler closure-subtraction, so the values
 * here are the exact closures the verifier enforced, not a looser structural
 * over-approximation a host-side graph walk would produce.
 *
 * The class holds no mutable state and performs no side effects. It lives in
 * `:verifier` — it needs only [TypeExpr], [VerifyResult], [NodeStore], and the
 * EffectCategory nodes, all already present here, with no dependency on
 * `:interpreter` or `:runtime` (so no circular module dependency). The
 * capability-requirement result is therefore modelled with verifier-local
 * types ([CapabilityRequirement] / [RefinementRequirement] / [RefinementValue])
 * rather than the interpreter's `CapabilitySet`, which lives downstream.
 */
class ProgramAnalysis(
    private val store: NodeStore,
    private val verify: VerifyResult.Ok,
    /** The program root; every query defaults its `node` argument to this. */
    val root: NodeId,
) {

    // ------------------------------------------------------------------
    // Effect-closure queries — read the verifier's own surfaced closures.
    // ------------------------------------------------------------------

    /**
     * The directly-performed effect closure of [node]: the EffectCategory
     * NodeIds whose evaluation the subgraph may exercise through the
     * Application edges the verifier walks. Handler-aware (an intercepted
     * category is subtracted). Reads [VerifyResult.Ok.nodeClosures].
     */
    fun effectClosure(node: NodeId = root): Set<NodeId> =
        verify.nodeClosures[node] ?: emptySet()

    /**
     * The latent effect closure of [node]: the EffectCategory NodeIds
     * reachable only through *indirect* invocation (N-044 ToolDef
     * implementations and higher-order callbacks). Reads
     * [VerifyResult.Ok.latentClosures].
     */
    fun latentEffectClosure(node: NodeId = root): Set<NodeId> =
        verify.latentClosures[node] ?: emptySet()

    /**
     * The total pre-execution effect reach of [node]: the union of
     * [effectClosure] and [latentEffectClosure]. The value a host reasons
     * about when it must account for everything a program could reach, latent
     * capabilities included.
     */
    fun totalClosure(node: NodeId = root): Set<NodeId> =
        effectClosure(node) + latentEffectClosure(node)

    /** Boolean reachability: does [node]'s total closure include [category]? */
    fun reachesEffect(category: NodeId, node: NodeId = root): Boolean =
        category in effectClosure(node) || category in latentEffectClosure(node)

    // ------------------------------------------------------------------
    // Clean-room / harm-bound intersections.
    // ------------------------------------------------------------------

    /**
     * The egress set: `totalClosure(node) ∩ watched`. [watched] is a
     * caller-named set of EffectCategory NodeIds — the "can this exfiltrate"
     * query generalised from demonstration code to a library call. An empty
     * result is a positive structural proof that the subgraph reaches none of
     * the watched categories.
     */
    fun egressSet(watched: Set<NodeId>, node: NodeId = root): Set<NodeId> =
        totalClosure(node).intersect(watched)

    /**
     * The harm bound under a specific grant: `totalClosure(node) ∩ granted`.
     * [granted] is the set of EffectCategory NodeIds a principal grants; the
     * result is what survives the grant — the admission-decision input
     * `closure(g) ∩ C` of the Q-044 containment bound.
     */
    fun harmBound(granted: Set<NodeId>, node: NodeId = root): Set<NodeId> =
        totalClosure(node).intersect(granted)

    // ------------------------------------------------------------------
    // Capability requirement — the categories + refinements a caller grants.
    // ------------------------------------------------------------------

    /**
     * The capability a caller must grant to run [node]: per EffectCategory in
     * the total closure, either [RefinementRequirement.Wildcard] (the category
     * is reached only at refinement-free / propagating call sites) or
     * [RefinementRequirement.Refined] carrying the concrete refinement
     * patterns pinned at the reachable EffectDecls.
     *
     * Derivation: start from [totalClosure] (every category the caller must at
     * least grant the *presence* of). Then walk the reachable Applications
     * within the subgraph and, for each EffectDecl in `effectInstances`, read
     * its parameter expressions. A statically-known refinement (a literal
     * parameter tower) becomes a concrete [RefinementValue] pattern; a
     * non-literal parameter (a VarRef forwarding a runtime value) is a
     * [RefinementValue.Dynamic] slot, so the whole site is a refinement a
     * caller must still grant but cannot fully pin at analysis time. A
     * category that appears in the closure but is reached only at sites with
     * no covering EffectDecl (refinement-free / propagating) stays a
     * [RefinementRequirement.Wildcard].
     *
     * Latent-only categories (in [latentEffectClosure] but not reached at a
     * walked Application) are [RefinementRequirement.Wildcard]: the model or a
     * higher-order builtin invokes them, so their refinement is not pinned by
     * a call site the analysis can see.
     */
    fun capabilityRequirement(node: NodeId = root): CapabilityRequirement {
        val closure = totalClosure(node)
        // category -> accumulated distinct refinement patterns (each a per-parameter list)
        val refined = HashMap<NodeId, LinkedHashSet<List<RefinementValue>>>()

        forEachReachableApplication(node) { app ->
            for (declId in app.effectInstances) {
                val decl = store.getOrNull(declId) as? Node.EffectDecl ?: continue
                val category = decl.effectType
                // Only categories genuinely in the closure are requirements; a
                // subtracted (Handler-intercepted) category may still carry an
                // EffectDecl at the intercepted call but is not required.
                if (category !in closure) continue
                val pattern = decl.parameters.map { refinementValueOf(it) }
                refined.getOrPut(category) { LinkedHashSet() }.add(pattern)
            }
        }

        val perCategory = closure.associateWith { category ->
            val patterns = refined[category]
            if (patterns.isNullOrEmpty()) {
                RefinementRequirement.Wildcard
            } else {
                RefinementRequirement.Refined(patterns.toList())
            }
        }
        return CapabilityRequirement(perCategory)
    }

    // ------------------------------------------------------------------
    // Cross-program diff.
    // ------------------------------------------------------------------

    /**
     * The categories this program's root requires that [other]'s does not —
     * the new authority a revision demands, which is what makes iterative
     * agent editing safe. Category-level ([CapabilityRequirement.categories]
     * of this minus [other]); refinement-level diffing is deferred.
     */
    fun capabilityDiff(other: ProgramAnalysis): Set<NodeId> =
        this.capabilityRequirement().categories - other.capabilityRequirement().categories

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    /**
     * Visit every [Node.Application] reachable from [start] within the local
     * store, following the same non-binder child edges the closure walk uses.
     * NodeRef targets are a subgraph boundary (their `target` is a Hash), so
     * this stays within the local image exactly as the verifier's own closure
     * accounting does.
     */
    private inline fun forEachReachableApplication(start: NodeId, visit: (Node.Application) -> Unit) {
        val seen = HashSet<NodeId>()
        val stack = ArrayDeque<NodeId>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val node = store.getOrNull(id) ?: continue
            if (node is Node.Application) visit(node)
            for (child in node.childNodeIds()) stack.addLast(child)
        }
    }

    /**
     * The static refinement value of an EffectDecl parameter expression. A
     * literal node yields a concrete [RefinementValue]; anything else (a
     * VarRef forwarding a runtime argument, a ProductValue, a computation)
     * yields [RefinementValue.Dynamic] — the caller must grant the category
     * but the exact refinement is only known at runtime.
     */
    private fun refinementValueOf(id: NodeId): RefinementValue =
        when (val n = store.getOrNull(id)) {
            is Node.IntLit -> RefinementValue.IntValue(n.value)
            is Node.FloatLit -> RefinementValue.FloatValue(n.value)
            is Node.StringLit -> RefinementValue.StringValue(n.value)
            is Node.BoolLit -> RefinementValue.BoolValue(n.value)
            is Node.UnitLit -> RefinementValue.UnitValue
            else -> RefinementValue.Dynamic
        }
}

/**
 * The capability a caller must grant to run a subgraph: a category-keyed map of
 * [RefinementRequirement]. The keys ([categories]) are the EffectCategory
 * NodeIds a caller must at least grant the presence of; the values pin the
 * refinement each category requires where the analysis can see it.
 */
data class CapabilityRequirement(
    val perCategory: Map<NodeId, RefinementRequirement>,
) {
    /** The bare set of EffectCategory NodeIds a caller must grant. */
    val categories: Set<NodeId> get() = perCategory.keys
}

/** The refinement a single EffectCategory requires within a [CapabilityRequirement]. */
sealed class RefinementRequirement {
    /**
     * The category is reached only at refinement-free / propagating call
     * sites (or only through latent/indirect invocation), so any grant of the
     * category suffices — no parameter pattern is pinned.
     */
    object Wildcard : RefinementRequirement()

    /**
     * The category is reached at one or more call sites that pin refinement
     * parameters. [patterns] holds the distinct per-site parameter patterns
     * (each a positional list of [RefinementValue], one per EffectCategory
     * parameter). A [RefinementValue.Dynamic] slot marks a parameter the
     * analysis cannot statically pin (a forwarded runtime value).
     */
    data class Refined(val patterns: List<List<RefinementValue>>) : RefinementRequirement()
}

/** One statically-analysable refinement-parameter value in a [RefinementRequirement.Refined] pattern. */
sealed class RefinementValue {
    data class IntValue(val value: Long) : RefinementValue()
    data class FloatValue(val value: Double) : RefinementValue()
    data class StringValue(val value: String) : RefinementValue()
    data class BoolValue(val value: Boolean) : RefinementValue()
    object UnitValue : RefinementValue()

    /**
     * A refinement parameter whose value is not a static literal (a forwarded
     * runtime argument, a computed value). The caller must grant the category,
     * but the exact refinement is determined at runtime and checked at dispatch.
     */
    object Dynamic : RefinementValue()
}
