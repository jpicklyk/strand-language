package org.strand.authoring

import org.strand.authoring.BuiltinSignatures.Sig

/**
 * Q-060 M-4 slice a — registry-wide implicit builtins.
 *
 * An Elaborator inference case: a bare dotted registry-builtin name in
 * Application callee position (e.g. `mapped APP List.Map [xs f]`)
 * expands to a synthesized ForeignNode + FunctionType pair, with the
 * polymorphic signature from [BuiltinSignatures] instantiated from the
 * already-elaborated argument types at the application site.
 *
 * The dotted canonical name is the Layer A spelling: author ids cannot
 * contain dots ([LayerAParser.isBareIdentifier] rejects them at the id
 * position), so `List.Map` in callee position can never collide with a
 * user declaration.
 *
 * **Local instantiation only.** Argument types are matched against the
 * signature's parameter shapes positionally; type variables bind to the
 * author-ids of the argument types. There is no unification across
 * sites (the Q-034 § 6 boundary). Where the argument types
 * underdetermine the instantiation — `List.Empty`'s element type,
 * `Map.Get`'s value type, a lambda whose parameter or return type
 * cannot be resolved — the expansion does not fire and the post-fixed-
 * point gap scan ([diagnose]) produces an [ElaborationGap] naming the
 * annotation needed. The expansion never guesses.
 *
 * **Hash neutrality.** Synthesized nodes (`__bi<n>_*` author ids) are
 * structurally identical to the hand-declared equivalents, so the
 * hash-keyed dedup store collapses them — a program using the bare name
 * compiles to a canonical graph byte-identical to its explicit-form
 * counterpart (the slice's acceptance gate).
 *
 * **Two-way lambda annotation.** When a function-shaped parameter is
 * supplied as a lambda whose own parameter annotations are absent but
 * the signature determines them (e.g. `List.Map`'s `(A) -> B` once `A`
 * is bound from the list argument), the expansion pushes the
 * instantiated types into the lambda's compact parameter entries —
 * mirroring the existing call-site paramType inference.
 */
internal object ImplicitBuiltinExpansion {

    // ------------------------------------------------------------------
    // Public surface
    // ------------------------------------------------------------------

    /** Run one expansion pass over [doc]. Pure; returns [doc] when nothing fires. */
    fun expand(doc: LayerADocument): LayerADocument {
        if (!docMentionsDottedCallee(doc)) return doc
        val st = State(doc)
        for (node in doc.nodes) {
            val rewritten = rewriteNode(node, st)
            if (rewritten !== node) {
                st.working[node.id] = rewritten
                st.invalidate()
                st.changed = true
            }
        }
        applyAnnotations(st)
        if (!st.changed && st.synthesized.isEmpty()) return doc
        return LayerADocument(doc.version, doc.rootId, st.working.values.toList() + st.synthesized)
    }

    /**
     * Post-fixed-point gap scan: every dotted-callee Application still
     * unexpanded is an attempted-but-failed inference. Re-runs the pure
     * matching phase to name the exact annotation needed.
     */
    fun diagnose(doc: LayerADocument): List<ElaborationGap> {
        if (!docMentionsDottedCallee(doc)) return emptyList()
        val st = State(doc)
        val gaps = mutableListOf<ElaborationGap>()
        for (node in doc.nodes) {
            collectGapsInNode(node, st, gaps)
        }
        return gaps
    }

    // ------------------------------------------------------------------
    // Pass state
    // ------------------------------------------------------------------

    private class State(doc: LayerADocument) {
        val working: LinkedHashMap<String, NodeDecl> =
            LinkedHashMap<String, NodeDecl>().apply { doc.nodes.forEach { put(it.id, it) } }
        val synthesized = mutableListOf<NodeDecl>()
        val synthById = HashMap<String, NodeDecl>()
        val prim: Map<String, String> = Elaborator.buildPrimTypeMap(doc)

        /** Ground-shape key -> existing/synthesized type author id. */
        val instCache = HashMap<String, String>()

        /** target|params|result -> FN author id. */
        val fnCache = HashMap<String, String>()

        /** Synthesized EffectCategory author id per category name. */
        val efcCache = HashMap<String, String>()

        /** Top-level lambda annotations: lamId -> (paramIndex -> typeId). */
        val pendingLamAnnotations = LinkedHashMap<String, LinkedHashMap<Int, String>>()

        var changed = false
        var counter: Int = seedCounter(doc)
        var tmpCounter: Int = 0
        private var combined: Map<String, NodeDecl>? = null

        fun map(): Map<String, NodeDecl> {
            combined?.let { return it }
            val m = HashMap<String, NodeDecl>(working.size + synthById.size)
            m.putAll(working)
            m.putAll(synthById)
            combined = m
            return m
        }

        fun invalidate() {
            combined = null
        }

        fun addSynth(decl: NodeDecl) {
            synthesized += decl
            synthById[decl.id] = decl
            invalidate()
        }

        fun freshId(suffix: String): String = "__bi${counter++}_$suffix"

        private fun seedCounter(doc: LayerADocument): Int {
            var max = -1
            val regex = Regex("""^__bi(\d+)_""")
            for (node in doc.nodes) {
                regex.find(node.id)?.let { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@let
                    if (n > max) max = n
                }
            }
            return max + 1
        }
    }

    private fun docMentionsDottedCallee(doc: LayerADocument): Boolean {
        fun argHas(arg: Arg): Boolean = when (arg) {
            is Arg.Nested ->
                (arg.code == "APP" && (arg.args.firstOrNull() as? Arg.Bare)?.text?.contains('.') == true) ||
                    arg.args.any { argHas(it) }
            is Arg.Listing -> arg.items.any { argHas(it) }
            else -> false
        }
        return doc.nodes.any { node ->
            (node.code == "APP" && (node.args.firstOrNull() as? Arg.Bare)?.text?.contains('.') == true) ||
                node.args.any { argHas(it) }
        }
    }

    // ------------------------------------------------------------------
    // Rewrite walk
    // ------------------------------------------------------------------

    private fun rewriteNode(node: NodeDecl, st: State): NodeDecl {
        var args = node.args
        if (node.code == "APP") {
            expandAppArgs(args, st)?.let { args = it }
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
                expandAppArgs(children, st)?.let { children = it }
            }
            if (children == arg.args) arg else Arg.Nested(arg.code, children)
        }
        else -> arg
    }

    /**
     * Attempt the expansion over an APP's positional args. Returns the
     * rewritten args (callee replaced, nested lambdas annotated) or null
     * when the expansion does not apply / cannot fire yet.
     */
    private fun expandAppArgs(appArgs: List<Arg>, st: State): List<Arg>? {
        val plan = planExpansion(appArgs, st) as? PlanResult.Ok ?: return null
        return commit(plan.plan, appArgs, st)
    }

    // ------------------------------------------------------------------
    // Phase A — pure matching
    // ------------------------------------------------------------------

    private sealed class PlanResult {
        data class Ok(val plan: Plan) : PlanResult()

        /** Not an implicit-builtin call site at all. */
        object NotApplicable : PlanResult()

        /** A real attempt that failed; [reason] names the annotation needed. */
        data class Failed(val calleeName: String, val reason: String) : PlanResult()
    }

    private sealed class ParamPlan {
        /** Use the matched argument's own type id as the FNT parameter type. */
        data class UseArgType(val typeId: String) : ParamPlan()

        /** Instantiate the table shape under the plan's bindings. */
        data class Instantiate(val shape: Sig) : ParamPlan()
    }

    /**
     * A lambda-parameter annotation the commit phase pushes: either into
     * a top-level LAM declaration ([lamId]) or into a nested `(LAM ...)`
     * argument at [argIndex] of the application's value-argument list.
     */
    private data class LamAnnotation(
        val lamId: String?,
        val argIndex: Int?,
        val paramIndex: Int,
        val shape: Sig,
    )

    private data class Plan(
        val calleeName: String,
        val sig: BuiltinSignatures.BuiltinSignature,
        val bindings: Map<String, String>,
        val params: List<ParamPlan>,
        val annotations: List<LamAnnotation>,
    )

    private fun planExpansion(appArgs: List<Arg>, st: State): PlanResult {
        val calleeName = (appArgs.getOrNull(0) as? Arg.Bare)?.text ?: return PlanResult.NotApplicable
        if ('.' !in calleeName) return PlanResult.NotApplicable
        if (st.map().containsKey(calleeName)) return PlanResult.NotApplicable
        val sig = BuiltinSignatures.lookup(calleeName)
            ?: return PlanResult.Failed(
                calleeName,
                BuiltinSignatures.exclusions[calleeName]
                    ?.let { "excluded from the implicit signature table ($it); declare an explicit FNT + FN" }
                    ?: "no implicit signature for '$calleeName'; check the spelling or declare an explicit FNT + FN",
            )
        val argItems = (appArgs.getOrNull(1) as? Arg.Listing)?.items
            ?: return PlanResult.Failed(calleeName, "the application's value-argument list is malformed")
        if (argItems.size != sig.params.size) {
            return PlanResult.Failed(
                calleeName,
                "'$calleeName' takes ${sig.params.size} argument(s); the call supplies ${argItems.size}",
            )
        }

        val bindings = LinkedHashMap<String, String>()
        val plans = arrayOfNulls<ParamPlan>(sig.params.size)
        val annotations = mutableListOf<LamAnnotation>()

        // Pass 1 — var-bearing non-function shapes bind first, so the
        // function-shaped pass sees the bindings (List.Fold's B from the
        // init argument before the fold lambda is examined).
        for ((i, shape) in sig.params.withIndex()) {
            if (shape is Sig.FnOf || !shape.hasVars) continue
            val typeId = argTypeOf(argItems[i], st)
                ?: return PlanResult.Failed(
                    calleeName,
                    "the type of argument ${i + 1} could not be inferred; annotate the argument " +
                        "or declare an explicit FNT + FN",
                )
            if (!matchType(shape, typeId, bindings, st)) {
                return PlanResult.Failed(
                    calleeName,
                    "argument ${i + 1} (type '$typeId') does not match the documented shape $shape " +
                        "or conflicts with a prior type-variable binding",
                )
            }
            plans[i] = ParamPlan.UseArgType(typeId)
        }

        // Pass 2 — function-shaped parameters.
        for ((i, shape) in sig.params.withIndex()) {
            if (shape !is Sig.FnOf) continue
            when (val r = planFnParam(calleeName, shape, argItems[i], i, bindings, annotations, st)) {
                is FnParamResult.Ok -> plans[i] = r.plan
                is FnParamResult.Failed -> return PlanResult.Failed(calleeName, r.reason)
            }
        }

        // Pass 3 — var-free shapes instantiate from the table directly;
        // the argument's own type is not needed (the verifier checks the
        // value flow downstream).
        for ((i, shape) in sig.params.withIndex()) {
            if (plans[i] != null) continue
            plans[i] = ParamPlan.Instantiate(shape)
        }

        // Result groundness: every variable in the result shape must be
        // bound by now — otherwise the instantiation is underdetermined.
        unboundVar(sig.result, bindings)?.let { v ->
            return PlanResult.Failed(
                calleeName,
                "the result type variable '$v' of '$calleeName' is not determined by the " +
                    "argument types; declare an explicit FNT + FN at this call site",
            )
        }
        // Same for every Instantiate plan and scheduled annotation.
        for ((i, plan) in plans.withIndex()) {
            val shape = (plan as? ParamPlan.Instantiate)?.shape ?: continue
            unboundVar(shape, bindings)?.let { v ->
                return PlanResult.Failed(
                    calleeName,
                    "type variable '$v' in parameter ${i + 1} of '$calleeName' is not determined " +
                        "by the argument types; annotate the argument or declare an explicit FNT + FN",
                )
            }
        }

        return PlanResult.Ok(Plan(calleeName, sig, bindings, plans.map { it!! }, annotations))
    }

    private sealed class FnParamResult {
        data class Ok(val plan: ParamPlan) : FnParamResult()
        data class Failed(val reason: String) : FnParamResult()
    }

    private fun planFnParam(
        calleeName: String,
        shape: Sig.FnOf,
        arg: Arg,
        argIndex: Int,
        bindings: MutableMap<String, String>,
        annotations: MutableList<LamAnnotation>,
        st: State,
    ): FnParamResult {
        val map = st.map()
        // Resolve the argument to a callable description.
        var lamDecl: NodeDecl? = null
        var lamId: String? = null
        var nested = false
        var fntId: String? = null

        when (arg) {
            is Arg.Bare -> {
                val resolved = Elaborator.resolveCalleeChain(arg.text, map, HashSet())
                val decl = resolved?.let { map[it] }
                when (decl?.code) {
                    "LAM" -> {
                        lamDecl = decl
                        lamId = decl.id
                    }
                    "FN" -> fntId = (decl.args.getOrNull(1) as? Arg.Bare)?.text
                    "FIX" -> fntId = (decl.args.getOrNull(0) as? Arg.Bare)?.text
                    else -> {
                        // Reserved prelude ForeignNode (e.g. passing `lt` as
                        // a comparator), or a binder whose type is an FNT.
                        val spec = LayerAGrammar.reservedNodes[arg.text]
                        fntId = if (spec?.jsonType == "ForeignNode") {
                            spec.refFields["foreignType"]
                        } else {
                            argTypeOf(arg, st)
                        }
                    }
                }
            }
            is Arg.Nested -> {
                if (arg.code != "LAM") {
                    return FnParamResult.Failed(
                        "argument ${argIndex + 1} of '$calleeName' must be a function value " +
                            "(lambda, foreign node, or fixpoint); got a nested (${arg.code} ...)",
                    )
                }
                val scratch = HashMap<String, NodeDecl>()
                val tmpId = materializeNested(arg, st, scratch)
                lamDecl = scratch.getValue(tmpId)
                nested = true
                // The scratch entries must be visible to typeOf below.
                lamScratch = scratch
            }
            else -> return FnParamResult.Failed(
                "argument ${argIndex + 1} of '$calleeName' must be a function value; " +
                    "got ${arg::class.simpleName}",
            )
        }

        try {
            if (lamDecl == null) {
                val fnt = fntId ?: return FnParamResult.Failed(
                    "argument ${argIndex + 1} of '$calleeName' could not be resolved to a " +
                        "function value; annotate it or declare an explicit FNT + FN",
                )
                val fnShape = asFnShape(fnt, st) ?: return FnParamResult.Failed(
                    "argument ${argIndex + 1} of '$calleeName' has type '$fnt', which is not a " +
                        "FunctionType",
                )
                if (fnShape.params.size != shape.params.size) {
                    return FnParamResult.Failed(
                        "the function argument of '$calleeName' (argument ${argIndex + 1}) takes " +
                            "${fnShape.params.size} parameter(s); the documented shape needs " +
                            "${shape.params.size}",
                    )
                }
                for ((s, id) in shape.params.zip(fnShape.params)) {
                    if (!matchType(s, id, bindings, st)) {
                        return FnParamResult.Failed(
                            "a parameter of the function argument (argument ${argIndex + 1}) does " +
                                "not match the documented shape $s or conflicts with a prior binding",
                        )
                    }
                }
                if (!matchType(shape.result, fnShape.result, bindings, st)) {
                    return FnParamResult.Failed(
                        "the result type of the function argument (argument ${argIndex + 1}) does " +
                            "not match the documented shape ${shape.result} or conflicts with a " +
                            "prior binding",
                    )
                }
                return FnParamResult.Ok(ParamPlan.UseArgType(fnt))
            }

            // Lambda path.
            val entries = (lamDecl.args.getOrNull(0) as? Arg.Listing)?.items
                ?: return FnParamResult.Failed("the lambda argument's parameter list is malformed")
            if (entries.size != shape.params.size) {
                return FnParamResult.Failed(
                    "the lambda argument of '$calleeName' (argument ${argIndex + 1}) declares " +
                        "${entries.size} parameter(s); the documented shape needs ${shape.params.size}",
                )
            }
            val lookup = scratchMap(st)
            for ((j, entry) in entries.withIndex()) {
                val declared = Elaborator.resolveParamType(entry, lookup)
                if (declared != null) {
                    if (!matchType(shape.params[j], declared, bindings, st)) {
                        return FnParamResult.Failed(
                            "lambda parameter ${j + 1} (type '$declared') does not match the " +
                                "documented shape ${shape.params[j]} or conflicts with a prior binding",
                        )
                    }
                    continue
                }
                // Unannotated entry: the signature must determine it.
                val entryName = (entry as? Arg.Bare)?.text
                if (entryName == null || entryName == "_") {
                    return FnParamResult.Failed(
                        "lambda parameter ${j + 1} cannot be annotated automatically; write it " +
                            "as `name:<type>`",
                    )
                }
                unboundVar(shape.params[j], bindings)?.let { v ->
                    return FnParamResult.Failed(
                        "lambda parameter '$entryName' needs a type annotation — type variable " +
                            "'$v' is not determined by the other arguments",
                    )
                }
                annotations += LamAnnotation(
                    lamId = if (nested) null else lamId,
                    argIndex = if (nested) argIndex else null,
                    paramIndex = j,
                    shape = shape.params[j],
                )
            }
            // Lambda result type.
            val bodyArg = lamDecl.args.getOrNull(1)
            val bodyTypeId: String? = when (bodyArg) {
                is Arg.Bare -> Elaborator.typeOfArg(bodyArg.text, lookup, st.prim, HashSet())
                is Arg.Nested -> {
                    val scratch = HashMap<String, NodeDecl>(lamScratch ?: emptyMap())
                    val tmpId = materializeNested(bodyArg, st, scratch)
                    val merged = HashMap(st.map()).apply { putAll(scratch) }
                    Elaborator.typeOfArg(tmpId, merged, st.prim, HashSet())
                }
                else -> null
            }
            if (bodyTypeId != null) {
                if (!matchType(shape.result, bodyTypeId, bindings, st)) {
                    return FnParamResult.Failed(
                        "the lambda's return type ('$bodyTypeId') does not match the documented " +
                            "shape ${shape.result} or conflicts with a prior binding",
                    )
                }
            } else {
                unboundVar(shape.result, bindings)?.let { v ->
                    return FnParamResult.Failed(
                        "the lambda's return type could not be inferred and type variable '$v' " +
                            "is not determined by the other arguments; annotate the lambda or " +
                            "declare an explicit FNT + FN",
                    )
                }
            }
            return FnParamResult.Ok(ParamPlan.Instantiate(shape))
        } finally {
            lamScratch = null
        }
    }

    /**
     * Scratch decls for a nested lambda currently being matched, so the
     * lambda's body/parameter lookups can see its materialized children.
     * Single-threaded pass state; cleared in [planFnParam]'s finally.
     */
    private var lamScratch: Map<String, NodeDecl>? = null

    private fun scratchMap(st: State): Map<String, NodeDecl> {
        val extra = lamScratch ?: return st.map()
        return HashMap(st.map()).apply { putAll(extra) }
    }

    /** First unbound variable in [s] under [bindings], or null when ground. */
    private fun unboundVar(s: Sig, bindings: Map<String, String>): String? = when (s) {
        is Sig.Var -> if (s.name in bindings) null else s.name
        is Sig.Prim, Sig.JsonValue, Sig.MarkdownDoc -> null
        is Sig.ListOf -> unboundVar(s.elem, bindings)
        is Sig.OptionOf -> unboundVar(s.elem, bindings)
        is Sig.FnOf -> s.params.firstNotNullOfOrNull { unboundVar(it, bindings) }
            ?: unboundVar(s.result, bindings)
        is Sig.ProductOf -> s.fields.firstNotNullOfOrNull { unboundVar(it.second, bindings) }
    }

    // ------------------------------------------------------------------
    // Argument typing
    // ------------------------------------------------------------------

    private fun argTypeOf(arg: Arg, st: State): String? = when (arg) {
        is Arg.IntL -> st.prim["Int"]
        is Arg.FloatL -> st.prim["Float"]
        is Arg.BoolL -> st.prim["Bool"]
        is Arg.Str -> st.prim["String"]
        is Arg.Bare -> {
            val map = scratchMap(st)
            val decl = map[arg.text]
            when {
                decl?.code == "PRC" -> (decl.args.getOrNull(1) as? Arg.Bare)?.text
                decl == null -> Elaborator.compactLamParamType(arg.text, map)
                else -> Elaborator.typeOfArg(arg.text, map, st.prim, HashSet())
            }
        }
        is Arg.Nested -> {
            val scratch = HashMap<String, NodeDecl>(lamScratch ?: emptyMap())
            val tmpId = materializeNested(arg, st, scratch)
            val merged = HashMap(st.map()).apply { putAll(scratch) }
            Elaborator.typeOfArg(tmpId, merged, st.prim, HashSet())
        }
        else -> null
    }

    /**
     * Materialize a nested `(CODE args...)` expression as temporary
     * NodeDecls in [scratch] so [Elaborator.typeOfArg] can type it.
     * Scratch ids never reach the emitted document.
     */
    private fun materializeNested(arg: Arg.Nested, st: State, scratch: MutableMap<String, NodeDecl>): String {
        val id = "__bitmp${st.tmpCounter++}"
        val newArgs = arg.args.map { materializeArgTree(it, st, scratch) }
        scratch[id] = NodeDecl(id = id, code = arg.code, args = newArgs, line = 0)
        return id
    }

    private fun materializeArgTree(arg: Arg, st: State, scratch: MutableMap<String, NodeDecl>): Arg =
        when (arg) {
            is Arg.Nested -> Arg.Bare(materializeNested(arg, st, scratch))
            is Arg.Listing -> Arg.Listing(arg.items.map { materializeArgTree(it, st, scratch) })
            else -> arg
        }

    // ------------------------------------------------------------------
    // Shape matching
    // ------------------------------------------------------------------

    private data class FnShape(val params: List<String>, val result: String)

    private fun declOf(id: String, st: State): NodeDecl? = scratchMap(st)[id]

    private fun isPrimOf(typeId: String, kind: String, st: State): Boolean {
        val decl = declOf(typeId, st)
        if (decl != null) {
            return decl.code == "PRM" && (decl.args.getOrNull(0) as? Arg.Bare)?.text == kind
        }
        val spec = LayerAGrammar.reservedNodes[typeId] ?: return false
        return spec.jsonType == "PrimitiveType" && spec.stringFields["kind"] == kind
    }

    /** SCS caseType: a Bare ref, or null/absent for a payload-less case. */
    private fun scsCaseType(scs: NodeDecl): String? = (scs.args.getOrNull(1) as? Arg.Bare)?.text

    private fun scsName(scs: NodeDecl): String? = (scs.args.getOrNull(0) as? Arg.Str)?.value

    private fun sumCases(typeId: String, st: State): List<NodeDecl>? {
        val sum = declOf(typeId, st) ?: return null
        if (sum.code != "SUM") return null
        val refs = (sum.args.getOrNull(0) as? Arg.Listing)?.items ?: return null
        val cases = refs.mapNotNull { (it as? Arg.Bare)?.text?.let { id -> declOf(id, st) } }
        if (cases.size != refs.size || cases.any { it.code != "SCS" }) return null
        return cases
    }

    /** Match the canonical list tower; returns the element type id. */
    private fun asListShape(typeId: String, st: State): String? {
        val rt = declOf(typeId, st) ?: return null
        if (rt.code != "RT") return null
        val sumId = (rt.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
        val cases = sumCases(sumId, st) ?: return null
        if (cases.size != 2) return null
        var elem: String? = null
        var sawNil = false
        for (scs in cases) {
            when (scsName(scs)) {
                "Cons" -> {
                    val prdId = scsCaseType(scs) ?: return null
                    val fields = asProductShape(prdId, st) ?: return null
                    if (fields.size != 2) return null
                    val (headName, headType) = fields[0]
                    val (tailName, tailType) = fields[1]
                    if (headName != "head" || tailName != "tail") return null
                    val tailDecl = declOf(tailType, st)
                    val tailIsSelf = tailDecl?.code == "RS" || tailType == typeId
                    if (!tailIsSelf) return null
                    elem = headType
                }
                "Nil" -> {
                    if (scsCaseType(scs) != null) return null
                    sawNil = true
                }
                else -> return null
            }
        }
        return if (sawNil) elem else null
    }

    /** Match the canonical `Some(T) | None` sum; returns the payload type id. */
    private fun asOptionShape(typeId: String, st: State): String? {
        val cases = sumCases(typeId, st) ?: return null
        if (cases.size != 2) return null
        var payload: String? = null
        var sawNone = false
        for (scs in cases) {
            when (scsName(scs)) {
                "Some" -> payload = scsCaseType(scs) ?: return null
                "None" -> {
                    if (scsCaseType(scs) != null) return null
                    sawNone = true
                }
                else -> return null
            }
        }
        return if (sawNone) payload else null
    }

    /** Match a ProductType; returns ordered (fieldName, fieldTypeId) pairs. */
    private fun asProductShape(typeId: String, st: State): List<Pair<String, String>>? {
        val prd = declOf(typeId, st) ?: return null
        if (prd.code != "PRD") return null
        val refs = (prd.args.getOrNull(0) as? Arg.Listing)?.items ?: return null
        val out = mutableListOf<Pair<String, String>>()
        for (ref in refs) {
            val prfId = (ref as? Arg.Bare)?.text ?: return null
            val prf = declOf(prfId, st) ?: return null
            if (prf.code != "PRF") return null
            val name = (prf.args.getOrNull(0) as? Arg.Str)?.value ?: return null
            val fieldType = (prf.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
            out += name to fieldType
        }
        return out
    }

    /** Match a FunctionType (declared or reserved). */
    private fun asFnShape(typeId: String, st: State): FnShape? {
        val decl = declOf(typeId, st)
        if (decl != null) {
            if (decl.code != "FNT") return null
            val params = (decl.args.getOrNull(0) as? Arg.Listing)?.items
                ?.map { (it as? Arg.Bare)?.text ?: return null }
                ?: return null
            val result = (decl.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
            return FnShape(params, result)
        }
        val spec = LayerAGrammar.reservedNodes[typeId] ?: return null
        if (spec.jsonType != "FunctionType") return null
        val params = spec.refListFields["parameters"] ?: return null
        val result = spec.refFields["result"] ?: return null
        return FnShape(params, result)
    }

    private fun matchType(
        s: Sig,
        typeId: String,
        bindings: MutableMap<String, String>,
        st: State,
    ): Boolean {
        val ok = when (s) {
            is Sig.Var -> {
                val current = bindings[s.name]
                if (current == null) {
                    bindings[s.name] = typeId
                    true
                } else {
                    typeShapeEquals(current, typeId, st)
                }
            }
            is Sig.Prim -> isPrimOf(typeId, s.kind, st)
            is Sig.ListOf -> asListShape(typeId, st)?.let { matchType(s.elem, it, bindings, st) } == true
            is Sig.OptionOf -> asOptionShape(typeId, st)?.let { matchType(s.elem, it, bindings, st) } == true
            is Sig.ProductOf -> {
                val fields = asProductShape(typeId, st)
                fields != null && fields.size == s.fields.size &&
                    s.fields.zip(fields).all { (sf, af) ->
                        sf.first == af.first && matchType(sf.second, af.second, bindings, st)
                    }
            }
            is Sig.FnOf -> {
                val fn = asFnShape(typeId, st)
                fn != null && fn.params.size == s.params.size &&
                    s.params.zip(fn.params).all { (ss, id) -> matchType(ss, id, bindings, st) } &&
                    matchType(s.result, fn.result, bindings, st)
            }
            // The blessed macros are var-free; structural agreement is the
            // verifier's job downstream. Matching only binds variables.
            Sig.JsonValue, Sig.MarkdownDoc -> true
        }
        if (ok && s !is Sig.Var && s !is Sig.JsonValue && s !is Sig.MarkdownDoc) {
            groundKey(s, bindings)?.let { key -> st.instCache.putIfAbsent(key, typeId) }
        }
        return ok
    }

    /** Structural type equality through declared + reserved nodes. */
    private fun typeShapeEquals(a: String, b: String, st: State): Boolean {
        if (a == b) return true
        return shapeString(a, st, mutableSetOf()) == shapeString(b, st, mutableSetOf())
    }

    private fun shapeString(id: String, st: State, visiting: MutableSet<String>): String {
        if (id in visiting) return "@self"
        visiting += id
        try {
            val decl = declOf(id, st)
            if (decl == null) {
                val spec = LayerAGrammar.reservedNodes[id] ?: return "#$id"
                return when (spec.jsonType) {
                    "PrimitiveType" -> "P:${spec.stringFields["kind"]}"
                    "FunctionType" -> {
                        val params = (spec.refListFields["parameters"] ?: emptyList())
                            .joinToString(",") { shapeString(it, st, visiting) }
                        val result = spec.refFields["result"]?.let { shapeString(it, st, visiting) } ?: "?"
                        "F($params)>$result"
                    }
                    else -> "#$id"
                }
            }
            return when (decl.code) {
                "PRM" -> "P:${(decl.args.getOrNull(0) as? Arg.Bare)?.text}"
                "RS" -> "@self"
                "RT" -> "M(" + ((decl.args.getOrNull(0) as? Arg.Bare)?.text
                    ?.let { shapeString(it, st, visiting) } ?: "?") + ")"
                "SUM" -> {
                    val cases = (decl.args.getOrNull(0) as? Arg.Listing)?.items.orEmpty()
                        .joinToString("|") { ref ->
                            val scs = (ref as? Arg.Bare)?.text?.let { declOf(it, st) }
                            if (scs == null || scs.code != "SCS") "?"
                            else (scsName(scs) ?: "?") + ":" +
                                (scsCaseType(scs)?.let { shapeString(it, st, visiting) } ?: "_")
                        }
                    "S($cases)"
                }
                "PRD" -> {
                    val fields = (decl.args.getOrNull(0) as? Arg.Listing)?.items.orEmpty()
                        .joinToString(",") { ref ->
                            val prf = (ref as? Arg.Bare)?.text?.let { declOf(it, st) }
                            if (prf == null || prf.code != "PRF") "?"
                            else ((prf.args.getOrNull(0) as? Arg.Str)?.value ?: "?") + ":" +
                                ((prf.args.getOrNull(1) as? Arg.Bare)?.text
                                    ?.let { shapeString(it, st, visiting) } ?: "?")
                        }
                    "R($fields)"
                }
                "FNT" -> {
                    val params = (decl.args.getOrNull(0) as? Arg.Listing)?.items.orEmpty()
                        .joinToString(",") {
                            (it as? Arg.Bare)?.text?.let { p -> shapeString(p, st, visiting) } ?: "?"
                        }
                    val result = (decl.args.getOrNull(1) as? Arg.Bare)?.text
                        ?.let { shapeString(it, st, visiting) } ?: "?"
                    "F($params)>$result"
                }
                else -> "#$id"
            }
        } finally {
            visiting -= id
        }
    }

    /**
     * The instantiation-cache key for a shape under [bindings] — null
     * when any contained variable is unbound. The same key function
     * drives both match-time recording (shape -> existing type id) and
     * commit-time instantiation, which is what makes the "reuse the
     * user's tower when one matched" rule line up exactly.
     */
    private fun groundKey(s: Sig, bindings: Map<String, String>): String? {
        return when (s) {
            is Sig.Var -> bindings[s.name]?.let { "=$it" }
            is Sig.Prim -> "=prim:${s.kind}"
            is Sig.ListOf -> groundKey(s.elem, bindings)?.let { "L($it)" }
            is Sig.OptionOf -> groundKey(s.elem, bindings)?.let { "O($it)" }
            is Sig.FnOf -> {
                val params = s.params.map { groundKey(it, bindings) ?: return null }
                val result = groundKey(s.result, bindings) ?: return null
                "F(${params.joinToString(",")})>$result"
            }
            is Sig.ProductOf -> {
                val fields = s.fields.map { (n, f) -> "$n:${groundKey(f, bindings) ?: return null}" }
                "R(${fields.joinToString(",")})"
            }
            Sig.JsonValue -> "JSONVALUE"
            Sig.MarkdownDoc -> "MARKDOWNDOC"
        }
    }

    // ------------------------------------------------------------------
    // Phase B — commit (synthesis + rewrite)
    // ------------------------------------------------------------------

    private fun commit(plan: Plan, appArgs: List<Arg>, st: State): List<Arg> {
        // Push lambda annotations first (the instantiated types may reuse
        // towers the matching phase recorded).
        var argItems = (appArgs[1] as Arg.Listing).items
        for (ann in plan.annotations) {
            val typeId = instantiate(ann.shape, plan.bindings, st)
            if (ann.lamId != null) {
                st.pendingLamAnnotations.getOrPut(ann.lamId) { LinkedHashMap() }[ann.paramIndex] = typeId
            } else if (ann.argIndex != null) {
                argItems = argItems.toMutableList().also { items ->
                    val nested = items[ann.argIndex] as Arg.Nested
                    val entries = (nested.args[0] as Arg.Listing).items.toMutableList()
                    val entry = entries[ann.paramIndex] as Arg.Bare
                    entries[ann.paramIndex] = Arg.Bare("${entry.text}:$typeId")
                    items[ann.argIndex] = Arg.Nested(
                        nested.code,
                        listOf(Arg.Listing(entries)) + nested.args.drop(1),
                    )
                }
            }
        }

        val paramTypeIds = plan.params.map { p ->
            when (p) {
                is ParamPlan.UseArgType -> p.typeId
                is ParamPlan.Instantiate -> instantiate(p.shape, plan.bindings, st)
            }
        }
        val resultTypeId = instantiate(plan.sig.result, plan.bindings, st)

        val fnKey = plan.calleeName + "|" + paramTypeIds.joinToString(",") + "|" + resultTypeId
        val fnId = st.fnCache.getOrPut(fnKey) {
            val effectIds = plan.sig.effects.map { spec ->
                spec.reservedId ?: st.efcCache.getOrPut(spec.categoryName!!) {
                    val id = st.freshId("efc")
                    st.addSynth(NodeDecl(id, "EFC", listOf(Arg.Str(spec.categoryName)), 0))
                    id
                }
            }
            val fntId = st.freshId("fnt")
            st.addSynth(NodeDecl(
                id = fntId,
                code = "FNT",
                args = listOf(
                    Arg.Listing(paramTypeIds.map { Arg.Bare(it) }),
                    Arg.Bare(resultTypeId),
                ),
                line = 0,
            ))
            val fnArgs = mutableListOf<Arg>(
                Arg.Str(BuiltinSignatures.targetFor(plan.calleeName)),
                Arg.Bare(fntId),
            )
            val hasProjections = plan.sig.effects.any { it.sources != null }
            if (effectIds.isNotEmpty()) {
                fnArgs += Arg.Listing(effectIds.map { Arg.Bare(it) })
            }
            if (hasProjections) {
                val dsl = EffectProjectionDsl.format(
                    effectIds.zip(plan.sig.effects).map { (catId, spec) ->
                        EffectProjectionDsl.Entry(
                            category = catId,
                            sources = (spec.sources ?: emptyList()).map {
                                EffectProjectionDsl.Source.ArgIndex(it)
                            },
                        )
                    },
                )
                fnArgs += Arg.Str(dsl)
            }
            val id = st.freshId("fn")
            st.addSynth(NodeDecl(id, "FN", fnArgs, 0))
            id
        }

        return listOf(Arg.Bare(fnId), Arg.Listing(argItems)) + appArgs.drop(2)
    }

    private fun instantiate(s: Sig, bindings: Map<String, String>, st: State): String = when (s) {
        is Sig.Var -> bindings.getValue(s.name)
        is Sig.Prim -> st.prim.getValue(s.kind)
        else -> {
            val key = groundKey(s, bindings)
                ?: error("instantiate called on non-ground shape $s (phase A must prevent this)")
            st.instCache.getOrPut(key) { synthesizeShape(s, bindings, st) }
        }
    }

    private fun synthesizeShape(s: Sig, bindings: Map<String, String>, st: State): String = when (s) {
        is Sig.Var, is Sig.Prim -> instantiate(s, bindings, st)
        is Sig.ListOf -> synthListTower(instantiate(s.elem, bindings, st), st)
        is Sig.OptionOf -> synthOption(instantiate(s.elem, bindings, st), st)
        is Sig.ProductOf -> synthProduct(
            s.fields.map { (n, f) -> n to instantiate(f, bindings, st) }, st,
        )
        is Sig.FnOf -> {
            val params = s.params.map { instantiate(it, bindings, st) }
            val result = instantiate(s.result, bindings, st)
            val id = st.freshId("fnt")
            st.addSynth(NodeDecl(
                id, "FNT",
                listOf(Arg.Listing(params.map { Arg.Bare(it) }), Arg.Bare(result)),
                0,
            ))
            id
        }
        Sig.JsonValue -> synthJsonValueTower(st)
        Sig.MarkdownDoc -> synthMarkdownTower(st)
    }

    /** `mu. Cons({head: elem, tail: self}) | Nil` — the canonical list. */
    private fun synthListTower(elemId: String, st: State): String {
        val rs = st.freshId("rs")
        val head = st.freshId("head")
        val tail = st.freshId("tail")
        val prd = st.freshId("prd")
        val cons = st.freshId("cons")
        val nil = st.freshId("nil")
        val sum = st.freshId("sum")
        val rt = st.freshId("rt")
        st.addSynth(NodeDecl(rs, "RS", emptyList(), 0))
        st.addSynth(NodeDecl(head, "PRF", listOf(Arg.Str("head"), Arg.Bare(elemId)), 0))
        st.addSynth(NodeDecl(tail, "PRF", listOf(Arg.Str("tail"), Arg.Bare(rs)), 0))
        st.addSynth(NodeDecl(prd, "PRD", listOf(Arg.Listing(listOf(Arg.Bare(head), Arg.Bare(tail)))), 0))
        st.addSynth(NodeDecl(cons, "SCS", listOf(Arg.Str("Cons"), Arg.Bare(prd)), 0))
        st.addSynth(NodeDecl(nil, "SCS", listOf(Arg.Str("Nil"), Arg.Null), 0))
        st.addSynth(NodeDecl(sum, "SUM", listOf(Arg.Listing(listOf(Arg.Bare(cons), Arg.Bare(nil)))), 0))
        st.addSynth(NodeDecl(rt, "RT", listOf(Arg.Bare(sum)), 0))
        return rt
    }

    /** `Some(elem) | None` — the canonical option sum. */
    private fun synthOption(elemId: String, st: State): String {
        val some = st.freshId("some")
        val none = st.freshId("none")
        val sum = st.freshId("sum")
        st.addSynth(NodeDecl(some, "SCS", listOf(Arg.Str("Some"), Arg.Bare(elemId)), 0))
        st.addSynth(NodeDecl(none, "SCS", listOf(Arg.Str("None"), Arg.Null), 0))
        st.addSynth(NodeDecl(sum, "SUM", listOf(Arg.Listing(listOf(Arg.Bare(some), Arg.Bare(none)))), 0))
        return sum
    }

    private fun synthProduct(fields: List<Pair<String, String>>, st: State): String {
        val prfIds = fields.map { (name, typeId) ->
            val id = st.freshId("prf")
            st.addSynth(NodeDecl(id, "PRF", listOf(Arg.Str(name), Arg.Bare(typeId)), 0))
            id
        }
        val prd = st.freshId("prd")
        st.addSynth(NodeDecl(prd, "PRD", listOf(Arg.Listing(prfIds.map { Arg.Bare(it) })), 0))
        return prd
    }

    /** The blessed corpus-66 JsonValueFull tower (8-case recursive sum). */
    private fun synthJsonValueTower(st: State): String {
        val stringT = st.prim.getValue("String")
        val intT = st.prim.getValue("Int")
        val boolT = st.prim.getValue("Bool")
        val rs = st.freshId("rs")
        st.addSynth(NodeDecl(rs, "RS", emptyList(), 0))
        val arrHead = st.freshId("prf")
        val arrTail = st.freshId("prf")
        val arrProd = st.freshId("prd")
        st.addSynth(NodeDecl(arrHead, "PRF", listOf(Arg.Str("head"), Arg.Bare(rs)), 0))
        st.addSynth(NodeDecl(arrTail, "PRF", listOf(Arg.Str("tail"), Arg.Bare(rs)), 0))
        st.addSynth(NodeDecl(arrProd, "PRD", listOf(Arg.Listing(listOf(Arg.Bare(arrHead), Arg.Bare(arrTail)))), 0))
        val objKey = st.freshId("prf")
        val objValue = st.freshId("prf")
        val objTail = st.freshId("prf")
        val objProd = st.freshId("prd")
        st.addSynth(NodeDecl(objKey, "PRF", listOf(Arg.Str("key"), Arg.Bare(stringT)), 0))
        st.addSynth(NodeDecl(objValue, "PRF", listOf(Arg.Str("value"), Arg.Bare(rs)), 0))
        st.addSynth(NodeDecl(objTail, "PRF", listOf(Arg.Str("tail"), Arg.Bare(rs)), 0))
        st.addSynth(NodeDecl(
            objProd, "PRD",
            listOf(Arg.Listing(listOf(Arg.Bare(objKey), Arg.Bare(objValue), Arg.Bare(objTail)))), 0,
        ))
        val caseDefs = listOf(
            "JsonNull" to null,
            "JsonBool" to boolT,
            "JsonNumber" to intT,
            "JsonString" to stringT,
            "JsonArrayCons" to arrProd,
            "JsonArrayNil" to null,
            "JsonObjectCons" to objProd,
            "JsonObjectNil" to null,
        )
        val caseIds = caseDefs.map { (name, caseType) ->
            val id = st.freshId("scs")
            st.addSynth(NodeDecl(
                id, "SCS",
                listOf(Arg.Str(name), caseType?.let { Arg.Bare(it) } ?: Arg.Null), 0,
            ))
            id
        }
        val sum = st.freshId("sum")
        st.addSynth(NodeDecl(sum, "SUM", listOf(Arg.Listing(caseIds.map { Arg.Bare(it) })), 0))
        val rt = st.freshId("rt")
        st.addSynth(NodeDecl(rt, "RT", listOf(Arg.Bare(sum)), 0))
        return rt
    }

    /** The blessed corpus-61 MarkdownDocument tower (recursive block list). */
    private fun synthMarkdownTower(st: State): String {
        val stringT = st.prim.getValue("String")
        val intT = st.prim.getValue("Int")
        fun prf(name: String, typeId: String): String {
            val id = st.freshId("prf")
            st.addSynth(NodeDecl(id, "PRF", listOf(Arg.Str(name), Arg.Bare(typeId)), 0))
            return id
        }
        fun prd(fields: List<String>): String {
            val id = st.freshId("prd")
            st.addSynth(NodeDecl(id, "PRD", listOf(Arg.Listing(fields.map { Arg.Bare(it) })), 0))
            return id
        }
        fun scs(name: String, caseType: String?): String {
            val id = st.freshId("scs")
            st.addSynth(NodeDecl(
                id, "SCS",
                listOf(Arg.Str(name), caseType?.let { Arg.Bare(it) } ?: Arg.Null), 0,
            ))
            return id
        }
        val headingT = prd(listOf(prf("level", intT), prf("text", stringT)))
        val paragraphT = prd(listOf(prf("text", stringT)))
        val codeBlockT = prd(listOf(prf("language", stringT), prf("code", stringT)))
        val blockSum = st.freshId("sum")
        st.addSynth(NodeDecl(
            blockSum, "SUM",
            listOf(Arg.Listing(listOf(
                Arg.Bare(scs("Heading", headingT)),
                Arg.Bare(scs("Paragraph", paragraphT)),
                Arg.Bare(scs("CodeBlock", codeBlockT)),
                Arg.Bare(scs("HorizontalRule", null)),
            ))), 0,
        ))
        val rs = st.freshId("rs")
        st.addSynth(NodeDecl(rs, "RS", emptyList(), 0))
        val consPrd = prd(listOf(prf("head", blockSum), prf("tail", rs)))
        val docSum = st.freshId("sum")
        st.addSynth(NodeDecl(
            docSum, "SUM",
            listOf(Arg.Listing(listOf(
                Arg.Bare(scs("Cons", consPrd)),
                Arg.Bare(scs("Nil", null)),
            ))), 0,
        ))
        val rt = st.freshId("rt")
        st.addSynth(NodeDecl(rt, "RT", listOf(Arg.Bare(docSum)), 0))
        return rt
    }

    // ------------------------------------------------------------------
    // Lambda annotation application
    // ------------------------------------------------------------------

    private fun applyAnnotations(st: State) {
        for ((lamId, byIndex) in st.pendingLamAnnotations) {
            val lam = st.working[lamId] ?: continue
            if (lam.code != "LAM") continue
            val params = (lam.args.getOrNull(0) as? Arg.Listing)?.items?.toMutableList() ?: continue
            var lamChanged = false
            for ((idx, typeId) in byIndex) {
                val entry = params.getOrNull(idx) as? Arg.Bare ?: continue
                if (':' in entry.text || entry.text == "_") continue
                val prc = st.working[entry.text]
                if (prc != null && prc.code == "PRC") {
                    if (prc.args.size == 1) {
                        st.working[entry.text] = prc.copy(args = prc.args + Arg.Bare(typeId))
                        st.invalidate()
                        st.changed = true
                    }
                } else {
                    params[idx] = Arg.Bare("${entry.text}:$typeId")
                    lamChanged = true
                }
            }
            if (lamChanged) {
                st.working[lamId] = lam.copy(args = listOf(Arg.Listing(params)) + lam.args.drop(1))
                st.invalidate()
                st.changed = true
            }
        }
        st.pendingLamAnnotations.clear()
    }

    // ------------------------------------------------------------------
    // Gap diagnosis
    // ------------------------------------------------------------------

    private fun collectGapsInNode(node: NodeDecl, st: State, gaps: MutableList<ElaborationGap>) {
        if (node.code == "APP") {
            diagnoseAppArgs(node.args, node.id, node.line, st, gaps)
        }
        fun walk(arg: Arg) {
            when (arg) {
                is Arg.Listing -> arg.items.forEach(::walk)
                is Arg.Nested -> {
                    if (arg.code == "APP") diagnoseAppArgs(arg.args, node.id, node.line, st, gaps)
                    arg.args.forEach(::walk)
                }
                else -> {}
            }
        }
        node.args.forEach(::walk)
    }

    private fun diagnoseAppArgs(
        appArgs: List<Arg>,
        nodeId: String,
        line: Int,
        st: State,
        gaps: MutableList<ElaborationGap>,
    ) {
        val calleeName = (appArgs.firstOrNull() as? Arg.Bare)?.text ?: return
        if ('.' !in calleeName) return
        when (val r = planExpansion(appArgs, st)) {
            is PlanResult.Failed -> gaps += ElaborationGap(
                nodeId = nodeId,
                field = "function",
                inferenceCase = "implicit registry-builtin expansion",
                reason = r.reason,
                line = line,
            )
            // Ok here means the fixed point hit its iteration bound before
            // this site was committed — surface it the same way.
            is PlanResult.Ok -> gaps += ElaborationGap(
                nodeId = nodeId,
                field = "function",
                inferenceCase = "implicit registry-builtin expansion",
                reason = "'$calleeName' was resolvable but the elaboration fixed point did not " +
                    "converge; simplify the document or declare an explicit FNT + FN",
                line = line,
            )
            PlanResult.NotApplicable -> {}
        }
    }
}
