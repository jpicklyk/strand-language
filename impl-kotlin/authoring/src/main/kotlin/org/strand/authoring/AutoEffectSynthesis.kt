package org.strand.authoring

/**
 * Q-060 M-4 slice b — opt-in `@auto` effect-declaration synthesis.
 *
 * An `@auto` marker in an Application's effect-instances slot (or in
 * the typeArguments position when typeArguments are omitted) directs
 * the Elaborator to synthesize the EffectDecl list from the callee's
 * declared effects and Q-039 projections:
 *
 *     w APP fsWrite [path data] [] @auto
 *     w APP fsWrite [path data] @auto        -- typeArguments default to []
 *
 * For each effect category the callee declares (in declaration order),
 * one EffectDecl is synthesized whose parameters come from the callee's
 * projection for that category — `ArgRef(i)` projects the application's
 * i-th value argument, `LiteralNode(t)` references the literal node `t`.
 * A category with no declared parameters synthesizes a parameterless
 * EffectDecl even when the callee carries no projections. A category
 * WITH parameters but no projection cannot be synthesized — the gap
 * scan names the explicit declaration needed; `@auto` never guesses.
 *
 * **Opt-in only.** Explicit effect-instances remain the default; an
 * Application without the marker is untouched. The synthesized
 * EffectDecls are structurally identical to the equivalent explicit
 * declarations, so an `@auto` program compiles to a canonical graph
 * byte-identical to its explicit-form counterpart — which also pins
 * the Handler interaction: Handler interception keys on the callee's
 * declared effect categories, not on the effect-instances list, so a
 * Handler-intercepted `@auto` call site behaves exactly like the
 * explicit one (asserted by `AutoEffectsEquivalenceTest`).
 *
 * Callee resolution covers: implicit-prelude reserved ForeignNodes
 * (effects + projections from the reserved spec), document-declared FN
 * nodes (effects from the optional third arg; projections from the
 * optional [EffectProjectionDsl] string), FNs synthesized by
 * [ImplicitBuiltinExpansion] in the same fixed-point loop, and
 * Lambda/Fixpoint callees (declared-or-inferred effect lists; no
 * projections, so only parameterless categories synthesize).
 */
internal object AutoEffectSynthesis {

    /** The Layer A marker token (lexes as a bare token, like `@last`). */
    const val AUTO_MARKER = "@auto"

    // ------------------------------------------------------------------
    // Public surface
    // ------------------------------------------------------------------

    fun expand(doc: LayerADocument): LayerADocument {
        if (!docMentionsMarker(doc)) return doc
        val st = State(doc)
        for (node in doc.nodes) {
            val rewritten = rewriteNode(node, st)
            if (rewritten !== node) {
                st.working[node.id] = rewritten
                st.invalidate()
                st.changed = true
            }
        }
        if (!st.changed && st.synthesized.isEmpty()) return doc
        return LayerADocument(doc.version, doc.rootId, st.working.values.toList() + st.synthesized)
    }

    /** Post-fixed-point gap scan for unresolved `@auto` markers. */
    fun diagnose(doc: LayerADocument): List<ElaborationGap> {
        if (!docMentionsMarker(doc)) return emptyList()
        val st = State(doc)
        val gaps = mutableListOf<ElaborationGap>()
        for (node in doc.nodes) {
            fun report(appArgs: List<Arg>) {
                if (appArgs.none { it is Arg.Bare && it.text == AUTO_MARKER }) return
                val reason = when (val r = resolve(appArgs, st)) {
                    is Result.Failed -> r.reason
                    is Result.Retry -> r.reason
                    else -> return
                }
                gaps += ElaborationGap(
                    nodeId = node.id,
                    field = "effectInstances",
                    inferenceCase = "@auto effect-declaration synthesis",
                    reason = reason,
                    line = node.line,
                )
            }
            if (node.code == "APP") report(node.args)
            fun walk(arg: Arg) {
                when (arg) {
                    is Arg.Listing -> arg.items.forEach(::walk)
                    is Arg.Nested -> {
                        if (arg.code == "APP") report(arg.args)
                        arg.args.forEach(::walk)
                    }
                    else -> {}
                }
            }
            node.args.forEach(::walk)
        }
        return gaps
    }

    // ------------------------------------------------------------------
    // State + walk
    // ------------------------------------------------------------------

    private class State(doc: LayerADocument) {
        val working: LinkedHashMap<String, NodeDecl> =
            LinkedHashMap<String, NodeDecl>().apply { doc.nodes.forEach { put(it.id, it) } }
        val synthesized = mutableListOf<NodeDecl>()
        var changed = false
        var counter: Int = seedCounter(doc)
        private var combined: Map<String, NodeDecl>? = null

        fun map(): Map<String, NodeDecl> {
            combined?.let { return it }
            val m = HashMap<String, NodeDecl>(working.size + synthesized.size)
            m.putAll(working)
            synthesized.forEach { m[it.id] = it }
            combined = m
            return m
        }

        fun invalidate() {
            combined = null
        }

        fun addSynth(decl: NodeDecl) {
            synthesized += decl
            invalidate()
        }

        fun freshId(): String = "__autoefd${counter++}"

        private fun seedCounter(doc: LayerADocument): Int {
            var max = -1
            val regex = Regex("""^__autoefd(\d+)$""")
            for (node in doc.nodes) {
                regex.find(node.id)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@let
                    if (n > max) max = n
                }
            }
            return max + 1
        }
    }

    private fun docMentionsMarker(doc: LayerADocument): Boolean {
        fun argHas(arg: Arg): Boolean = when (arg) {
            is Arg.Bare -> arg.text == AUTO_MARKER
            is Arg.Listing -> arg.items.any { argHas(it) }
            is Arg.Nested -> arg.args.any { argHas(it) }
            else -> false
        }
        return doc.nodes.any { node -> node.args.any { argHas(it) } }
    }

    private fun rewriteNode(node: NodeDecl, st: State): NodeDecl {
        var args = node.args
        if (node.code == "APP") {
            (resolve(args, st) as? Result.Ok)?.let { args = it.newArgs }
        }
        val rewritten = args.map { rewriteArgTree(it, st) }
        return if (rewritten == node.args) node else node.copy(args = rewritten)
    }

    private fun rewriteArgTree(arg: Arg, st: State): Arg = when (arg) {
        is Arg.Listing -> {
            val items = arg.items.map { rewriteArgTree(it, st) }
            if (items == arg.items) arg else Arg.Listing(items)
        }
        is Arg.Nested -> {
            var children = arg.args.map { rewriteArgTree(it, st) }
            if (arg.code == "APP") {
                (resolve(children, st) as? Result.Ok)?.let { children = it.newArgs }
            }
            if (children == arg.args) arg else Arg.Nested(arg.code, children)
        }
        else -> arg
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private sealed class Result {
        data class Ok(val newArgs: List<Arg>) : Result()

        /** The callee is not resolvable yet (e.g. a dotted name the
         * implicit-builtin pass has not expanded); retry next iteration. */
        data class Retry(val reason: String) : Result()

        /** A definitive failure the gap scan reports. */
        data class Failed(val reason: String) : Result()

        object NotApplicable : Result()
    }

    private sealed class Src {
        data class ArgIdx(val index: Int) : Src()
        data class Lit(val target: String) : Src()
    }

    private data class CategorySurface(val categoryRef: String, val sources: List<Src>?)

    private fun resolve(appArgs: List<Arg>, st: State): Result {
        val markerIdx = appArgs.indexOfFirst { it is Arg.Bare && it.text == AUTO_MARKER }
        if (markerIdx < 0) return Result.NotApplicable
        if (markerIdx !in 2..3) {
            return Result.Failed(
                "the @auto marker must stand in the typeArguments or effectInstances slot " +
                    "(position 3 or 4 of APP)",
            )
        }
        val calleeText = (appArgs.getOrNull(0) as? Arg.Bare)?.text
            ?: return Result.Failed("the application's callee is not a bare reference")
        val argItems = (appArgs.getOrNull(1) as? Arg.Listing)?.items
            ?: return Result.Failed("the application's value-argument list is malformed")

        val surface = calleeEffectSurface(calleeText, st)
            ?: return Result.Retry(
                "the callee '$calleeText' could not be resolved to a function with a known " +
                    "effect surface; declare explicit EffectDecls",
            )

        val efdIds = mutableListOf<String>()
        for (cat in surface) {
            val params: List<Arg> = when {
                cat.sources != null -> cat.sources.map { src ->
                    when (src) {
                        is Src.ArgIdx -> argItems.getOrNull(src.index)
                            ?: return Result.Failed(
                                "the projection for '${cat.categoryRef}' references argument " +
                                    "${src.index + 1}, but the call supplies only ${argItems.size}",
                            )
                        is Src.Lit -> Arg.Bare(src.target)
                    }
                }
                else -> when (categoryParameterCount(cat.categoryRef, st)) {
                    0 -> emptyList()
                    null -> return Result.Retry(
                        "effect category '${cat.categoryRef}' could not be resolved; declare " +
                            "it (EFC) or use explicit EffectDecls",
                    )
                    else -> return Result.Failed(
                        "category '${cat.categoryRef}' declares parameters but the callee " +
                            "carries no Q-039 projection for it; declare an explicit EffectDecl",
                    )
                }
            }
            val id = st.freshId()
            st.addSynth(NodeDecl(
                id = id,
                code = "EFD",
                args = listOf(Arg.Bare(cat.categoryRef), Arg.Listing(params)),
                line = 0,
            ))
            efdIds += id
        }

        val typeArgs: Arg = if (markerIdx == 2) Arg.Listing(emptyList()) else appArgs[2]
        return Result.Ok(listOf(
            appArgs[0],
            appArgs[1],
            typeArgs,
            Arg.Listing(efdIds.map { Arg.Bare(it) }),
        ))
    }

    /**
     * The callee's declared effect surface: one [CategorySurface] per
     * declared category, in declaration order. Null when the callee
     * cannot be resolved (yet).
     */
    private fun calleeEffectSurface(calleeText: String, st: State): List<CategorySurface>? {
        val map = st.map()

        // Reserved prelude ForeignNode (fsWrite, netConnect, now, ...).
        val spec = LayerAGrammar.reservedNodes[calleeText]
        if (spec?.jsonType == "ForeignNode") {
            val effects = spec.refListFields["effects"] ?: emptyList()
            val projByCategory = spec.effectProjections.associateBy { it.category }
            return effects.map { cat ->
                val proj = projByCategory[cat]
                CategorySurface(
                    categoryRef = cat,
                    sources = proj?.sources?.map { src ->
                        when (src) {
                            is LayerAGrammar.ReservedProjectionSource.ArgRef -> Src.ArgIdx(src.index)
                            is LayerAGrammar.ReservedProjectionSource.LiteralNode -> Src.Lit(src.target)
                        }
                    },
                )
            }
        }

        val resolved = Elaborator.resolveCalleeChain(calleeText, map, HashSet()) ?: return null
        val decl = map[resolved] ?: return null
        return when (decl.code) {
            "FN" -> {
                val effects = (decl.args.getOrNull(2) as? Arg.Listing)?.items
                    ?.mapNotNull { (it as? Arg.Bare)?.text }
                    ?: emptyList()
                val dsl = (decl.args.getOrNull(3) as? Arg.Str)?.value
                val entries = dsl?.let { EffectProjectionDsl.parse(it) }
                val byCategory = entries?.associateBy { it.category }
                effects.map { cat ->
                    val entry = byCategory?.get(cat)
                    CategorySurface(
                        categoryRef = cat,
                        sources = entry?.sources?.map { src ->
                            when (src) {
                                is EffectProjectionDsl.Source.ArgIndex -> Src.ArgIdx(src.index)
                                is EffectProjectionDsl.Source.Literal -> Src.Lit(src.target)
                            }
                        },
                    )
                }
            }
            "LAM", "FIX" -> Elaborator.effectsOfOrdered(calleeText, map, HashSet())
                .map { CategorySurface(categoryRef = it, sources = null) }
            else -> null
        }
    }

    /**
     * Declared parameter count of an EffectCategory (document-declared,
     * synthesized, or reserved). Null when the category is unknown.
     */
    private fun categoryParameterCount(categoryRef: String, st: State): Int? {
        val decl = st.map()[categoryRef]
        if (decl != null) {
            if (decl.code != "EFC") return null
            return (decl.args.getOrNull(1) as? Arg.Listing)?.items?.size ?: 0
        }
        val spec = LayerAGrammar.reservedNodes[categoryRef] ?: return null
        if (spec.jsonType != "EffectCategory") return null
        return spec.refListFields["parameters"]?.size ?: 0
    }
}
