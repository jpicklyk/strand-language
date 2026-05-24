package org.strand.authoring

/**
 * Layer C elaboration — first slice (Q-034 step 1).
 *
 * Bidirectional type inference and effect propagation that fill in
 * annotations the LLM can omit. The proposal (`proposals/llm-authoring-
 * layer.md` § 5.3) sketches four elaboration cases:
 *
 *  1. `Lambda.paramType` — inferred from call-site argument types
 *  2. `Application.typeArguments` — inferred from value-argument types
 *  3. `Lambda.effects` — inferred from the body's effect closure
 *  4. `Application.effectInstances` — defaulted from the surrounding
 *     capability context when unambiguous
 *
 * This first slice ships **only case (3)**: effect-closure inference for
 * Lambda. The other three cases would each be similarly bounded inference
 * passes; they share the AST-walking machinery but require type
 * information the simple author-id graph does not carry. They are
 * deferred to follow-up slices.
 *
 * The inference algorithm is structural and works directly on the Layer
 * A document (no dag-json round-trip, no verifier reuse):
 *
 *  * For each Lambda node whose `effects` argument is absent, compute
 *    the body's effect closure by walking the author-id graph.
 *  * Closure rules mirror the verifier's `nodeClosures` computation
 *    (`Verifier.kt` § effect closure):
 *      - `Application` releases the function's declared effects plus
 *        the closure of the function and arguments
 *      - `Lambda` and `ForeignNode` construction release no effects
 *        themselves; their declared effects fire only at call sites
 *      - `Let` is the union of value and body closures
 *      - `Match` is scrutinee + every case body
 *      - `Handler` is the subtraction rule
 *        `(body.closure - {intercept}) ∪ handle.closure ∪ handleFun.effects`
 *      - everything else recurses into its expression children
 *  * If the inferred closure is non-empty, the Lambda's args list is
 *    extended with the effects list. If empty, the Lambda is left
 *    unchanged (the optional `effects` slot defaults to `[]` in the
 *    grammar).
 *
 * The resulting document compiles via [DagJsonEmitter] to canonical
 * dag-json that the verifier accepts. Round-trip integrity with the
 * original canonical JSON is **not** preserved — elaboration is a 1-way
 * compilation; programs that need exact canonical recovery should
 * continue to declare effects explicitly in Layer A.
 *
 * Cases the inference cannot resolve (e.g., a Lambda whose body calls a
 * function bound by a Let whose value is itself an Application returning
 * a closure) are accepted with an empty inferred set; the verifier will
 * surface the under-declaration as `UncoveredEffects` at compile time,
 * matching the proposal's `ElaborationGap` policy ("emit a structured
 * gap diagnostic; the LLM must annotate"). For this slice the gap is
 * implicit (no explicit `ElaborationGap` type is introduced); a future
 * slice can promote it to a structured diagnostic.
 */
object Elaborator {

    /**
     * Elaborate [doc]: fill in inferred Lambda effects. The returned
     * document is structurally equivalent to [doc] except for LAM nodes
     * whose `effects` argument was absent and whose body has a non-empty
     * inferred closure.
     */
    fun elaborate(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val newNodes = doc.nodes.map { node ->
            if (node.code == "LAM" && node.args.size == 2) {
                val bodyArg = node.args[1] as? Arg.Bare
                    ?: return@map node  // malformed; let the emitter report it
                val bodyClosure = closureOf(bodyArg.text, byId, HashSet())
                if (bodyClosure.isEmpty()) {
                    node
                } else {
                    val effectsList = Arg.Listing(
                        bodyClosure.toList().sorted().map { Arg.Bare(it) }
                    )
                    node.copy(args = node.args + effectsList)
                }
            } else {
                node
            }
        }
        return doc.copy(nodes = newNodes)
    }

    /**
     * Compute the effect closure of the expression at author id [nodeId].
     * The closure is the set of EffectCategory author-ids the expression
     * may exercise during evaluation. Mirrors `Verifier.nodeClosures`
     * semantics but operates on the Layer A AST.
     *
     * Visited tracking is per-walk to prevent infinite recursion on
     * malformed self-referential graphs; well-formed Strand programs
     * have no expression-position cycles, so this is defensive only.
     */
    private fun closureOf(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        if (!visited.add(nodeId)) return emptySet()
        val node = byId[nodeId] ?: return emptySet()
        return when (node.code) {
            "APP" -> closureOfApplication(node, byId, visited)
            "LET" -> closureOfLet(node, byId, visited)
            "MAT" -> closureOfMatch(node, byId, visited)
            "MC" -> {
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                closureOf(bodyId, byId, visited)
            }
            "FIX" -> {
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                closureOf(bodyId, byId, visited)
            }
            "H" -> closureOfHandler(node, byId, visited)
            "CAP" -> {
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                closureOf(bodyId, byId, visited)
            }
            "TAB" -> {
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                closureOf(bodyId, byId, visited)
            }
            // Lambda / ForeignNode / TypeAbstraction / value constructors
            // / variable references release no effects at construction.
            "LAM", "FN", "VAR", "NRF", "RS",
            "ILT", "FLT", "STR", "BLT", "ULT", "BYT",
            "PRM", "PRD", "PRF", "SUM", "SCS", "FNT", "TPM", "FAL",
            "PRC", "EFC", "EFD",
            "PV", "PFV", "SV", "RT",
            "SM", "ESE", "ESI", "ESO", "TR",
            "SCH", "INV",
            "PLT", "PVR", "PWC", "PCN" -> emptySet()
            "PFG" -> {
                // ProductFieldGet's target expression releases effects.
                val targetId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
                closureOf(targetId, byId, visited)
            }
            else -> emptySet()
        }
    }

    private fun closureOfApplication(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        // APP args: function, [arguments], [optional typeArguments], [optional effectInstances]
        val fnId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
        val argList = (node.args[1] as? Arg.Listing)?.items ?: emptyList()
        val out = HashSet<String>()
        out += closureOf(fnId, byId, visited)
        for (arg in argList) {
            val argId = (arg as? Arg.Bare)?.text ?: continue
            out += closureOf(argId, byId, HashSet(visited))
        }
        // The function's *declared* effects fire at the call site.
        out += effectsOf(fnId, byId, HashSet())
        return out
    }

    private fun closureOfLet(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        // LET args: name, value, body
        val valueId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
        val bodyId = (node.args[2] as? Arg.Bare)?.text ?: return emptySet()
        return closureOf(valueId, byId, visited) + closureOf(bodyId, byId, HashSet(visited))
    }

    private fun closureOfMatch(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        // MAT args: scrutinee, [cases]
        val scrutId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
        val caseList = (node.args[1] as? Arg.Listing)?.items ?: emptyList()
        val out = HashSet<String>()
        out += closureOf(scrutId, byId, visited)
        for (case in caseList) {
            val caseId = (case as? Arg.Bare)?.text ?: continue
            out += closureOf(caseId, byId, HashSet(visited))
        }
        return out
    }

    private fun closureOfHandler(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        // H args: intercept, handle, body
        val interceptId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
        val handleId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
        val bodyId = (node.args[2] as? Arg.Bare)?.text ?: return emptySet()
        val bodyClosure = closureOf(bodyId, byId, visited)
        val handleClosure = closureOf(handleId, byId, HashSet(visited))
        val handleEffects = effectsOf(handleId, byId, HashSet())
        // Closure subtraction: (body - {intercept}) ∪ handle.closure ∪ handle.effects
        return (bodyClosure - interceptId) + handleClosure + handleEffects
    }

    /**
     * The set of declared EffectCategory author-ids that calling [nodeId]
     * as a function would release. Used by `closureOfApplication` to
     * compute what a call-site exercises.
     *
     *  - `FN` (ForeignNode): the explicit effects list at args[2] if present
     *  - `LAM`: the explicit effects list at args[2] if present;
     *           otherwise recursively the body's closure
     *  - `VAR`: the binder's effects (looked up by author id)
     *  - `NRF`: the target's effects
     *  - other categories: empty (not callable)
     */
    private fun effectsOf(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        if (!visited.add(nodeId)) return emptySet()
        val node = byId[nodeId] ?: return emptySet()
        return when (node.code) {
            "FN" -> {
                // FN args: target, foreignType, [optional effects]
                if (node.args.size >= 3) extractEffectsList(node.args[2]) else emptySet()
            }
            "LAM" -> {
                if (node.args.size >= 3) {
                    extractEffectsList(node.args[2])
                } else {
                    val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                    closureOf(bodyId, byId, HashSet(visited))
                }
            }
            "VAR" -> {
                val binderId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
                effectsOf(binderId, byId, visited)
            }
            "NRF" -> {
                val targetId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
                effectsOf(targetId, byId, visited)
            }
            "LET" -> {
                // VarRef to a Let binder — the Let's value is what's bound.
                val valueId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                effectsOf(valueId, byId, visited)
            }
            "FIX" -> {
                // Calling a Fixpoint runs the body Lambda.
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                effectsOf(bodyId, byId, visited)
            }
            else -> emptySet()
        }
    }

    private fun extractEffectsList(arg: Arg): Set<String> {
        val listing = arg as? Arg.Listing ?: return emptySet()
        return listing.items.mapNotNull { (it as? Arg.Bare)?.text }.toSet()
    }
}
