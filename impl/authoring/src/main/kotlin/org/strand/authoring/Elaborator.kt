package org.strand.authoring

/**
 * Layer C elaboration — Q-034 step 1 + Layer A density v4.
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
 * **Layer A density v4 (2026-05-25)** adds deeper inference cases so most
 * explicit type declarations can be omitted:
 *
 *  5. **Recursion-slot paramType inference.** The first parameter of a
 *     Fixpoint body Lambda is the recursion slot; its `paramType` MUST
 *     equal the `FIX.recursionType`. The elaborator fills that in
 *     automatically — the user writes `[recurse n:intT]` (no `:factT`
 *     annotation) or `[_ n:intT]` (anonymous slot).
 *
 *  6. **FunctionType synthesis.** A `FIX recursionType` reference to an
 *     undeclared name synthesizes a new `FNT` node with that name as its
 *     id. Parameters are the body Lambda's params (after the recursion
 *     slot) types; result is the body's return type. So `factT` need not
 *     be declared anywhere — the FIX names the slot it wants the FNT to
 *     occupy, and the elaborator fills it in.
 *
 *  7. **SumTypeCase caseType inference.** When every SumValue (`SV`) that
 *     uses a given case supplies a payload of the same inferable type,
 *     and the matching `SCS.caseType` is `_`/absent, the caseType is
 *     inferred. So `someCase SCS "Some" _` becomes valid when callers
 *     write `someValue SV optionT "Some" 42` (payload of inferable
 *     `intT`).
 *
 *  8. **Aggressive paramType inference.** Extends the v1 single-call-site
 *     rule to also cover:
 *      - Nested Application chains (the LAM is wrapped in a LET, called
 *        via VarRef inside a deeper expression).
 *      - Match scrutinee context (a PRC is the scrutinee of a Match;
 *        infer from the first case's `patternType`).
 *      - ProductFieldValue context (a PRC is the value of a `PFV` whose
 *        name matches a `PRF` in the enclosing PV's `ofType`).
 *      - ProductFieldGet target (PFG's target's type contains the field).
 *
 *  9. **Extended `typeOfArg`.** The internal type computation covers:
 *      - Foreign-call result type via `FN.foreignType` → `FNT.result`
 *      - Lambda-call return type via in-document body shape
 *      - Sum/Product construction (SV/PV → their declared `ofType`)
 *      - Match / IF sugar → first case body's type
 *      - Reserved-name lookup for primitives (so `intT` resolves even
 *        when not declared, via Slice 1's implicit prelude)
 *
 * The inference passes run in a fixed-point loop until the document
 * stabilizes — earlier passes (recursion-slot, FNT synthesis) feed later
 * ones (paramType call-site inference) and vice versa. Loop bound: 8
 * iterations (defensive — every realistic document converges in 2-3).
 *
 * The inference algorithm is structural and works directly on the Layer
 * A document (no dag-json round-trip, no verifier reuse). Closure rules
 * for effect inference mirror the verifier's `nodeClosures` computation.
 *
 * The resulting document compiles via [DagJsonEmitter] to canonical
 * dag-json that the verifier accepts. Round-trip integrity with the
 * original canonical JSON IS preserved when the elaborator's inferences
 * produce the same node shapes the user would have written by hand —
 * which is the design goal for density-v4 fixtures.
 *
 * Cases the inference cannot resolve (ambiguous call sites, value-typed
 * expressions outside the supported subset, etc.) are left unchanged for
 * the verifier to surface — the proposal's `ElaborationGap` policy
 * ("emit a structured gap diagnostic; the LLM must annotate"). The gap
 * is implicit in this implementation; a future slice can promote it to
 * a structured diagnostic.
 *
 * **Design boundary.** This is bidirectional inference per Q-034's plan.
 * No unification, no constraint solving, no Hindley-Milner. When a case
 * fundamentally requires unification, the elaborator gives up and falls
 * through to the verifier's explicit-annotation requirement.
 */
object Elaborator {

    private const val FIXED_POINT_MAX_ITERATIONS = 8

    /**
     * Elaborate [doc] by running each inference pass to a fixed point.
     * Each pass is a pure function `(doc) -> doc`. The loop terminates
     * when an iteration produces no change to the node list.
     */
    fun elaborate(doc: LayerADocument): LayerADocument {
        var current = doc
        for (iter in 0 until FIXED_POINT_MAX_ITERATIONS) {
            // v4 passes (run first so they feed v1-v3 passes below)
            val afterRecSlot = inferRecursionSlotParamTypes(current)
            val afterCompactParams = inferCompactLambdaParamTypes(afterRecSlot)
            val afterFntSynthesis = synthesizeFunctionTypes(afterCompactParams)
            val afterScsInference = inferSumCaseTypes(afterFntSynthesis)
            // v1-v3 passes (extended to use the richer typeOfArg)
            val afterAllNodeRewrites = applyNodeRewrites(afterScsInference)
            if (afterAllNodeRewrites == current) return current
            current = afterAllNodeRewrites
        }
        return current
    }

    // ========================================================================
    // v4 — Compact-LAM param type inference (case 8 — extended).
    // ========================================================================

    /**
     * For each LAM PARAM_LIST entry that's a bare name without a `:type`
     * suffix AND doesn't refer to an existing PRC node, try to infer the
     * type and rewrite the entry to `name:inferredType`.
     *
     * Inference sources, in order:
     *  1. The LAM's call sites: the value-arg at the corresponding
     *     position is typed via [typeOfArg].
     *  2. Match scrutinee context: a VarRef to the param is the
     *     scrutinee of a Match; first case's patternType wins.
     *  3. Application-arg context: a VarRef to the param is used as an
     *     argument of an APP whose callee's parameter type at that
     *     position is known (resolved via FN.foreignType.parameters).
     *  4. PFV context: a VarRef to the param is the value of a PFV
     *     whose name matches a PRF in a PV's ofType.
     *
     * Skips entries that are anonymous (`_`), are compact-with-type
     * already (`name:type`), or that reference a separate PRC node.
     */
    private fun inferCompactLambdaParamTypes(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val primTypeIdByKind = buildPrimTypeMap(doc)

        // Build LAM -> list of APPs whose function arg resolves to this LAM.
        val callSitesByLam: Map<String, List<NodeDecl>> = doc.nodes
            .asSequence()
            .filter { it.code == "APP" }
            .mapNotNull { app ->
                val fnId = (app.args.getOrNull(0) as? Arg.Bare)?.text ?: return@mapNotNull null
                val resolved = resolveCalleeChain(fnId, byId, HashSet()) ?: return@mapNotNull null
                resolved to app
            }
            .groupBy({ it.first }, { it.second })

        var changed = false
        val newNodes = doc.nodes.map { node ->
            if (node.code != "LAM") return@map node
            val paramsArg = node.args.getOrNull(0) as? Arg.Listing ?: return@map node
            val paramItems = paramsArg.items.toMutableList()
            var entryChanged = false

            for ((paramIndex, entry) in paramItems.withIndex()) {
                val text = (entry as? Arg.Bare)?.text ?: continue
                // Already typed (compact `name:type`) — leave alone.
                if (':' in text) continue
                // Reference to a separate PRC: leave alone (the PRC's
                // paramType is set by inferLambdaParamTypes via
                // applyNodeRewrites in the outer fixed-point loop).
                val refed = byId[text]
                if (refed != null && refed.code == "PRC") continue
                // Anonymous `_` is rare and the emitter doesn't accept
                // it in a PARAM_LIST entry. Skip.
                if (text == "_") continue

                // Try inference paths in order.
                val inferred = inferTypeForCompactParam(
                    paramName = text,
                    paramIndex = paramIndex,
                    lamId = node.id,
                    nodes = doc.nodes,
                    byId = byId,
                    primTypeIdByKind = primTypeIdByKind,
                    callSitesByLam = callSitesByLam,
                ) ?: continue

                paramItems[paramIndex] = Arg.Bare("$text:$inferred")
                entryChanged = true
                changed = true
            }

            if (entryChanged) {
                val newArgs = node.args.toMutableList()
                newArgs[0] = Arg.Listing(paramItems)
                node.copy(args = newArgs)
            } else node
        }

        return if (changed) doc.copy(nodes = newNodes) else doc
    }

    /**
     * Try every inference path for a compact-LAM param. Returns the
     * type id when one path succeeds unambiguously, else null.
     */
    private fun inferTypeForCompactParam(
        paramName: String,
        paramIndex: Int,
        lamId: String,
        nodes: List<NodeDecl>,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
        callSitesByLam: Map<String, List<NodeDecl>>,
    ): String? {
        // 0. StateMachine transitionFn context. If this LAM is the
        // transitionFn of an SM, the LAM's signature is
        // (state, event) -> (state, outputs). For single-input machines
        // (the common single-stream case), the second param's type is
        // `inputStreams[0].eventType`. The first param's type matches
        // the initialState's type — but that's usually inferable via
        // the body's APP/PV usages, so the SM-driven inference here
        // targets only the event slot for now.
        for (sm in nodes) {
            if (sm.code != "SM") continue
            val transitionFnRef = (sm.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
            if (transitionFnRef != lamId) continue
            val inputStreams = (sm.args.getOrNull(2) as? Arg.Listing)?.items ?: continue
            // paramIndex 0 = State (use initialState type), 1 = Event.
            if (paramIndex == 1 && inputStreams.size == 1) {
                val streamId = (inputStreams[0] as? Arg.Bare)?.text ?: continue
                val stream = byId[streamId] ?: continue
                if (stream.code !in setOf("ESE", "ESI", "ESO")) continue
                val eventTypeId = (stream.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                return eventTypeId
            }
            if (paramIndex == 0) {
                val initialStateArg = sm.args.getOrNull(1) ?: continue
                val initialStateType = when (initialStateArg) {
                    is Arg.Bare -> typeOfArg(initialStateArg.text, byId, primTypeIdByKind, HashSet())
                    is Arg.IntL -> primTypeIdByKind["Int"]
                    is Arg.FloatL -> primTypeIdByKind["Float"]
                    is Arg.BoolL -> primTypeIdByKind["Bool"]
                    is Arg.Str -> primTypeIdByKind["String"]
                    else -> null
                } ?: continue
                return initialStateType
            }
        }
        // 1. Call-site inference.
        val sites = callSitesByLam[lamId] ?: emptyList()
        val callSiteTypes = mutableSetOf<String>()
        for (app in sites) {
            val valueArgIds = (app.args.getOrNull(1) as? Arg.Listing)?.items
                ?.mapNotNull { (it as? Arg.Bare)?.text }
                ?: continue
            val valueArgId = valueArgIds.getOrNull(paramIndex) ?: continue
            val typeId = typeOfArg(valueArgId, byId, primTypeIdByKind, HashSet())
                ?: continue
            callSiteTypes += typeId
        }
        if (callSiteTypes.size == 1) return callSiteTypes.single()

        // 2. Usage-site inference: the param name is itself used as a
        // direct reference in the body (Slice 3 auto-VarRef makes
        // `paramName` legal inside APP args and other value slots).
        // Walk every node, check if it references paramName as a value.
        val usageTypes = mutableSetOf<String>()
        for (node in nodes) {
            // 2a. Param is a value-arg of an APP. The callee's parameter
            // type at that position is what we want.
            if (node.code == "APP") {
                val fnId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                val argList = (node.args.getOrNull(1) as? Arg.Listing)?.items
                    ?: continue
                for ((argPos, argNode) in argList.withIndex()) {
                    val argText = (argNode as? Arg.Bare)?.text ?: continue
                    if (argText != paramName) continue
                    // Look up the callee's parameter type at argPos.
                    val type = paramTypeAt(fnId, argPos, byId) ?: continue
                    usageTypes += type
                }
            }
            // 2b. Param is the scrutinee of a Match.
            if (node.code == "MAT") {
                val scrutId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                if (scrutId != paramName) continue
                // First case's patternType is the scrutinee's type.
                val cases = (node.args.getOrNull(1) as? Arg.Listing)?.items
                    ?: continue
                val firstCaseId = (cases.firstOrNull() as? Arg.Bare)?.text ?: continue
                val mc = byId[firstCaseId] ?: continue
                if (mc.code != "MC") continue
                val patternId = (mc.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                val pattern = byId[patternId] ?: continue
                val patternType = (pattern.args.getOrNull(0) as? Arg.Bare)?.text
                    ?: continue
                usageTypes += patternType
            }
            // 2c. Param is the value of a PFV.
            if (node.code == "PFV") {
                val pfvValue = (node.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
                if (pfvValue != paramName) continue
                val pfvName = (node.args.getOrNull(0) as? Arg.Str)?.value ?: continue
                // Find the PV whose `fields` list includes this PFV.
                for (pv in nodes) {
                    if (pv.code != "PV") continue
                    val fieldList = (pv.args.getOrNull(1) as? Arg.Listing)?.items
                        ?: continue
                    val containsPfv = fieldList.any {
                        (it as? Arg.Bare)?.text == node.id
                    }
                    if (!containsPfv) continue
                    val pvOfTypeId = (pv.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                    val prd = byId[pvOfTypeId] ?: continue
                    if (prd.code != "PRD") continue
                    val prdFields = (prd.args.getOrNull(0) as? Arg.Listing)?.items
                        ?: continue
                    for (prfRef in prdFields) {
                        val prfId = (prfRef as? Arg.Bare)?.text ?: continue
                        val prf = byId[prfId] ?: continue
                        if (prf.code != "PRF") continue
                        val prfName = (prf.args.getOrNull(0) as? Arg.Str)?.value
                            ?: continue
                        if (prfName != pfvName) continue
                        val fieldType = (prf.args.getOrNull(1) as? Arg.Bare)?.text
                            ?: continue
                        usageTypes += fieldType
                    }
                }
            }
            // 2d. Param is the payload of a SV. Look up the SCS for the
            // case and use its caseType.
            if (node.code == "SV") {
                val payload = (node.args.getOrNull(2) as? Arg.Bare)?.text ?: continue
                if (payload != paramName) continue
                val ofTypeId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                val caseName = (node.args.getOrNull(1) as? Arg.Str)?.value ?: continue
                val sumNode = byId[ofTypeId] ?: continue
                if (sumNode.code != "SUM") continue
                val sumCases = (sumNode.args.getOrNull(0) as? Arg.Listing)?.items
                    ?: continue
                for (caseRef in sumCases) {
                    val caseId = (caseRef as? Arg.Bare)?.text ?: continue
                    val scs = byId[caseId] ?: continue
                    if (scs.code != "SCS") continue
                    val scsName = (scs.args.getOrNull(0) as? Arg.Str)?.value
                        ?: continue
                    if (scsName != caseName) continue
                    val caseType = (scs.args.getOrNull(1) as? Arg.Bare)?.text
                        ?: continue
                    usageTypes += caseType
                }
            }
        }
        if (usageTypes.size == 1) return usageTypes.single()

        return null
    }

    /**
     * Look up the parameter type at position [argPos] of the function
     * referenced by [fnId]. Chases VAR/NRF/LET chains to find the
     * actual callable; then reads from its FunctionType:
     *  * `FN.foreignType` → the FNT's parameters[argPos]
     *  * `LAM` → the LAM's PARAM_LIST entry at argPos's type (compact
     *    or via separate PRC.paramType)
     *  * `FIX` → `FIX.recursionType.parameters[argPos]`
     *
     * v4 extension: also resolves reserved-name builtins (`not`, `add`,
     * etc.) through the implicit-prelude table when [fnId] isn't
     * declared locally. This lets call-site inference fire on builtins
     * the user references without explicit FN declarations.
     */
    private fun paramTypeAt(
        fnId: String,
        argPos: Int,
        byId: Map<String, NodeDecl>,
    ): String? {
        // First try the document graph.
        val resolved = resolveCalleeChain(fnId, byId, HashSet())
        if (resolved != null) {
            val callable = byId[resolved] ?: return null
            return when (callable.code) {
                "FN" -> {
                    val fntId = (callable.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                    fntParam(fntId, argPos, byId)
                }
                "LAM" -> {
                    val params = (callable.args.getOrNull(0) as? Arg.Listing)?.items
                        ?: return null
                    val entry = params.getOrNull(argPos) ?: return null
                    resolveParamType(entry, byId)
                }
                "FIX" -> {
                    val recTypeId = (callable.args.getOrNull(0) as? Arg.Bare)?.text
                        ?: return null
                    fntParam(recTypeId, argPos, byId)
                }
                else -> null
            }
        }
        // Reserved-name fallback: `not`, `add`, etc. The reserved spec
        // for an FN-class entry has a refFields["foreignType"] -> FNT
        // reserved id, and that FNT's reserved spec has
        // refListFields["parameters"] -> [typeRef ...].
        return reservedFnParamAt(fnId, argPos)
    }

    /** Look up an FNT node's `parameters[argPos]`. */
    private fun fntParam(
        fntId: String,
        argPos: Int,
        byId: Map<String, NodeDecl>,
    ): String? {
        val fnt = byId[fntId] ?: return null
        if (fnt.code != "FNT") return null
        val params = (fnt.args.getOrNull(0) as? Arg.Listing)?.items ?: return null
        val ref = params.getOrNull(argPos) ?: return null
        return (ref as? Arg.Bare)?.text
    }

    /**
     * Resolve a reserved-name FN's parameter type at [argPos]. Used as
     * a fallback when the document doesn't declare the callee — the
     * implicit prelude provides the shape.
     */
    private fun reservedFnParamAt(fnId: String, argPos: Int): String? {
        val fnSpec = LayerAGrammar.reservedNodes[fnId] ?: return null
        if (fnSpec.jsonType != "ForeignNode") return null
        val fntId = fnSpec.refFields["foreignType"] ?: return null
        val fntSpec = LayerAGrammar.reservedNodes[fntId] ?: return null
        if (fntSpec.jsonType != "FunctionType") return null
        val params = fntSpec.refListFields["parameters"] ?: return null
        return params.getOrNull(argPos)
    }

    /**
     * Resolve a reserved-name FN's result type. Used by [typeOfArg]
     * when the document references a builtin without declaring it.
     */
    private fun reservedFnResult(fnId: String): String? {
        val fnSpec = LayerAGrammar.reservedNodes[fnId] ?: return null
        if (fnSpec.jsonType != "ForeignNode") return null
        val fntId = fnSpec.refFields["foreignType"] ?: return null
        val fntSpec = LayerAGrammar.reservedNodes[fntId] ?: return null
        if (fntSpec.jsonType != "FunctionType") return null
        return fntSpec.refFields["result"]
    }

    /**
     * Run the per-node rewrite passes (LAM effects, APP typeArguments +
     * effectInstances, PRC paramType inference) in a single document
     * walk. Returns a document that has been further elaborated; the
     * outer fixed-point loop calls this repeatedly so call-site
     * inferences from later passes can feed earlier ones on the next
     * iteration.
     */
    private fun applyNodeRewrites(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val effectDeclsByCategory: Map<String, List<String>> =
            doc.nodes.asSequence()
                .filter { it.code == "EFD" }
                .mapNotNull { effectDecl ->
                    val categoryArg = effectDecl.args.getOrNull(0) as? Arg.Bare ?: return@mapNotNull null
                    categoryArg.text to effectDecl.id
                }
                .groupBy({ it.first }, { it.second })

        // Index primitive types: explicit PRM declarations win; reserved
        // names (Slice 1 implicit prelude) provide an implicit fallback.
        val primTypeIdByKind: Map<String, String> = run {
            val declared = doc.nodes.asSequence()
                .filter { it.code == "PRM" }
                .mapNotNull { prm ->
                    val kindArg = prm.args.getOrNull(0) as? Arg.Bare ?: return@mapNotNull null
                    kindArg.text to prm.id
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.first() }
            // Layer down a reserved-name fallback for every kind that
            // has no explicit declaration. The reserved-name table in
            // [LayerAGrammar] maps each primitive `Int`/`Bool`/etc. to a
            // reserved id (`intT`, `boolT`, ...).
            val fallback = mapOf(
                "Int" to "intT", "Float" to "floatT", "String" to "stringT",
                "Bool" to "boolT", "Unit" to "unitT", "Bytes" to "bytesT",
            )
            fallback + declared
        }

        // Layer C case (1, extended) — Lambda.paramType inference.
        val paramTypeInferences: Map<String, String> =
            inferLambdaParamTypes(doc.nodes, byId, primTypeIdByKind)

        val newNodes = doc.nodes.map { node ->
            when (node.code) {
                "LAM" -> elaborateLambda(node, byId)
                "APP" -> elaborateApplication(node, byId, effectDeclsByCategory, primTypeIdByKind)
                "PRC" -> elaboratePrc(node, paramTypeInferences)
                else -> node
            }
        }
        return doc.copy(nodes = newNodes)
    }

    // ========================================================================
    // v4 — Recursion-slot paramType inference (case 5).
    // ========================================================================

    /**
     * For each `FIX` node, find the body Lambda's first parameter and,
     * if it lacks a paramType, set it to `FIX.recursionType`.
     *
     * Two parameter forms are handled:
     *  * **Separate PRC.** The LAM's first param is a bare reference to
     *    a `PRC` declaration without `paramType` (args.size == 1). The
     *    PRC is rewritten to include the inferred paramType.
     *  * **Compact LAM param.** The LAM's first param is a compact
     *    `name` entry (no `:type` suffix). The LAM's PARAM_LIST entry is
     *    rewritten from `Bare("name")` to `Bare("name:<recursionType>")`,
     *    so the emitter synthesizes the right PRC at emit time.
     *
     * Anonymous slots written as `_` are also accepted in the compact
     * form (e.g., `[_ n:intT]`). The emitter mints a synthetic name; the
     * Elaborator just sets the type on the entry.
     */
    private fun inferRecursionSlotParamTypes(doc: LayerADocument): LayerADocument {
        // Build a LAM → its FIX's recursionType id map. For each FIX,
        // record (body LAM id, recursionType id).
        val recTypeByLamId: Map<String, String> = doc.nodes.asSequence()
            .filter { it.code == "FIX" }
            .mapNotNull { fix ->
                val recType = (fix.args.getOrNull(0) as? Arg.Bare)?.text ?: return@mapNotNull null
                val body = (fix.args.getOrNull(1) as? Arg.Bare)?.text ?: return@mapNotNull null
                body to recType
            }
            .toMap()
        if (recTypeByLamId.isEmpty()) return doc

        val byId = doc.nodes.associateBy { it.id }

        // Step 1: rewrite compact-LAM params (in-place on LAM nodes).
        // Also collect any separate PRC ids that need rewriting.
        val prcRewrites = mutableMapOf<String, String>()  // PRC id -> recType
        val newNodes = doc.nodes.map { node ->
            if (node.code != "LAM") return@map node
            val recType = recTypeByLamId[node.id] ?: return@map node
            val paramsArg = node.args.getOrNull(0) as? Arg.Listing ?: return@map node
            val firstEntry = paramsArg.items.firstOrNull() as? Arg.Bare ?: return@map node

            if (':' in firstEntry.text) {
                // Compact form WITH type — user explicitly annotated.
                // Leave alone (overrides inference).
                return@map node
            }

            val nameOrUnderscore = firstEntry.text
            if (nameOrUnderscore == "_") {
                // Anonymous compact slot — DagJsonEmitter doesn't accept
                // `_` here (compact entries must have a name). This is a
                // syntactic gap — the user wrote `[_ ...]` but the emitter
                // expects `[name ...]` or `[name:type ...]`. Skip.
                return@map node
            }

            // Two cases:
            //  (a) The compact entry has no colon — bare name like
            //      `recurse`. There's no separate PRC. Rewrite to
            //      `recurse:<recType>`.
            //  (b) The compact entry IS a reference to a separate PRC
            //      declaration with id == this name. Mark the PRC for
            //      rewrite (below) and leave the LAM entry untouched.
            val separatePrc = byId[nameOrUnderscore]
            if (separatePrc != null && separatePrc.code == "PRC") {
                if (separatePrc.args.size == 1) {
                    // PRC has only `name`, needs paramType. Schedule rewrite.
                    prcRewrites[separatePrc.id] = recType
                }
                return@map node  // LAM entry stays as bare ref to PRC.
            }

            // No separate PRC — must be a compact-form entry. Rewrite
            // to `name:recType`.
            val newItems = paramsArg.items.toMutableList()
            newItems[0] = Arg.Bare("$nameOrUnderscore:$recType")
            val newArgs = node.args.toMutableList()
            newArgs[0] = Arg.Listing(newItems)
            node.copy(args = newArgs)
        }

        // Step 2: apply collected PRC rewrites.
        return if (prcRewrites.isEmpty()) {
            LayerADocument(doc.version, doc.rootId, newNodes)
        } else {
            LayerADocument(
                doc.version, doc.rootId,
                newNodes.map { n ->
                    if (n.code == "PRC" && n.id in prcRewrites) {
                        // PRC had args.size == 1 (name only); append paramType.
                        n.copy(args = n.args + Arg.Bare(prcRewrites.getValue(n.id)))
                    } else n
                },
            )
        }
    }

    // ========================================================================
    // v4 — FunctionType synthesis (case 6).
    // ========================================================================

    /**
     * For each `FIX` node whose `recursionType` references an
     * undeclared name, synthesize a new `FNT` node with that name as id.
     *
     * Parameters: the body LAM's params (after the recursion slot)
     * resolved to type ids via [resolveParamType]. Falls through if any
     * param's type can't be resolved.
     *
     * Result: the body LAM body's return type via [typeOfArg]. Falls
     * through if the body's return type can't be resolved.
     */
    private fun synthesizeFunctionTypes(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val primTypeIdByKind = buildPrimTypeMap(doc)
        val synthesized = mutableListOf<NodeDecl>()
        val synthesizedIds = mutableSetOf<String>()

        for (fix in doc.nodes.filter { it.code == "FIX" }) {
            val recTypeRef = (fix.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
            val bodyRef = (fix.args.getOrNull(1) as? Arg.Bare)?.text ?: continue

            // Skip if recType already exists in the document or has been
            // synthesized in this pass.
            if (recTypeRef in byId) continue
            if (recTypeRef in synthesizedIds) continue

            // The body Lambda holds the signature. Walk its PARAM_LIST
            // skipping slot 0 (recursion slot); collect param types.
            val lam = byId[bodyRef] ?: continue
            if (lam.code != "LAM") continue
            val paramsArg = lam.args.getOrNull(0) as? Arg.Listing ?: continue
            val paramEntries = paramsArg.items.drop(1)

            val paramTypes: List<String?> = paramEntries.map { entry ->
                resolveParamType(entry, byId)
            }
            if (paramTypes.any { it == null }) continue
            val paramTypeIds = paramTypes.filterNotNull()

            // Body return type.
            val bodyExprId = (lam.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
            val resultTypeId = typeOfArg(bodyExprId, byId, primTypeIdByKind, HashSet())
                ?: continue

            // Synthesize a new FNT NodeDecl with id = recTypeRef.
            // line=0 is fine — synthesized nodes have no source line.
            synthesized += NodeDecl(
                id = recTypeRef,
                code = "FNT",
                args = listOf(
                    Arg.Listing(paramTypeIds.map { Arg.Bare(it) }),
                    Arg.Bare(resultTypeId),
                ),
                line = 0,
            )
            synthesizedIds += recTypeRef
        }

        if (synthesized.isEmpty()) return doc
        // Insert synthesized nodes at the START so they appear before
        // their references. (Layer A allows forward refs, but stable
        // ordering helps debugging.)
        return LayerADocument(doc.version, doc.rootId, synthesized + doc.nodes)
    }

    /**
     * Resolve a PARAM_LIST entry to its type id. Two shapes:
     *  * `name:typeRef` — split on `:`, return `typeRef`.
     *  * `name` — look up the separate PRC declaration with id == name;
     *    return its `paramType` arg.
     * Returns null on shapes the elaborator can't resolve.
     */
    private fun resolveParamType(entry: Arg, byId: Map<String, NodeDecl>): String? {
        val text = (entry as? Arg.Bare)?.text ?: return null
        if (':' in text) {
            return text.substringAfter(':').takeIf { it.isNotEmpty() }
        }
        val prc = byId[text] ?: return null
        if (prc.code != "PRC") return null
        return (prc.args.getOrNull(1) as? Arg.Bare)?.text
    }

    // ========================================================================
    // v4 — SumTypeCase caseType inference (case 7).
    // ========================================================================

    /**
     * For each `SCS` (SumTypeCase) node whose `caseType` is `Null`
     * (omitted with `_`), scan the document for `SV` (SumValue) nodes
     * that supply a payload for this case. If the payload's type is
     * inferable AND consistent across every usage, set the SCS's
     * caseType to that type.
     *
     * Algorithm:
     *  1. Find every SCS whose `args[1] == Arg.Null` (caseType absent).
     *  2. Find every SV node that references this SCS via its
     *     `ofType` + `caseName`. The SV's `caseName` must match the
     *     SCS's `name`, and the SV's `ofType` must reference a SUM that
     *     lists this SCS in its `cases`.
     *  3. For each such SV, compute the payload's type via [typeOfArg].
     *     If exactly one type appears across all usages, set caseType.
     *  4. If zero usages or any has an unresolvable payload, leave
     *     unchanged (gap policy).
     */
    private fun inferSumCaseTypes(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val primTypeIdByKind = buildPrimTypeMap(doc)

        // Build: SCS id -> list of inferred types from SV payloads.
        // For each SV, the SCS we update is the one whose name == sv.caseName
        // AND that's part of sv.ofType's case list.
        val typesBySCS = mutableMapOf<String, MutableList<String>>()
        for (sv in doc.nodes.filter { it.code == "SV" }) {
            val ofTypeId = (sv.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
            val caseName = (sv.args.getOrNull(1) as? Arg.Str)?.value ?: continue
            val payloadArg = sv.args.getOrNull(2) ?: continue
            // No payload: Arg.Null — the case is payload-less. Skip.
            if (payloadArg is Arg.Null) continue
            // Resolve the payload's type. Accepts either a bare-ref
            // payload (recurse via typeOfArg) or an inline literal
            // (resolved directly).
            val payloadType: String = when (payloadArg) {
                is Arg.Bare -> typeOfArg(payloadArg.text, byId, primTypeIdByKind, HashSet())
                    ?: continue
                is Arg.IntL -> primTypeIdByKind["Int"] ?: continue
                is Arg.FloatL -> primTypeIdByKind["Float"] ?: continue
                is Arg.BoolL -> primTypeIdByKind["Bool"] ?: continue
                is Arg.Str -> primTypeIdByKind["String"] ?: continue
                else -> continue
            }

            val sumNode = byId[ofTypeId] ?: continue
            if (sumNode.code != "SUM") continue
            val sumCases = (sumNode.args.getOrNull(0) as? Arg.Listing)?.items ?: continue
            for (caseRef in sumCases) {
                val caseId = (caseRef as? Arg.Bare)?.text ?: continue
                val scs = byId[caseId] ?: continue
                if (scs.code != "SCS") continue
                val scsName = (scs.args.getOrNull(0) as? Arg.Str)?.value ?: continue
                if (scsName != caseName) continue
                // Skip if caseType is already declared as a bare ref
                // (a real type ref). Arg.Null = no type yet → infer.
                val caseTypeArg = scs.args.getOrNull(1)
                if (caseTypeArg is Arg.Bare) break  // already typed
                typesBySCS.getOrPut(caseId) { mutableListOf() } += payloadType
                break
            }
        }

        if (typesBySCS.isEmpty()) return doc

        // Resolve types for each SCS's payloads. Use the unique type
        // across usages; if any disagrees, fall through.
        val scsTypeInferences = mutableMapOf<String, String>()
        for ((scsId, types) in typesBySCS) {
            val unique = types.toSet()
            if (unique.size == 1) {
                scsTypeInferences[scsId] = unique.single()
            }
        }

        if (scsTypeInferences.isEmpty()) return doc

        return doc.copy(nodes = doc.nodes.map { node ->
            if (node.code != "SCS" || node.id !in scsTypeInferences) return@map node
            val inferred = scsTypeInferences.getValue(node.id)
            // SCS args[1] is currently Arg.Null. Replace it with the
            // inferred type reference.
            val newArgs = node.args.toMutableList()
            if (newArgs.size < 2) {
                newArgs += Arg.Bare(inferred)
            } else {
                newArgs[1] = Arg.Bare(inferred)
            }
            node.copy(args = newArgs)
        })
    }

    // ========================================================================
    // v1-v3 — paramType inference (case 1, extended to v4 case 8).
    // ========================================================================

    /**
     * Layer C case (1, extended for v4): fill in `Lambda.paramType` from
     * call-site context, Match scrutinee context, and ProductFieldValue
     * context.
     *
     * Returns a PRC author-id → inferred paramType author-id map for
     * every PRC whose paramType could be inferred.
     */
    private fun inferLambdaParamTypes(
        nodes: List<NodeDecl>,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Index APP call sites by the function they call.
        val callSitesByCallee: Map<String, List<NodeDecl>> = nodes
            .asSequence()
            .filter { it.code == "APP" }
            .mapNotNull { app ->
                val fnId = (app.args.getOrNull(0) as? Arg.Bare)?.text ?: return@mapNotNull null
                fnId to app
            }
            .groupBy({ it.first }, { it.second })

        // Index MAT scrutinees → MAT nodes for Match-scrutinee-based inference.
        val matchesByScrutinee: Map<String, List<NodeDecl>> = nodes
            .asSequence()
            .filter { it.code == "MAT" }
            .mapNotNull { mat ->
                val scrutId = (mat.args.getOrNull(0) as? Arg.Bare)?.text ?: return@mapNotNull null
                scrutId to mat
            }
            .groupBy({ it.first }, { it.second })

        // Index VAR nodes by their binder. A VAR pointing at a PRC is
        // the typical use; the VAR's "use site" is its parent (an APP
        // arg, a MAT scrutinee, a PFV value, ...).
        val varsByBinder: Map<String, List<NodeDecl>> = nodes
            .asSequence()
            .filter { it.code == "VAR" }
            .mapNotNull { v ->
                val binderId = (v.args.getOrNull(0) as? Arg.Bare)?.text ?: return@mapNotNull null
                binderId to v
            }
            .groupBy({ it.first }, { it.second })

        // For each PRC, try every inference path. Result is the first
        // successful one — they shouldn't disagree on a well-formed doc,
        // but if they do, the earliest non-empty wins (we don't try to
        // reconcile).
        for (prc in nodes.filter { it.code == "PRC" }) {
            if (prc.args.size >= 2) continue  // already has paramType

            // Find the LAM that lists this PRC. Two forms:
            //  (a) separate PRC: the LAM's PARAM_LIST entry is `Bare(prcId)`.
            //  (b) compact form: the entry is `Bare("name:type")`. Not
            //      a PRC node by our reckoning — covered separately.
            // Here we handle (a).
            val (lam, paramIndex) = findOwningLambda(prc.id, nodes) ?: continue

            // 1. APP call-site inference (the existing v1 path).
            val sites = callSitesByCallee[lam.id] ?: emptyList()
            val callSiteTypes = mutableSetOf<String>()
            for (app in sites) {
                val valueArgIds = (app.args.getOrNull(1) as? Arg.Listing)?.items
                    ?.mapNotNull { (it as? Arg.Bare)?.text }
                    ?: continue
                val valueArgId = valueArgIds.getOrNull(paramIndex) ?: continue
                val typeId = typeOfArg(valueArgId, byId, primTypeIdByKind, HashSet())
                    ?: continue
                callSiteTypes += typeId
            }
            if (callSiteTypes.size == 1) {
                result[prc.id] = callSiteTypes.single()
                continue
            }

            // 2. Transitive call-site inference: the LAM is referenced
            // by name (perhaps via VAR or NRF or as a LET value), and
            // the wrapper is called.
            val transitiveTypes = inferViaTransitiveCallSites(
                lamId = lam.id,
                paramIndex = paramIndex,
                nodes = nodes,
                byId = byId,
                primTypeIdByKind = primTypeIdByKind,
            )
            if (transitiveTypes.size == 1) {
                result[prc.id] = transitiveTypes.single()
                continue
            }

            // 3. Match scrutinee inference: the PRC is the scrutinee of
            // a MAT (directly or via a VAR). The first case's
            // patternType is the PRC's type.
            val prcVars = varsByBinder[prc.id] ?: emptyList()
            val matchScrutTypes = mutableSetOf<String>()
            for (variable in prcVars) {
                val matches = matchesByScrutinee[variable.id] ?: continue
                for (mat in matches) {
                    val caseList = (mat.args.getOrNull(1) as? Arg.Listing)?.items
                        ?: continue
                    val firstCaseId = (caseList.firstOrNull() as? Arg.Bare)?.text
                        ?: continue
                    val mc = byId[firstCaseId] ?: continue
                    if (mc.code != "MC") continue
                    val patternId = (mc.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                    val pattern = byId[patternId] ?: continue
                    val patternType = (pattern.args.getOrNull(0) as? Arg.Bare)?.text
                        ?: continue
                    matchScrutTypes += patternType
                }
            }
            if (matchScrutTypes.size == 1) {
                result[prc.id] = matchScrutTypes.single()
                continue
            }

            // 4. ProductFieldValue context: the PRC is referenced as
            // the value of a PFV; the PFV's name matches a PRF in the
            // enclosing PV's ofType.
            val pfvTypes = inferViaPfvContext(
                prcId = prc.id,
                varsByBinder = varsByBinder,
                nodes = nodes,
                byId = byId,
            )
            if (pfvTypes.size == 1) {
                result[prc.id] = pfvTypes.single()
                continue
            }
        }
        return result
    }

    /** Find the LAM that owns a separate-PRC param. Returns (lam, paramIndex). */
    private fun findOwningLambda(
        prcId: String,
        nodes: List<NodeDecl>,
    ): Pair<NodeDecl, Int>? {
        for (lam in nodes.filter { it.code == "LAM" }) {
            val paramsArg = lam.args.getOrNull(0) as? Arg.Listing ?: continue
            for ((idx, entry) in paramsArg.items.withIndex()) {
                val text = (entry as? Arg.Bare)?.text ?: continue
                if (text == prcId) return lam to idx
                if (':' in text && text.substringBefore(':') == prcId) return lam to idx
            }
        }
        return null
    }

    /**
     * v4 case 8: inference through LET/VAR/NRF chains. The LAM (lamId)
     * may be wrapped in a LET (e.g., `let f = lam; f(x)`); a call site
     * `f(x)` looks up the binder via VAR. We chase those chains by
     * walking every APP whose function resolves (via [resolveCalleeChain])
     * to the target LAM.
     */
    private fun inferViaTransitiveCallSites(
        lamId: String,
        paramIndex: Int,
        nodes: List<NodeDecl>,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
    ): Set<String> {
        val out = mutableSetOf<String>()
        for (app in nodes.filter { it.code == "APP" }) {
            val fnId = (app.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
            val resolved = resolveCalleeChain(fnId, byId, HashSet()) ?: continue
            if (resolved != lamId) continue
            val valueArgIds = (app.args.getOrNull(1) as? Arg.Listing)?.items
                ?.mapNotNull { (it as? Arg.Bare)?.text }
                ?: continue
            val valueArgId = valueArgIds.getOrNull(paramIndex) ?: continue
            val typeId = typeOfArg(valueArgId, byId, primTypeIdByKind, HashSet())
                ?: continue
            out += typeId
        }
        return out
    }

    /**
     * Resolve [nodeId] through VAR/LET/NRF chains until a LAM (or other
     * terminal) is reached. Used by [inferViaTransitiveCallSites] to
     * match call sites that go through wrappers.
     */
    private fun resolveCalleeChain(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): String? {
        if (!visited.add(nodeId)) return null
        val node = byId[nodeId] ?: return null
        return when (node.code) {
            "LAM", "FN", "FIX", "TAB" -> nodeId
            "VAR" -> {
                val binderId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                resolveCalleeChain(binderId, byId, visited)
            }
            "NRF" -> {
                val targetId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                resolveCalleeChain(targetId, byId, visited)
            }
            "LET" -> {
                val valueId = (node.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                resolveCalleeChain(valueId, byId, visited)
            }
            else -> null
        }
    }

    /**
     * v4 case 8: infer a PRC's paramType from ProductFieldValue context.
     * If the PRC's VarRef appears as the `value` of a PFV whose name N
     * matches a PRF "N" in the enclosing PV's `ofType`, the PRC's type
     * is that PRF's `fieldType`.
     */
    private fun inferViaPfvContext(
        prcId: String,
        varsByBinder: Map<String, List<NodeDecl>>,
        nodes: List<NodeDecl>,
        byId: Map<String, NodeDecl>,
    ): Set<String> {
        val out = mutableSetOf<String>()
        val prcVars = varsByBinder[prcId] ?: return out
        val varIds = prcVars.map { it.id }.toSet()
        // Each VAR may be used as a PFV's value.
        for (pfv in nodes.filter { it.code == "PFV" }) {
            val pfvName = (pfv.args.getOrNull(0) as? Arg.Str)?.value ?: continue
            val pfvValueId = (pfv.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
            if (pfvValueId !in varIds) continue
            // Find the PV whose `fields` list references this PFV.
            for (pv in nodes.filter { it.code == "PV" }) {
                val ofTypeId = (pv.args.getOrNull(0) as? Arg.Bare)?.text ?: continue
                val fields = (pv.args.getOrNull(1) as? Arg.Listing)?.items ?: continue
                val pvHasPfv = fields.any {
                    (it as? Arg.Bare)?.text == pfv.id
                }
                if (!pvHasPfv) continue
                // Look up the PRD ofType, find the PRF named pfvName.
                val prd = byId[ofTypeId] ?: continue
                if (prd.code != "PRD") continue
                val prdFields = (prd.args.getOrNull(0) as? Arg.Listing)?.items ?: continue
                for (prfRef in prdFields) {
                    val prfId = (prfRef as? Arg.Bare)?.text ?: continue
                    val prf = byId[prfId] ?: continue
                    if (prf.code != "PRF") continue
                    val prfName = (prf.args.getOrNull(0) as? Arg.Str)?.value ?: continue
                    if (prfName != pfvName) continue
                    val fieldType = (prf.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
                    out += fieldType
                }
            }
        }
        return out
    }

    /**
     * If [node] is a PRC whose paramType was inferred, synthesize the
     * paramType arg so the emitted JSON has it. Otherwise return [node]
     * unchanged.
     */
    private fun elaboratePrc(
        node: NodeDecl,
        paramTypeInferences: Map<String, String>,
    ): NodeDecl {
        if (node.args.size >= 2) return node  // already has paramType
        val inferred = paramTypeInferences[node.id] ?: return node
        return node.copy(args = node.args + Arg.Bare(inferred))
    }

    // ========================================================================
    // v1 — Lambda effects inference (case 3).
    // ========================================================================

    /**
     * Fill in `Lambda.effects` when absent and the body's closure is
     * non-empty. See class doc, case (3).
     */
    private fun elaborateLambda(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
    ): NodeDecl {
        if (node.args.size != 2) return node
        val bodyArg = node.args[1] as? Arg.Bare ?: return node  // malformed
        val bodyClosure = closureOf(bodyArg.text, byId, HashSet())
        if (bodyClosure.isEmpty()) return node
        val effectsList = Arg.Listing(
            bodyClosure.toList().sorted().map { Arg.Bare(it) }
        )
        return node.copy(args = node.args + effectsList)
    }

    // ========================================================================
    // v1 — Application elaboration (cases 2 + 4).
    // ========================================================================

    /**
     * Default `Application.effectInstances` when absent and the callee
     * declares non-empty effects. Also runs typeArguments inference.
     */
    private fun elaborateApplication(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        effectDeclsByCategory: Map<String, List<String>>,
        primTypeIdByKind: Map<String, String>,
    ): NodeDecl {
        // APP positional schema: function, arguments, [typeArguments], [effectInstances].
        if (node.args.size !in 2..3) return node  // already has effectInstances or malformed
        val fnId = (node.args[0] as? Arg.Bare)?.text ?: return node  // malformed

        // Step 1: typeArguments inference.
        val withTypeArgs: NodeDecl = if (node.args.size == 2) {
            tryInferTypeArguments(node, fnId, byId, primTypeIdByKind) ?: node
        } else {
            node
        }
        val sizedNode = withTypeArgs
        val sizedArgs = sizedNode.args
        if (sizedArgs.size !in 2..3) return sizedNode

        // Step 2: effectInstances defaulting.
        val calleeEffects = effectsOfOrdered(fnId, byId, HashSet())
        if (calleeEffects.isEmpty()) return sizedNode

        val matched = mutableListOf<String>()
        for (effectCategoryId in calleeEffects) {
            val candidates = effectDeclsByCategory[effectCategoryId] ?: return sizedNode
            if (candidates.size != 1) return sizedNode
            matched += candidates[0]
        }

        val newArgs = if (sizedArgs.size == 2) {
            sizedArgs + Arg.Listing(emptyList()) + Arg.Listing(matched.map { Arg.Bare(it) })
        } else {
            sizedArgs + Arg.Listing(matched.map { Arg.Bare(it) })
        }
        return sizedNode.copy(args = newArgs)
    }

    /** Layer C case (2): infer `Application.typeArguments` from value-arg types. */
    private fun tryInferTypeArguments(
        node: NodeDecl,
        fnId: String,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
    ): NodeDecl? {
        val tab = resolveToTab(fnId, byId) ?: return null
        if (tab.args.size != 2) return null
        val tpmIds = (tab.args[0] as? Arg.Listing)?.items
            ?.mapNotNull { (it as? Arg.Bare)?.text }
            ?: return null
        if (tpmIds.isEmpty()) return null

        val lamId = (tab.args[1] as? Arg.Bare)?.text ?: return null
        val lam = byId[lamId] ?: return null
        if (lam.code != "LAM") return null
        val paramIds = (lam.args.getOrNull(0) as? Arg.Listing)?.items
            ?.mapNotNull { (it as? Arg.Bare)?.text }
            ?: return null

        val paramTypes: List<String?> = paramIds.map { paramId ->
            if (':' in paramId) {
                paramId.substringAfter(':').takeIf { it.isNotEmpty() }
            } else {
                val prc = byId[paramId] ?: return null
                if (prc.code != "PRC") return null
                (prc.args.getOrNull(1) as? Arg.Bare)?.text
            }
        }

        val valueArgIds = (node.args[1] as? Arg.Listing)?.items
            ?.mapNotNull { (it as? Arg.Bare)?.text }
            ?: return null

        val tpmToValueArgIndex: Map<String, Int> = run {
            val result = mutableMapOf<String, Int>()
            for (tpmId in tpmIds) {
                val idx = paramTypes.indexOfFirst { it == tpmId }
                if (idx < 0) return null
                result[tpmId] = idx
            }
            result
        }

        val typeArgIds = tpmIds.map { tpmId ->
            val valueArgPos = tpmToValueArgIndex.getValue(tpmId)
            val valueArgId = valueArgIds.getOrNull(valueArgPos) ?: return null
            typeOfArg(valueArgId, byId, primTypeIdByKind, HashSet())
                ?: return null
        }

        return node.copy(args = node.args + Arg.Listing(typeArgIds.map { Arg.Bare(it) }))
    }

    /** Resolve a node reference through VAR/LET/NRF chains until a TAB is found. */
    private fun resolveToTab(
        nodeId: String,
        byId: Map<String, NodeDecl>,
    ): NodeDecl? {
        val visited = HashSet<String>()
        var current = byId[nodeId] ?: return null
        while (visited.add(current.id)) {
            when (current.code) {
                "TAB" -> return current
                "VAR" -> {
                    val binderId = (current.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                    current = byId[binderId] ?: return null
                }
                "NRF" -> {
                    val targetId = (current.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                    current = byId[targetId] ?: return null
                }
                "LET" -> {
                    val valueId = (current.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                    current = byId[valueId] ?: return null
                }
                else -> return null
            }
        }
        return null
    }

    // ========================================================================
    // typeOfArg — the core type-resolution helper.
    // ========================================================================

    /**
     * Compute the Layer A type author-id of the value expression at
     * [nodeId]. v4 extends this beyond v1's minimum to cover:
     *
     *  * Primitive literals (ILT/FLT/STR/BLT/ULT/BYT) — reserved-name aware
     *  * VAR — through PRC and LET chains
     *  * APP — function's return type (FN.foreignType.result, LAM.body, FIX)
     *  * LET — body's type (LET's expression type IS its body's type)
     *  * SV / PV — the declared ofType
     *  * PFG — field type from PRD's PRF lookup
     *  * MAT / IF / WHEN — first case body's type
     *  * NRF — target's type
     *  * TAB — ForallType (returns the ForallType id if declared; only
     *    used by typeArguments inference upstream)
     *
     * Returns null for cases the elaborator can't resolve. The implicit
     * elaboration-gap policy applies — the LLM must annotate.
     */
    private fun typeOfArg(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
        visited: MutableSet<String>,
    ): String? {
        if (!visited.add(nodeId)) return null
        val node = byId[nodeId] ?: return null
        return when (node.code) {
            "ILT" -> primTypeIdByKind["Int"]
            "FLT" -> primTypeIdByKind["Float"]
            "STR" -> primTypeIdByKind["String"]
            "BLT" -> primTypeIdByKind["Bool"]
            "ULT" -> primTypeIdByKind["Unit"]
            "BYT" -> primTypeIdByKind["Bytes"]
            "VAR" -> {
                val binderId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                typeOfBinder(binderId, byId, primTypeIdByKind, visited)
            }
            "APP" -> {
                val fnId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                typeOfApplicationResult(fnId, byId, primTypeIdByKind, visited)
            }
            "LET" -> {
                // The LET expression's type IS its body's type.
                val bodyId = (node.args.getOrNull(2) as? Arg.Bare)?.text ?: return null
                typeOfArg(bodyId, byId, primTypeIdByKind, visited)
            }
            "SV" -> (node.args.getOrNull(0) as? Arg.Bare)?.text
            "PV" -> (node.args.getOrNull(0) as? Arg.Bare)?.text
            "PFG" -> {
                // PFG's type is the field's type. Resolve target's type
                // (must be a PRD), find the PRF with the named field.
                val targetId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                val fieldName = (node.args.getOrNull(1) as? Arg.Str)?.value ?: return null
                val targetTypeId = typeOfArg(targetId, byId, primTypeIdByKind, visited)
                    ?: return null
                val prd = byId[targetTypeId] ?: return null
                if (prd.code != "PRD") return null
                val prfRefs = (prd.args.getOrNull(0) as? Arg.Listing)?.items ?: return null
                for (prfRef in prfRefs) {
                    val prfId = (prfRef as? Arg.Bare)?.text ?: continue
                    val prf = byId[prfId] ?: continue
                    if (prf.code != "PRF") continue
                    val prfName = (prf.args.getOrNull(0) as? Arg.Str)?.value ?: continue
                    if (prfName != fieldName) continue
                    return (prf.args.getOrNull(1) as? Arg.Bare)?.text
                }
                null
            }
            "MAT" -> {
                // Match's type is its first case body's type.
                val cases = (node.args.getOrNull(1) as? Arg.Listing)?.items ?: return null
                val firstCaseId = (cases.firstOrNull() as? Arg.Bare)?.text ?: return null
                val mc = byId[firstCaseId] ?: return null
                if (mc.code != "MC") return null
                val bodyId = (mc.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                typeOfArg(bodyId, byId, primTypeIdByKind, visited)
            }
            "IF" -> {
                // IF's type is the then-branch's type. (The else-branch
                // must agree per Strand semantics; we take the then for
                // inference brevity.)
                val thenArg = node.args.getOrNull(1) ?: return null
                typeOfRefOrLiteral(thenArg, byId, primTypeIdByKind, visited)
            }
            "WHEN" -> {
                // WHEN's type is the first case's body. The body is a
                // STRING containing the cases; the first body is the
                // text after the first `->`.
                val casesText = (node.args.getOrNull(2) as? Arg.Str)?.value ?: return null
                val firstCase = casesText.split('|').firstOrNull()?.trim() ?: return null
                val arrowIdx = firstCase.indexOf("->")
                if (arrowIdx < 0) return null
                val bodyText = firstCase.substring(arrowIdx + 2).trim()
                // Try as int / float / bool literal; otherwise look up
                // as a declared id.
                bodyText.toLongOrNull()?.let { return primTypeIdByKind["Int"] }
                if (bodyText == "true" || bodyText == "false") return primTypeIdByKind["Bool"]
                if ('.' in bodyText && bodyText.toDoubleOrNull() != null) {
                    return primTypeIdByKind["Float"]
                }
                // Bare identifier — look up the declared node's type.
                // PRC binder reference: the PRC's paramType. Other ids:
                // recurse through typeOfArg.
                typeOfArg(bodyText, byId, primTypeIdByKind, visited)
            }
            "NRF" -> {
                val targetId = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                typeOfArg(targetId, byId, primTypeIdByKind, visited)
            }
            else -> null
        }
    }

    /**
     * Helper: resolve a literal or bare-reference argument to a type id.
     * Used by IF/WHEN where the case body may be an inline literal.
     */
    private fun typeOfRefOrLiteral(
        arg: Arg,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
        visited: MutableSet<String>,
    ): String? = when (arg) {
        is Arg.IntL -> primTypeIdByKind["Int"]
        is Arg.FloatL -> primTypeIdByKind["Float"]
        is Arg.BoolL -> primTypeIdByKind["Bool"]
        is Arg.Str -> primTypeIdByKind["String"]
        is Arg.Bare -> typeOfArg(arg.text, byId, primTypeIdByKind, visited)
        else -> null
    }

    /**
     * Compute the type of a name (PRC, LET, or other binder).
     *  * PRC → paramType
     *  * LET → recurse into the value (LET-bound name has the value's type)
     *  * compact-LAM-entry (no PRC node) → the entry's typeRef
     *  * other → fall through to typeOfArg (the binder is itself an
     *    expression — rare, but covers cases like a NodeRef-bound name).
     */
    private fun typeOfBinder(
        binderId: String,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
        visited: MutableSet<String>,
    ): String? {
        val binder = byId[binderId] ?: return compactLamParamType(binderId, byId)
        return when (binder.code) {
            "PRC" -> (binder.args.getOrNull(1) as? Arg.Bare)?.text
            "LET" -> {
                val valueId = (binder.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                typeOfArg(valueId, byId, primTypeIdByKind, visited)
            }
            else -> typeOfArg(binderId, byId, primTypeIdByKind, visited)
        }
    }

    /**
     * Look up [name] as a compact-LAM PARAM_LIST entry (no separate PRC
     * node). Returns the entry's typeRef when found, or null when the
     * name doesn't appear in any LAM's compact param list (or when the
     * entry has no `:type` suffix yet).
     *
     * Used by [typeOfBinder] and [resolveCalleeChain]'s fallback so the
     * elaborator can reason about compact-form parameters without
     * needing to materialize PRC nodes for them mid-pass.
     */
    private fun compactLamParamType(
        name: String,
        byId: Map<String, NodeDecl>,
    ): String? {
        for ((_, node) in byId) {
            if (node.code != "LAM") continue
            val params = (node.args.getOrNull(0) as? Arg.Listing)?.items ?: continue
            for (entry in params) {
                val text = (entry as? Arg.Bare)?.text ?: continue
                if (':' !in text) continue
                if (text.substringBefore(':') == name) {
                    return text.substringAfter(':').takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    /**
     * Compute the result type of an Application whose function is at
     * [fnId]. Chases through VAR/NRF/LET wrappers to find the callable.
     *
     *  * `FN` (ForeignNode) → `FN.foreignType.result` (the FNT's result)
     *  * `LAM` (Lambda) → the body's return type (recursive into the body)
     *  * `FIX` → `FIX.recursionType.result`
     *  * `TAB` → the wrapped LAM's return type (after instantiation; we
     *    return the LAM body's type, which for an explicitly-typed body
     *    matches the instantiated FunctionType's result)
     *
     * Returns null when the function is not callable or the result type
     * can't be resolved.
     */
    private fun typeOfApplicationResult(
        fnId: String,
        byId: Map<String, NodeDecl>,
        primTypeIdByKind: Map<String, String>,
        visited: MutableSet<String>,
    ): String? {
        // First, check: is fnId a VAR whose binder is a compact-LAM
        // param? Then the binder's type is a function type; the APP's
        // result is that FNT's result.
        val fnNode = byId[fnId]
        if (fnNode != null && fnNode.code == "VAR") {
            val binderId = (fnNode.args.getOrNull(0) as? Arg.Bare)?.text
            if (binderId != null && binderId !in byId) {
                val binderType = compactLamParamType(binderId, byId)
                if (binderType != null) {
                    return fntResult(binderType, byId)
                        ?: reservedFnResult(binderType)
                }
            }
        }
        val resolved = resolveCalleeChain(fnId, byId, HashSet())
            // Reserved-name fallback when the callee isn't declared.
            ?: return reservedFnResult(fnId)
        val callable = byId[resolved] ?: return reservedFnResult(resolved)
        return when (callable.code) {
            "FN" -> {
                // FN.foreignType is an FNT; its result is the second arg.
                val fntId = (callable.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                fntResult(fntId, byId)
            }
            "LAM" -> {
                val bodyId = (callable.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                typeOfArg(bodyId, byId, primTypeIdByKind, visited)
            }
            "FIX" -> {
                val recTypeId = (callable.args.getOrNull(0) as? Arg.Bare)?.text ?: return null
                fntResult(recTypeId, byId)
            }
            "TAB" -> {
                // The TAB body is a LAM; the LAM's body's type is the
                // result. Caveat: TPMs inside the body are not resolved,
                // so this only works when the body's return type is
                // monomorphic (the common case for `(T) -> T` is the
                // pathological one, but typeArguments inference handles
                // that separately).
                val tabBodyId = (callable.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                val lam = byId[tabBodyId] ?: return null
                if (lam.code != "LAM") return null
                val lamBodyId = (lam.args.getOrNull(1) as? Arg.Bare)?.text ?: return null
                typeOfArg(lamBodyId, byId, primTypeIdByKind, visited)
            }
            else -> null
        }
    }

    /** Look up an FNT node's `result` (second arg). */
    private fun fntResult(fntId: String, byId: Map<String, NodeDecl>): String? {
        val fnt = byId[fntId] ?: return null
        if (fnt.code != "FNT") return null
        return (fnt.args.getOrNull(1) as? Arg.Bare)?.text
    }

    /**
     * Build the primitive-type-kind → reserved-id map. Used so the
     * elaborator can find primitive types whether they're declared in
     * the source or supplied via Slice 1's implicit prelude.
     */
    private fun buildPrimTypeMap(doc: LayerADocument): Map<String, String> {
        val declared = doc.nodes.asSequence()
            .filter { it.code == "PRM" }
            .mapNotNull { prm ->
                val kindArg = prm.args.getOrNull(0) as? Arg.Bare ?: return@mapNotNull null
                kindArg.text to prm.id
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.first() }
        val fallback = mapOf(
            "Int" to "intT", "Float" to "floatT", "String" to "stringT",
            "Bool" to "boolT", "Unit" to "unitT", "Bytes" to "bytesT",
        )
        return fallback + declared
    }

    // ========================================================================
    // Effects inference (cases 3, 4) — unchanged from v1.
    // ========================================================================

    private fun effectsOfOrdered(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): List<String> {
        if (!visited.add(nodeId)) return emptyList()
        val node = byId[nodeId] ?: return emptyList()
        return when (node.code) {
            "FN" -> {
                if (node.args.size >= 3) extractEffectsListOrdered(node.args[2]) else emptyList()
            }
            "LAM" -> {
                if (node.args.size >= 3) {
                    extractEffectsListOrdered(node.args[2])
                } else {
                    val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptyList()
                    closureOf(bodyId, byId, HashSet(visited)).toList().sorted()
                }
            }
            "VAR" -> {
                val binderId = (node.args[0] as? Arg.Bare)?.text ?: return emptyList()
                effectsOfOrdered(binderId, byId, visited)
            }
            "NRF" -> {
                val targetId = (node.args[0] as? Arg.Bare)?.text ?: return emptyList()
                effectsOfOrdered(targetId, byId, visited)
            }
            "LET" -> {
                val valueId = (node.args[1] as? Arg.Bare)?.text ?: return emptyList()
                effectsOfOrdered(valueId, byId, visited)
            }
            "FIX" -> {
                val bodyId = (node.args[1] as? Arg.Bare)?.text ?: return emptyList()
                effectsOfOrdered(bodyId, byId, visited)
            }
            else -> emptyList()
        }
    }

    private fun extractEffectsListOrdered(arg: Arg): List<String> {
        val listing = arg as? Arg.Listing ?: return emptyList()
        return listing.items.mapNotNull { (it as? Arg.Bare)?.text }
    }

    /** Compute the effect closure of the expression at author id [nodeId]. */
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
            "LAM", "FN", "VAR", "NRF", "RS",
            "ILT", "FLT", "STR", "BLT", "ULT", "BYT",
            "PRM", "PRD", "PRF", "SUM", "SCS", "FNT", "TPM", "FAL",
            "PRC", "EFC", "EFD",
            "PV", "PFV", "SV", "RT",
            "SM", "ESE", "ESI", "ESO", "TR",
            "SCH", "INV",
            "PLT", "PVR", "PWC", "PCN" -> emptySet()
            "PFG" -> {
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
        val fnId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
        val argList = (node.args[1] as? Arg.Listing)?.items ?: emptyList()
        val out = HashSet<String>()
        out += closureOf(fnId, byId, visited)
        for (arg in argList) {
            val argId = (arg as? Arg.Bare)?.text ?: continue
            out += closureOf(argId, byId, HashSet(visited))
        }
        out += effectsOf(fnId, byId, HashSet())
        return out
    }

    private fun closureOfLet(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        val valueId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
        val bodyId = (node.args[2] as? Arg.Bare)?.text ?: return emptySet()
        return closureOf(valueId, byId, visited) + closureOf(bodyId, byId, HashSet(visited))
    }

    private fun closureOfMatch(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
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
        val interceptId = (node.args[0] as? Arg.Bare)?.text ?: return emptySet()
        val handleId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
        val bodyId = (node.args[2] as? Arg.Bare)?.text ?: return emptySet()
        val bodyClosure = closureOf(bodyId, byId, visited)
        val handleClosure = closureOf(handleId, byId, HashSet(visited))
        val handleEffects = effectsOf(handleId, byId, HashSet())
        return (bodyClosure - interceptId) + handleClosure + handleEffects
    }

    private fun effectsOf(
        nodeId: String,
        byId: Map<String, NodeDecl>,
        visited: MutableSet<String>,
    ): Set<String> {
        if (!visited.add(nodeId)) return emptySet()
        val node = byId[nodeId] ?: return emptySet()
        return when (node.code) {
            "FN" -> {
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
                val valueId = (node.args[1] as? Arg.Bare)?.text ?: return emptySet()
                effectsOf(valueId, byId, visited)
            }
            "FIX" -> {
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
