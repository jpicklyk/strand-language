package org.strand.verifier

import org.strand.core.Hash
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
    /**
     * The program's hash-to-NodeId map, read only by the machine-shaped
     * queries ([machineClosure], [groupClosure]) to follow [Node.NodeRef]
     * edges while collecting a machine's latent reach. When it is absent (the
     * default) an unresolvable NodeRef makes those queries fall back to the
     * program's whole latent channel, which is looser but still sound.
     */
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
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
    // Machine-shaped closures (Q-073: the runMachine / runGroup gate).
    // ------------------------------------------------------------------

    /**
     * A sound bound on the EffectCategory NodeIds that driving [machine] can
     * perform: no run of the synchronous fold over [machine] (any event list,
     * any grant) performs an effect whose category is outside
     * [MachineClosure.total]. [totalClosure] cannot answer this: a
     * StateMachine node's own closure is empty (evaluating the node performs
     * nothing; its effects happen when the runtime drives it).
     *
     * Driving a machine evaluates its `initialState` once, evaluates its
     * `transitionFn` once (a Lambda, which performs nothing), applies the
     * transition to every event, and performs the implicit
     * `StateMachine.Receive` at every input pull and `StateMachine.Send` at
     * every output push. The two channels are bounded as follows.
     *
     * [MachineClosure.direct] is the machine's declared `effects` row. The
     * verifier requires it to cover the transition Lambda's effect row and
     * the effect closure of `initialState` (`StateMachineEffectCoverageViolation`)
     * and to name `StateMachine.Receive`, plus `StateMachine.Send` when the
     * machine has an output stream (`StateMachineMissingImplicitEffect`), so
     * it bounds everything the machine performs through Application edges
     * the verifier walks. It may be looser than what the transition performs
     * (a declared category no call site exercises stays in the bound).
     *
     * [MachineClosure.latent] is the union of the verifier's latent channel
     * ([VerifyResult.Ok.latentClosures]: rows of effectful values in argument
     * position, e.g. a callback handed to `List.Map`, and of ToolDef
     * implementations) over every site reachable from `transitionFn` and
     * `initialState`, following child edges, [Node.NodeRef] targets, and
     * [Node.VarRef]s bound by a [Node.Let] (a value defined outside the
     * machine that the transition uses). The coverage rule does not require
     * the declared row to cover this channel, so a category can be in
     * [MachineClosure.latent] and absent from the declared row.
     *
     * Not included: effects of machines the transition spawns
     * (`StateMachine.Spawn` only acts inside a group; see [groupClosure]),
     * and anything a host-supplied event value could carry (events are
     * data).
     *
     * [machine] must be a [Node.StateMachine] the verification this analysis
     * wraps reached; anything else is a host error and throws
     * [IllegalArgumentException].
     */
    fun machineClosure(machine: NodeId): MachineClosure {
        val node = store.getOrNull(machine) as? Node.StateMachine
            ?: throw IllegalArgumentException(
                "machineClosure: $machine is not a StateMachine (${store.getOrNull(machine)?.javaClass?.simpleName})"
            )
        require(machine in verify.nodeClosures) {
            "machineClosure: StateMachine $machine was not reached by the verification this analysis wraps"
        }
        return MachineClosure(
            machine = machine,
            direct = LinkedHashSet(node.effects),
            latent = latentReach(listOf(node.transitionFn, node.initialState)),
        )
    }

    /**
     * A sound bound on what running a group of [machines] can perform: the
     * union of [machineClosure] over every machine the group can drive, plus
     * the effects of opening and draining its `source`-bound external
     * streams.
     *
     * Machines: the listed [machines]; and, when any of them can perform a
     * category named `StateMachine.Spawn` (directly or latently), every
     * StateMachine in the store, since a spawn names its target by content
     * hash at runtime. Each spawnable machine the verification did not reach
     * contributes its declared row plus the program's whole latent channel.
     *
     * Sources ([GroupClosure.sources]): for every `source`-bound external
     * input stream of the listed machines (the streams the runtime opens at
     * group start, before any actor runs), the categories evaluating the
     * opener Application can reach, plus the transport category the
     * runtime's feeder performs on every read (`Network.Receive`). The
     * verifier checks an opener's shape (a registered opener builtin whose
     * `effectInstances` name its semantic effect) but does not infer the
     * opener subgraph, so it records no closure for it; the bound is
     * therefore structural: every EffectCategory node reachable from the
     * opener (its callee's declared effects, its EffectDecls, and whatever
     * its unverified arguments reference). The runtime checks
     * the transport category by name (`MachineGroupValidationError.ExternalStreamSourceEffectUncovered`);
     * the bound resolves the name to every EffectCategory node in the store
     * carrying it, and records the name in
     * [GroupClosure.unresolvedCategoryNames] when the store has none.
     * Source effects are performed directly by the runtime, so they join
     * [GroupClosure.direct].
     */
    fun groupClosure(machines: Collection<NodeId>): GroupClosure {
        val driven = LinkedHashMap<NodeId, MachineClosure>()
        for (m in machines) driven[m] = machineClosure(m)
        if (driven.values.any { mc -> mc.total.any { categoryNameOf(it) == SPAWN_EFFECT_NAME } }) {
            for ((id, n) in store.entries()) {
                if (n !is Node.StateMachine || id in driven) continue
                driven[id] = if (id in verify.nodeClosures) {
                    machineClosure(id)
                } else {
                    MachineClosure(id, LinkedHashSet(n.effects), allLatent())
                }
            }
        }

        val sources = ArrayList<SourceClosure>()
        val seenStreams = HashSet<NodeId>()
        for (m in machines) {
            val node = store.getOrNull(m) as? Node.StateMachine ?: continue
            for (streamId in node.inputStreams + node.outputStreams) {
                if (!seenStreams.add(streamId)) continue
                val stream = store.getOrNull(streamId) as? Node.EventStream ?: continue
                val opener = stream.source ?: continue
                if (stream.streamKind != org.strand.core.StreamKind.External) continue
                sources += SourceClosure(
                    stream = streamId,
                    opener = opener,
                    direct = (verify.nodeClosures[opener] ?: emptySet()) + structuralCategories(listOf(opener)),
                    latent = latentReach(listOf(opener)),
                    transportCategoryName = SOURCE_TRANSPORT_EFFECT_NAME,
                    transportCategories = categoriesNamed(SOURCE_TRANSPORT_EFFECT_NAME),
                )
            }
        }
        return GroupClosure(driven, sources)
    }

    /**
     * The latent channel reachable from [starts]: the union of
     * [VerifyResult.Ok.latentClosures] over every site reached through child
     * edges, NodeRef targets, and Let-bound VarRefs. An unresolvable NodeRef
     * (no [hashToNodeId] entry) makes the walk give up on precision and
     * return the program's whole latent channel.
     */
    private fun latentReach(starts: List<NodeId>): Set<NodeId> {
        val out = LinkedHashSet<NodeId>()
        val complete = walkValueReach(starts) { id, _ -> verify.latentClosures[id]?.let { out += it } }
        return if (complete) out else allLatent()
    }

    /**
     * Every EffectCategory node reachable from [starts] by the
     * [walkValueReach] edges: a structural bound on the categories evaluating
     * the subgraph can be granted, for a subgraph the verification recorded
     * no closure for. An unresolvable NodeRef widens it to every
     * EffectCategory in the store.
     */
    private fun structuralCategories(starts: List<NodeId>): Set<NodeId> {
        val out = LinkedHashSet<NodeId>()
        val complete = walkValueReach(starts) { id, node -> if (node is Node.EffectCategory) out += id }
        return if (complete) out else store.entries().filter { (_, n) -> n is Node.EffectCategory }
            .mapTo(LinkedHashSet()) { (id, _) -> id }
    }

    /**
     * Visit every node reachable from [starts] through child edges, NodeRef
     * targets, and — for a [Node.VarRef] — the value of the [Node.Let] that
     * binds it (its only route to a value defined outside the subgraph; a
     * ParameterDecl or pattern binder receives its value from inside). A
     * VarRef's own child edge to its binder is not followed, so a Let body
     * enclosing the subgraph is not swept in. Returns false when a NodeRef
     * target has no [hashToNodeId] entry (the walk is then incomplete).
     */
    private inline fun walkValueReach(starts: List<NodeId>, visit: (NodeId, Node) -> Unit): Boolean {
        val seen = HashSet<NodeId>()
        val stack = ArrayDeque(starts)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val node = store.getOrNull(id) ?: continue
            visit(id, node)
            when (node) {
                is Node.NodeRef -> stack.addLast(hashToNodeId[node.target] ?: return false)
                is Node.VarRef -> (store.getOrNull(node.binder) as? Node.Let)?.let { stack.addLast(it.value) }
                else -> for (child in node.childNodeIds()) stack.addLast(child)
            }
        }
        return true
    }

    /** Every latent contribution the verification recorded, program-wide. */
    private fun allLatent(): Set<NodeId> = verify.latentClosures.values.flatMapTo(LinkedHashSet()) { it }

    private fun categoryNameOf(id: NodeId): String? = (store.getOrNull(id) as? Node.EffectCategory)?.categoryName

    private fun categoriesNamed(name: String): Set<NodeId> =
        store.entries().filter { (_, n) -> n is Node.EffectCategory && n.categoryName == name }
            .mapTo(LinkedHashSet()) { (id, _) -> id }

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

    private companion object {
        val SPAWN_EFFECT_NAME: String = WellKnownEffect.StateMachineSpawn.categoryName

        /**
         * The transport category the runtime's stream feeder performs on every
         * read of a `source`-bound external stream (`MachineGroup`'s drain
         * effect in `:runtime`).
         */
        const val SOURCE_TRANSPORT_EFFECT_NAME = "Network.Receive"
    }
}

/**
 * [ProgramAnalysis.machineClosure]: a sound bound on the effect categories
 * driving [machine] can perform, split by channel. [direct] is the declared
 * `effects` row (which the verifier forces to cover the transition's effect
 * row, the `initialState` closure, and the implicit `StateMachine.Receive` /
 * `StateMachine.Send`); [latent] is the latent channel reachable from the
 * transition and the initial state. The two may overlap.
 */
data class MachineClosure(
    val machine: NodeId,
    val direct: Set<NodeId>,
    val latent: Set<NodeId>,
) {
    /** [direct] ∪ [latent]: the bound a budget must cover. */
    val total: Set<NodeId> get() = direct + latent
}

/**
 * One `source`-bound external stream of a group in a [GroupClosure]: the
 * [opener] Application the runtime evaluates at group start, its effect
 * closure ([direct]) and latent reach ([latent]), and the transport category
 * the feeder performs on every read, by name ([transportCategoryName]) and as
 * the store's EffectCategory nodes carrying that name ([transportCategories],
 * empty when there are none).
 */
data class SourceClosure(
    val stream: NodeId,
    val opener: NodeId,
    val direct: Set<NodeId>,
    val latent: Set<NodeId>,
    val transportCategoryName: String,
    val transportCategories: Set<NodeId>,
)

/**
 * [ProgramAnalysis.groupClosure]: a sound bound on what running a group can
 * perform. [machines] holds a [MachineClosure] for every machine the group
 * can drive (the listed ones, plus every StateMachine in the store when one
 * of them can spawn); [sources] the group's `source`-bound streams.
 */
data class GroupClosure(
    val machines: Map<NodeId, MachineClosure>,
    val sources: List<SourceClosure>,
) {
    /** Performed through walked Application edges or by the runtime itself (source opening and draining). */
    val direct: Set<NodeId>
        get() = machines.values.flatMapTo(LinkedHashSet()) { it.direct } +
            sources.flatMap { it.direct + it.transportCategories }

    /** Reachable only through indirect invocation (callbacks, tool implementations). */
    val latent: Set<NodeId>
        get() = machines.values.flatMapTo(LinkedHashSet()) { it.latent } + sources.flatMap { it.latent }

    /** [direct] ∪ [latent]. */
    val total: Set<NodeId> get() = direct + latent

    /**
     * Source transport categories the runtime requires by name for which the
     * store holds no EffectCategory node, so no grant over this program can
     * cover them and the runtime refuses the group at start.
     */
    val unresolvedCategoryNames: Set<String>
        get() = sources.filter { it.transportCategories.isEmpty() }.mapTo(LinkedHashSet()) { it.transportCategoryName }
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
