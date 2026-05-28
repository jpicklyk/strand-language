package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Emit a [LayerADocument] as canonical Strand dag-json bytes — the
 * flat-form `{ "version": 1, "root": "...", "nodes": { ... } }`
 * structure the existing `:core` [org.strand.core.JsonIngest] consumes.
 *
 * The emitter validates each node line against its [LayerAGrammar]
 * code schema (arg count, arg shape) and assembles the corresponding
 * JSON object per the per-code field mapping. Unknown codes, arity
 * mismatches, and arg-shape mismatches surface as [AuthoringException].
 *
 * No type checking or inference happens here — the emitter is a pure
 * syntax-level transcoder. The verifier remains the source of truth
 * for well-formedness; the JsonIngest + Hasher + Verifier pipeline runs
 * downstream over the emitted JSON exactly as it would over a hand-
 * authored canonical document. Round-trip property: parsing the Layer A
 * form and emitting the dag-json produces a hash-equal canonical store
 * to the original (see `LayerARoundTripTest`).
 *
 * **Layer A density v1 (Slices 1+2+3, 2026-05-25).** Three shorthand
 * forms compose at emit time:
 *
 *  * **Slice 1 — implicit prelude.** References to reserved names
 *    (primitives `intT`/`boolT`/..., common builtins `add`/`sub`/...,
 *    effect categories `receiveFx`/`nowFx`/...) inject synthetic
 *    canonical nodes when not locally declared. See
 *    [LayerAGrammar.reservedNodes].
 *  * **Slice 2 — inline literals at reference positions.** An integer,
 *    float, bool, or quoted-string token appearing in a REFERENCE,
 *    LIST_REF, or NULLABLE_REF slot synthesizes a child literal node
 *    with a `__lit<n>` internal author id and points the parent at it.
 *  * **Slice 3 — auto-VarRef for PRC/LET binders.** A bare reference
 *    to a `PRC` ParameterDecl or `LET` Let-binder at an expression-arg
 *    position synthesizes an intermediate `VarRef` and points the
 *    parent at the synthetic VarRef instead of the binder. Slot
 *    interpretation distinguishes value-position arg slots from
 *    binder-list slots (`Lambda.parameters`, `Fixpoint.recursionType`
 *    children, etc., do not get the VarRef wrapping).
 */
object DagJsonEmitter {

    private val printer = Json {
        prettyPrint = false
    }

    /** Emit [doc] as a canonical-form JSON string. */
    fun emit(doc: LayerADocument): String =
        printer.encodeToString(JsonObject.serializer(), emitJson(doc))

    /** Build the dag-json JsonObject for [doc] without serializing to text. */
    fun emitJson(doc: LayerADocument): JsonObject {
        val errors = mutableListOf<AuthoringError>()
        val ctx = EmitContext(doc)
        val emittedUser = linkedMapOf<String, JsonObject>()
        for (node in doc.nodes) {
            val nodeJson = emitNode(node, errors, ctx) ?: continue
            emittedUser[node.id] = nodeJson
            // Slice 7: track the most-recent anonymous id so a downstream
            // `@last` reference can resolve to it.
            if (node.id.startsWith("__anon")) ctx.lastAnonId = node.id
        }
        if (errors.isNotEmpty()) throw AuthoringException(errors)

        // Slice 1 (Layer A density v1): synthesize reserved nodes that are
        // referenced but not declared locally. Local declarations always
        // win; the implicit prelude fills only the unfilled slots.
        // Collect references from BOTH user nodes and synthesized
        // literal/varref nodes, since those may also reference reserved
        // names (e.g., a synthetic VarRef points at a PRC whose paramType
        // is a reserved primitive).
        val declaredIds = emittedUser.keys + ctx.synthesized.keys
        val referenced = collectReferencedIds(emittedUser.values) +
            collectReferencedIds(ctx.synthesized.values)
        val neededReserved = transitivelyCloseReserved(referenced - declaredIds)

        val nodesObj = buildJsonObject {
            for ((id, json) in emittedUser) {
                put(id, json)
            }
            for ((id, json) in ctx.synthesized) {
                put(id, json)
            }
            for (reservedId in neededReserved) {
                put(reservedId, synthesizeReserved(reservedId))
            }
        }
        return buildJsonObject {
            put("version", doc.version)
            put("root", doc.rootId)
            put("nodes", nodesObj)
        }
    }

    /**
     * Per-emission scratch state. Holds the synthetic-id counter (used
     * for inline-literal and auto-VarRef child nodes) and the map of
     * synthesized child nodes that get appended to the output document.
     *
     * Also caches per-id "is this a PRC/LET binder?" lookups so the
     * Slice 3 auto-VarRef rule can fire without scanning the document
     * O(N) times.
     */
    private class EmitContext(doc: LayerADocument) {
        var litCounter: Int = 0
        var varRefCounter: Int = 0
        var ifCounter: Int = 0
        var whenCounter: Int = 0
        var exprCounter: Int = 0
        var lamPrcCounter: Int = 0
        val synthesized: LinkedHashMap<String, JsonObject> = linkedMapOf()
        val document: LayerADocument = doc

        /**
         * Most-recent anonymous id encountered while iterating user-declared
         * nodes. Updated by [emitJson] as each node is processed; consumed
         * when a `@last` reference appears in a downstream node's arg list.
         * Null until the first anonymous declaration. Slice 7 (v2).
         */
        var lastAnonId: String? = null

        /**
         * Set of node ids whose code is `PRC` — the auto-VarRef wrap
         * targets. Implementation deviation from the plan's Slice 3
         * "Open question": LET binders are NOT included because a bare
         * Let-id appearing in a value-position slot is ambiguous between
         * "the Let expression as a sub-tree" (the structural use,
         * e.g., `outer LET ... innerLet` where `innerLet` is the Let's
         * body) and "the value bound by Let letId" (the name-lookup
         * use, written today as explicit `VAR letId`). Corpus programs
         * 06, 07, 10 (let-polymorphic, higher-order, noderef-shared)
         * exercise the structural-use case heavily. Auto-VarRef on LET
         * would break them. The plan flags this as an open question;
         * the resolved answer is "PRC only" and is captured here +
         * in the Implementation note when the plan promotes to
         * proposals/implemented/.
         *
         * Slice 5 (v2): compact-form LAM params `[x:intT y:intT]`
         * synthesize PRCs whose author id IS the parameter name. The
         * pre-pass below adds those names to topLevelBinders so the
         * auto-VarRef rule fires correctly when the Lambda body
         * references the parameter by name.
         *
         * The set is the *static* fallback layer of the binder resolver
         * (see [resolveBinder]). For PRC binders, the lookup name and
         * the binder id are identical, so the set stores just the name
         * and [resolveBinder] returns name == id when the lookup hits.
         */
        val topLevelBinders: Set<String> = run {
            val ids = mutableSetOf<String>()
            for (node in doc.nodes) {
                if (node.code == "PRC") ids += node.id
                // Slice 5: scan LAM's parameters list for binder names. A
                // compact-form entry is either `name:typeRef` (typed) or a
                // bare `name` (typed by Elaborator inference — recursion-
                // slot, call-site, etc.). Legacy bare-ref entries that
                // point at a separately-declared PRC are already covered
                // by the PRC NodeDecl scan above; for those, adding the id
                // again is a no-op.
                if (node.code == "LAM") {
                    val params = node.args.firstOrNull() as? Arg.Listing ?: continue
                    for (entry in params.items) {
                        val text = (entry as? Arg.Bare)?.text ?: continue
                        if (text == "_") continue  // anonymous slot
                        ids += if (':' in text) text.substringBefore(':') else text
                    }
                }
            }
            ids
        }

        /**
         * Stack of locally-introduced binder scopes for WHEN case
         * bodies. The author writes `Cons(p) -> body`; the WHEN
         * expander synthesizes a Pattern (variable kind) node with a
         * synthetic id like `__when0_bind_p`, then resolves the body
         * with `p -> __when0_bind_p` pushed onto this stack. Auto-
         * VarRef lookups for `p` inside the body (and inside any
         * nested expressions in the body) return the PVR id as the
         * binder target. Innermost scope wins on lookup, so nested
         * WHENs and PRC-shadowing-by-case-binder both work correctly.
         */
        val binderScopes: ArrayDeque<Map<String, String>> = ArrayDeque()

        /**
         * Resolve [name] to a binder id, walking [binderScopes]
         * innermost-first and falling back to [topLevelBinders].
         * Returns null when [name] is not a binder in any scope.
         */
        fun resolveBinder(name: String): String? {
            for (i in binderScopes.indices.reversed()) {
                binderScopes[i][name]?.let { return it }
            }
            return if (name in topLevelBinders) name else null
        }

        /**
         * Run [block] with a single-entry binder scope pushed onto
         * [binderScopes]. The scope is popped before this method
         * returns, even if [block] throws. Used by the WHEN expander
         * to make a case binder visible inside the case body without
         * leaking into sibling cases or sibling nodes.
         */
        inline fun <R> withCaseBinder(name: String, binderId: String, block: () -> R): R {
            binderScopes.addLast(mapOf(name to binderId))
            try {
                return block()
            } finally {
                binderScopes.removeLast()
            }
        }

        fun freshLitId(): String = "__lit${litCounter++}"
        fun freshVarRefId(): String = "__var${varRefCounter++}"
        fun freshIfPrefix(): String = "__if${ifCounter++}"
        fun freshWhenPrefix(): String = "__when${whenCounter++}"
        fun freshExprId(): String = "__expr${exprCounter++}"
        /**
         * Mint a unique synthesized PRC id for a compact-LAM param. The
         * naming pattern `__lamprc<n>_<name>` keeps the parameter name
         * visible in debug output without colliding when two Lambdas
         * use the same compact param name with different types.
         */
        fun freshLamPrcId(paramName: String): String = "__lamprc${lamPrcCounter++}_$paramName"
    }

    /**
     * Walk every reference field on a set of node JSON objects and
     * collect the set of author-ids they point at. References live in
     * JsonPrimitive string values and in JsonArray-of-strings list fields.
     * We over-approximate by treating every primitive string under any
     * non-`type`-or-discriminator key as a potential reference — the
     * worst case is a few false positives that don't match a reserved
     * name and get ignored.
     */
    private fun collectReferencedIds(nodes: Collection<JsonObject>): Set<String> {
        val out = mutableSetOf<String>()
        for (node in nodes) {
            for ((key, value) in node) {
                if (key in NON_REF_FIELDS) continue
                when (value) {
                    is JsonPrimitive -> if (value.isString) out += value.content
                    is JsonArray -> {
                        for (item in value) {
                            if (item is JsonPrimitive && item.isString) out += item.content
                        }
                    }
                    else -> {}
                }
            }
        }
        return out
    }

    /**
     * JSON field names that carry non-reference primitive/string values.
     * Every other primitive-string or string-array field is treated as
     * an author-id reference for reserved-name resolution.
     */
    private val NON_REF_FIELDS: Set<String> = setOf(
        "type", "kind", "streamKind",
        "name", "categoryName", "fieldName", "caseName",
        "schemaName", "invariantName",
        "target",
        "overflowPolicy", "consumerMode",
    )

    /**
     * Given a set of referenced-but-undeclared ids, return the subset
     * that maps to reserved names, transitively closed under each
     * reserved node's [LayerAGrammar.ReservedNodeSpec.dependencies].
     * Iteration order matches the reservedNodes table (deterministic).
     */
    private fun transitivelyCloseReserved(initial: Set<String>): List<String> {
        val needed = LinkedHashSet<String>()
        fun add(id: String) {
            if (id in needed) return
            val spec = LayerAGrammar.reservedNodes[id] ?: return
            needed += id
            for (dep in spec.dependencies) add(dep)
        }
        for (id in initial) add(id)
        return LayerAGrammar.reservedNodes.keys.filter { it in needed }
    }

    private fun synthesizeReserved(id: String): JsonObject {
        val spec = LayerAGrammar.reservedNodes.getValue(id)
        val fields = mutableMapOf<String, JsonElement>()
        fields["type"] = JsonPrimitive(spec.jsonType)
        for ((k, v) in spec.stringFields) {
            fields[k] = JsonPrimitive(v)
        }
        for ((k, v) in spec.refFields) {
            fields[k] = JsonPrimitive(v)
        }
        for ((k, v) in spec.refListFields) {
            fields[k] = JsonArray(v.map { JsonPrimitive(it) })
        }
        // Q-039: emit effectProjections when non-empty. Each projection
        // is an object with `category` and `sources`; each source is
        // either {kind:"ArgRef", index:N} or {kind:"LiteralNode", target:"<id>"}.
        // Matches the schema in [JsonIngest.optionalEffectProjections].
        if (spec.effectProjections.isNotEmpty()) {
            val projections = spec.effectProjections.map { proj ->
                val sources = proj.sources.map { src ->
                    when (src) {
                        is LayerAGrammar.ReservedProjectionSource.ArgRef -> JsonObject(mapOf(
                            "kind" to JsonPrimitive("ArgRef"),
                            "index" to JsonPrimitive(src.index),
                        ))
                        is LayerAGrammar.ReservedProjectionSource.LiteralNode -> JsonObject(mapOf(
                            "kind" to JsonPrimitive("LiteralNode"),
                            "target" to JsonPrimitive(src.target),
                        ))
                    }
                }
                JsonObject(mapOf(
                    "category" to JsonPrimitive(proj.category),
                    "sources" to JsonArray(sources),
                ))
            }
            fields["effectProjections"] = JsonArray(projections)
        }
        return JsonObject(fields)
    }

    private fun emitNode(
        node: NodeDecl,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): JsonObject? {
        val schema = LayerAGrammar.codes[node.code] ?: run {
            errors += AuthoringError.UnknownCode(line = node.line, code = node.code)
            return null
        }

        val minArgs = schema.required.size
        val maxArgs = minArgs + schema.optional.size
        if (node.args.size !in minArgs..maxArgs) {
            errors += AuthoringError.ArityMismatch(
                line = node.line, code = node.code,
                expected = minArgs..maxArgs, actual = node.args.size,
            )
            return null
        }

        // Layer A density v1.5 (Slice 4) — IF/Match-on-Bool sugar.
        // IF expands to the Match wrapper + 6 child nodes (2 BoolLit, 2
        // Pattern literal, 2 MatchCase). The Match takes the user's
        // author id; the 6 child nodes get `__if<n>_*` internal ids.
        if (node.code == "IF") return expandIfSugar(node, errors, ctx)

        // Layer A density v3 (Slice 9) — WHEN/constructor-pattern sugar.
        if (node.code == "WHEN") return expandWhenSugar(node, errors, ctx)

        val fields = mutableMapOf<String, JsonElement>()
        fields["type"] = JsonPrimitive(schema.jsonType)

        // Discriminator (e.g., Pattern.kind = "literal", EventStream.streamKind = "external").
        schema.discriminator?.let { (field, value) ->
            fields[field] = JsonPrimitive(value)
        }

        // Required slots.
        for ((i, spec) in schema.required.withIndex()) {
            val arg = node.args[i]
            val value = argToJson(node.line, node.code, i, spec, arg, errors, ctx) ?: return null
            fields[spec.jsonField] = value
        }
        // Optional slots (consumed in declaration order from the tail).
        for ((j, spec) in schema.optional.withIndex()) {
            val i = schema.required.size + j
            if (i >= node.args.size) break
            val arg = node.args[i]
            val value = argToJson(node.line, node.code, i, spec, arg, errors, ctx) ?: return null
            fields[spec.jsonField] = value
        }

        return JsonObject(fields)
    }

    /**
     * Slice 4 — IF/Match-on-Bool sugar expansion.
     *
     * Takes the user's `result IF scrutinee thenExpr elseExpr` and produces
     * a 7-node tower with the same canonical hash as the explicit form:
     *
     *     __ifN_lit_true   BoolLit true
     *     __ifN_lit_false  BoolLit false
     *     __ifN_pat_true   Pattern{kind=literal, patternType=boolT, literal=__ifN_lit_true}
     *     __ifN_pat_false  Pattern{kind=literal, patternType=boolT, literal=__ifN_lit_false}
     *     __ifN_case_true  MatchCase{pattern=__ifN_pat_true, body=resolvedThen}
     *     __ifN_case_false MatchCase{pattern=__ifN_pat_false, body=resolvedElse}
     *     <user id>        Match{scrutinee=resolvedScrutinee, cases=[__ifN_case_true, __ifN_case_false]}
     *
     * Returns the Match JsonObject for the user's author id; the 6 child
     * nodes are added to [EmitContext.synthesized] for later inclusion in
     * the document.
     *
     * Each arg is resolved through [resolveExpressionRef] which applies
     * Slice 2 inline-literal synthesis and Slice 3 auto-VarRef. So
     * `IF nIsZero 1 0` synthesizes IntLit children for the 1 and 0; and
     * `IF cond x y` where `x` is a PRC param synthesizes a VarRef around
     * `x`.
     *
     * The `boolT` reference in the synthesized Patterns is left as a bare
     * id — Slice 1's reserved-name prelude resolves it. A user who
     * declares their own `boolT` shadows the implicit one (the canonical
     * encoder makes both byte-identical).
     */
    private fun expandIfSugar(
        node: NodeDecl,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): JsonObject? {
        val scrutineeArg = node.args[0]
        val thenArg = node.args[1]
        val elseArg = node.args[2]

        val scrutineeId = resolveExpressionRef(node.line, "IF", 0, scrutineeArg, errors, ctx)
            ?: return null
        val thenId = resolveExpressionRef(node.line, "IF", 1, thenArg, errors, ctx)
            ?: return null
        val elseId = resolveExpressionRef(node.line, "IF", 2, elseArg, errors, ctx)
            ?: return null

        val prefix = ctx.freshIfPrefix()
        val litTrueId = "${prefix}_lit_true"
        val litFalseId = "${prefix}_lit_false"
        val patTrueId = "${prefix}_pat_true"
        val patFalseId = "${prefix}_pat_false"
        val caseTrueId = "${prefix}_case_true"
        val caseFalseId = "${prefix}_case_false"

        ctx.synthesized[litTrueId] = buildJsonObject {
            put("type", "BoolLit")
            put("value", true)
        }
        ctx.synthesized[litFalseId] = buildJsonObject {
            put("type", "BoolLit")
            put("value", false)
        }
        ctx.synthesized[patTrueId] = buildJsonObject {
            put("type", "Pattern")
            put("kind", "literal")
            put("patternType", "boolT")
            put("literal", litTrueId)
        }
        ctx.synthesized[patFalseId] = buildJsonObject {
            put("type", "Pattern")
            put("kind", "literal")
            put("patternType", "boolT")
            put("literal", litFalseId)
        }
        ctx.synthesized[caseTrueId] = buildJsonObject {
            put("type", "MatchCase")
            put("pattern", patTrueId)
            put("body", thenId)
        }
        ctx.synthesized[caseFalseId] = buildJsonObject {
            put("type", "MatchCase")
            put("pattern", patFalseId)
            put("body", elseId)
        }

        // Return the Match wrapper for the user's id.
        return buildJsonObject {
            put("type", "Match")
            put("scrutinee", scrutineeId)
            put("cases", JsonArray(listOf(JsonPrimitive(caseTrueId), JsonPrimitive(caseFalseId))))
        }
    }

    /**
     * Slice 9 (v3) — WHEN/constructor-pattern sugar expansion.
     *
     * Takes the user's `result WHEN scrutinee sumType "<cases>"` and
     * produces a Match wrapper + per-case {PCN [+ PVR] + MC} tower. The
     * `<cases>` STRING is parsed at emit time as a `|`-separated list
     * of `CaseName[(binder)] -> body` entries.
     *
     * For each case:
     *  * If `(binder)` is present and the SumType's matching SCS has a
     *    non-null caseType, synthesize a PVR with author id =
     *    `__when<n>_bind_<binder>` and patternType = the case's caseType.
     *  * Synthesize a PCN with author id = `__when<n>_pat_<CaseName>`,
     *    patternType = the user-named sumType, caseName, payloadPattern =
     *    PVR id (or null when no binder).
     *  * Resolve the body string:
     *    - Inline literal: synthesize child literal node, body = its id.
     *    - Identifier matching the case's binder: synthesize VarRef
     *      pointing at the PVR, body = VarRef id.
     *    - Identifier matching a PRC binder in scope: synthesize VarRef
     *      pointing at the PRC, body = VarRef id.
     *    - Any other identifier: pass through (must be a declared node).
     *  * Synthesize a MC with author id = `__when<n>_case_<CaseName>`,
     *    pattern = PCN id, body = resolved body.
     *
     * The Match wrapper takes the user's author id; its scrutinee resolves
     * through the same path as a regular REFERENCE arg (Slice 2 inline-
     * literal + Slice 3 auto-VarRef).
     *
     * Out of scope (per the plan §Slice 9 "Out of scope" list):
     *  * Nested constructor patterns (`Some(Cons(h, t))`) — requires
     *    explicit MC + nested PCN tower.
     *  * Wildcard or literal payload patterns (`Some(_)`, `Some(42)`).
     *  * Or-patterns (the node algebra doesn't support disjunction).
     */
    private fun expandWhenSugar(
        node: NodeDecl,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): JsonObject? {
        val scrutineeArg = node.args[0]
        val sumTypeArg = node.args[1]
        val casesArg = node.args[2]

        val scrutineeId = resolveExpressionRef(node.line, "WHEN", 0, scrutineeArg, errors, ctx)
            ?: return null

        val sumTypeId = (sumTypeArg as? Arg.Bare)?.text ?: run {
            shapeMismatch(node.line, "WHEN", 1, "sumType reference", sumTypeArg, errors)
            return null
        }

        val casesText = (casesArg as? Arg.Str)?.value ?: run {
            shapeMismatch(node.line, "WHEN", 2, "\"CaseName[(binder)] -> body | ...\" string", casesArg, errors)
            return null
        }

        val whenCases = parseWhenCaseList(node.line, casesText, errors) ?: return null
        if (whenCases.isEmpty()) {
            errors += AuthoringError.ArgShapeMismatch(
                line = node.line, code = "WHEN", position = 2,
                expectedKind = "at least one case",
                actualKind = "empty case list",
            )
            return null
        }

        // Build a sum-case lookup: caseName -> caseType ref (or null for
        // payload-less cases). We walk the document looking for SCS nodes
        // and the SUM that references them. The SUM's id must match
        // `sumTypeId`; each SCS reachable from the SUM's `cases` list
        // contributes a (name -> caseType) entry.
        val caseTypeByName = buildCaseTypeMap(sumTypeId, ctx)

        val prefix = ctx.freshWhenPrefix()
        val caseIds = mutableListOf<String>()
        for (whenCase in whenCases) {
            val caseType = caseTypeByName[whenCase.caseName]
            // Whether the SCS for this case has a payload determines if a
            // binder is allowed. Mismatch is a verifier-level error; we
            // pass through whatever the user wrote and let the verifier
            // reject if needed.
            //
            // Substitute RecursiveSelf with the WHEN's named sumType
            // (the RT) so a binder over a recursive-sum case carries a
            // patternType that is closed — i.e., has no free RS — and
            // verifies in the depth-0 context of the PVR. For non-
            // recursive sums (or recursive sums whose case has no RS
            // inside its caseType) the walker memoizes everything as
            // unchanged and returns the original caseType id.
            val resolvedPatternType = caseType?.let {
                substituteRecursiveSelf(it, sumTypeId, prefix, ctx)
            }

            // Synthesize PVR (if the user wrote a binder).
            val pvrId: String? = whenCase.binder?.let { binderName ->
                val id = "${prefix}_bind_$binderName"
                ctx.synthesized[id] = buildJsonObject {
                    put("type", "Pattern")
                    put("kind", "variable")
                    put("patternType", resolvedPatternType ?: "unknownT")
                    put("name", binderName)
                }
                id
            }

            // Synthesize PCN.
            val pcnId = "${prefix}_pat_${whenCase.caseName}"
            ctx.synthesized[pcnId] = buildJsonObject {
                put("type", "Pattern")
                put("kind", "constructor")
                put("patternType", sumTypeId)
                put("caseName", whenCase.caseName)
                if (pvrId != null) {
                    put("payloadPattern", pvrId)
                }
            }

            // Resolve the body with the case's binder pushed onto the
            // ctx scope stack. The binder is in scope only inside this
            // body — not in sibling cases, sibling nodes, or even
            // sibling case-pattern/PCN slots. The scope is popped by
            // withCaseBinder's finally block before the next iteration.
            val bodyId = if (whenCase.binder != null && pvrId != null) {
                ctx.withCaseBinder(whenCase.binder, pvrId) {
                    resolveWhenBody(
                        bodyText = whenCase.body,
                        line = node.line,
                        errors = errors,
                        ctx = ctx,
                    )
                }
            } else {
                resolveWhenBody(
                    bodyText = whenCase.body,
                    line = node.line,
                    errors = errors,
                    ctx = ctx,
                )
            } ?: return null

            // Synthesize MC.
            val mcId = "${prefix}_case_${whenCase.caseName}"
            ctx.synthesized[mcId] = buildJsonObject {
                put("type", "MatchCase")
                put("pattern", pcnId)
                put("body", bodyId)
            }
            caseIds += mcId
        }

        return buildJsonObject {
            put("type", "Match")
            put("scrutinee", scrutineeId)
            put("cases", JsonArray(caseIds.map { JsonPrimitive(it) }))
        }
    }

    /**
     * Slice 9 case-list mini-parser. Splits the WHEN cases string on `|`
     * (literal pipe, surrounded by whitespace for safety) and then each
     * case on `->`. Returns null with errors recorded on malformed input.
     */
    private data class WhenCase(val caseName: String, val binder: String?, val body: String)
    private fun parseWhenCaseList(
        line: Int,
        text: String,
        errors: MutableList<AuthoringError>,
    ): List<WhenCase>? {
        val out = mutableListOf<WhenCase>()
        for (rawCase in text.split('|')) {
            val caseStr = rawCase.trim()
            if (caseStr.isEmpty()) continue
            val arrowIdx = caseStr.indexOf("->")
            if (arrowIdx < 0) {
                errors += AuthoringError.ArgShapeMismatch(
                    line = line, code = "WHEN", position = 2,
                    expectedKind = "`CaseName[(binder)] -> body`",
                    actualKind = "case `$caseStr` missing `->` separator",
                )
                return null
            }
            val patternPart = caseStr.substring(0, arrowIdx).trim()
            val bodyPart = caseStr.substring(arrowIdx + 2).trim()
            if (bodyPart.isEmpty()) {
                errors += AuthoringError.ArgShapeMismatch(
                    line = line, code = "WHEN", position = 2,
                    expectedKind = "non-empty body after `->`",
                    actualKind = "case `$caseStr` has empty body",
                )
                return null
            }
            val parenIdx = patternPart.indexOf('(')
            val (caseName, binder) = if (parenIdx < 0) {
                patternPart to null
            } else {
                val closeIdx = patternPart.indexOf(')', parenIdx)
                if (closeIdx <= parenIdx) {
                    errors += AuthoringError.ArgShapeMismatch(
                        line = line, code = "WHEN", position = 2,
                        expectedKind = "balanced `(binder)`",
                        actualKind = "case `$caseStr` missing closing paren",
                    )
                    return null
                }
                val name = patternPart.substring(0, parenIdx).trim()
                val b = patternPart.substring(parenIdx + 1, closeIdx).trim()
                name to b
            }
            if (caseName.isEmpty()) {
                errors += AuthoringError.ArgShapeMismatch(
                    line = line, code = "WHEN", position = 2,
                    expectedKind = "non-empty case name",
                    actualKind = "case `$caseStr` has empty case name",
                )
                return null
            }
            out += WhenCase(caseName = caseName, binder = binder, body = bodyPart)
        }
        return out
    }

    /**
     * Build a `caseName -> caseTypeId` map for the named SumType. Walks
     * the document's NodeDecls to find:
     *   1. The SUM node with id matching [sumTypeId], following any RT
     *      (RecursiveType) wrappers — `listT RT listSumT` lets a WHEN
     *      whose sumType arg names `listT` still resolve to `listSumT`'s
     *      cases for binder type inference.
     *   2. Each SCS in its `cases` list.
     *
     * Returns an empty map if [sumTypeId] doesn't resolve to a declared
     * SUM (after RT-unwrapping) — the WHEN's PVR patterns then use a
     * "unknownT" placeholder (which the verifier will reject; the LLM
     * must add the SUM decl or the user mistyped a case name).
     */
    private fun buildCaseTypeMap(sumTypeId: String, ctx: EmitContext): Map<String, String?> {
        // Follow RT wrappers (bounded depth to defend against pathological cycles).
        var currentId = sumTypeId
        var currentNode = ctx.document.nodes.firstOrNull { it.id == currentId }
        var hops = 0
        while (currentNode?.code == "RT" && hops < 8) {
            val bodyRef = (currentNode.args.firstOrNull() as? Arg.Bare)?.text ?: break
            currentId = bodyRef
            currentNode = ctx.document.nodes.firstOrNull { it.id == currentId }
            hops++
        }
        val sumNode = currentNode?.takeIf { it.code == "SUM" } ?: return emptyMap()
        val casesList = (sumNode.args.firstOrNull() as? Arg.Listing)?.items ?: return emptyMap()
        val scsIds = casesList.mapNotNull { (it as? Arg.Bare)?.text }.toSet()
        val out = mutableMapOf<String, String?>()
        for (decl in ctx.document.nodes) {
            if (decl.code != "SCS" || decl.id !in scsIds) continue
            val name = (decl.args.getOrNull(0) as? Arg.Str)?.value ?: continue
            val caseType = when (val ct = decl.args.getOrNull(1)) {
                is Arg.Bare -> ct.text
                else -> null  // Arg.Null = no payload
            }
            out[name] = caseType
        }
        return out
    }

    /**
     * Walk the subgraph rooted at [caseTypeId] (typically a PRD, but
     * also PRM / SUM / FNT / SCS / PRF) and synthesize a structurally-
     * equivalent subgraph in which every [RecursiveSelf] reference
     * reachable without crossing a [RecursiveType] boundary is
     * rewritten to point at [rtId]. Returns the id of the rewritten
     * subgraph's root, or [caseTypeId] unchanged when no RS reference
     * was reachable.
     *
     * Motivation: WHEN sugar over a recursive sum (`μ. Cons({head:
     * Int, tail: <self>}) | Nil`) reads the SCS's caseType to type the
     * binder PVR. The caseType references RS for the recursive field
     * (the `<self>`), which is bound by the enclosing RT. But the PVR
     * is synthesized outside any RT context, so the verifier rejects
     * the standalone RS as [UnboundRecursiveSelf]. Substitute RS with
     * the RT id (which IS the recursive type) so the PVR's patternType
     * is closed and verifier-clean.
     *
     * The substitution is structural — both the original and the
     * rewritten subgraph canonical-hash to the same bytes under the
     * equirecursive equality the encoder implements, so semantics are
     * unchanged. The new ids carry the `[prefix]_outer_` prefix so they
     * don't collide with the original NodeDecls; the prefix is the WHEN
     * sugar's per-WHEN counter (`__when<n>`).
     *
     * Boundaries: nested RT subgraphs introduce their own binder, so
     * RS references inside them belong to the inner RT and must be
     * left alone. The walker recognizes RT nodes and returns the
     * original id without descent.
     */
    private fun substituteRecursiveSelf(
        caseTypeId: String,
        rtId: String,
        prefix: String,
        ctx: EmitContext,
    ): String {
        val byId = ctx.document.nodes.associateBy { it.id }
        val memo = mutableMapOf<String, String>()
        fun walk(id: String): String {
            memo[id]?.let { return it }
            val node = byId[id] ?: run {
                memo[id] = id
                return id
            }
            // RecursiveSelf — the substitution target. Replace with rtId.
            if (node.code == "RS") {
                memo[id] = rtId
                return rtId
            }
            // RT introduces a new binder; its RS refs belong to that
            // inner RT, not the outer one we're substituting away. Pass
            // through unchanged.
            if (node.code == "RT") {
                memo[id] = id
                return id
            }
            // Structural type nodes — recurse into the fields that
            // carry type references and rebuild only when at least one
            // reference rewrote to a different id.
            return when (node.code) {
                "PRD" -> {
                    val items = (node.args.firstOrNull() as? Arg.Listing)?.items
                        ?: run { memo[id] = id; return id }
                    val fieldIds = items.mapNotNull { (it as? Arg.Bare)?.text }
                    if (fieldIds.size != items.size) {
                        memo[id] = id
                        return id
                    }
                    val rewritten = fieldIds.map(::walk)
                    if (rewritten == fieldIds) {
                        memo[id] = id; return id
                    }
                    val newId = "${prefix}_outer_$id"
                    ctx.synthesized[newId] = buildJsonObject {
                        put("type", "ProductType")
                        put("fields", JsonArray(rewritten.map { JsonPrimitive(it) }))
                    }
                    memo[id] = newId
                    newId
                }
                "PRF" -> {
                    val name = (node.args.getOrNull(0) as? Arg.Str)?.value
                        ?: run { memo[id] = id; return id }
                    val fieldTypeId = (node.args.getOrNull(1) as? Arg.Bare)?.text
                        ?: run { memo[id] = id; return id }
                    val rewritten = walk(fieldTypeId)
                    if (rewritten == fieldTypeId) {
                        memo[id] = id; return id
                    }
                    val newId = "${prefix}_outer_$id"
                    ctx.synthesized[newId] = buildJsonObject {
                        put("type", "ProductTypeField")
                        put("name", name)
                        put("fieldType", rewritten)
                    }
                    memo[id] = newId
                    newId
                }
                "SUM" -> {
                    val items = (node.args.firstOrNull() as? Arg.Listing)?.items
                        ?: run { memo[id] = id; return id }
                    val caseIds = items.mapNotNull { (it as? Arg.Bare)?.text }
                    if (caseIds.size != items.size) {
                        memo[id] = id; return id
                    }
                    val rewritten = caseIds.map(::walk)
                    if (rewritten == caseIds) {
                        memo[id] = id; return id
                    }
                    val newId = "${prefix}_outer_$id"
                    ctx.synthesized[newId] = buildJsonObject {
                        put("type", "SumType")
                        put("cases", JsonArray(rewritten.map { JsonPrimitive(it) }))
                    }
                    memo[id] = newId
                    newId
                }
                "SCS" -> {
                    val name = (node.args.getOrNull(0) as? Arg.Str)?.value
                        ?: run { memo[id] = id; return id }
                    val caseTypeRef = (node.args.getOrNull(1) as? Arg.Bare)?.text
                    if (caseTypeRef == null) {
                        memo[id] = id; return id
                    }
                    val rewritten = walk(caseTypeRef)
                    if (rewritten == caseTypeRef) {
                        memo[id] = id; return id
                    }
                    val newId = "${prefix}_outer_$id"
                    ctx.synthesized[newId] = buildJsonObject {
                        put("type", "SumTypeCase")
                        put("name", name)
                        put("caseType", rewritten)
                    }
                    memo[id] = newId
                    newId
                }
                "FNT" -> {
                    val paramItems = (node.args.getOrNull(0) as? Arg.Listing)?.items
                        ?: run { memo[id] = id; return id }
                    val paramIds = paramItems.mapNotNull { (it as? Arg.Bare)?.text }
                    if (paramIds.size != paramItems.size) {
                        memo[id] = id; return id
                    }
                    val resultId = (node.args.getOrNull(1) as? Arg.Bare)?.text
                        ?: run { memo[id] = id; return id }
                    val rewrittenParams = paramIds.map(::walk)
                    val rewrittenResult = walk(resultId)
                    if (rewrittenParams == paramIds && rewrittenResult == resultId) {
                        memo[id] = id; return id
                    }
                    val effects = (node.args.getOrNull(2) as? Arg.Listing)?.items
                        ?.mapNotNull { (it as? Arg.Bare)?.text }
                        ?: emptyList()
                    val newId = "${prefix}_outer_$id"
                    ctx.synthesized[newId] = buildJsonObject {
                        put("type", "FunctionType")
                        put("parameters", JsonArray(rewrittenParams.map { JsonPrimitive(it) }))
                        put("result", rewrittenResult)
                        if (effects.isNotEmpty()) {
                            put("effects", JsonArray(effects.map { JsonPrimitive(it) }))
                        }
                    }
                    memo[id] = newId
                    newId
                }
                // Type nodes that cannot transitively contain RS via the
                // type-position fields we track (PRM, TPM, FAL).
                else -> {
                    memo[id] = id
                    id
                }
            }
        }
        return walk(caseTypeId)
    }

    /**
     * Slice 9 case-body resolver. Handles:
     *   * Nested expression `(CODE args...)`: tokenize the body via
     *     [LayerAParser.tokenizeLine] and synthesize a child node
     *     through [synthesizeNestedIfNested]. The case binder (if
     *     any) is visible inside the nested expression because the
     *     caller (`expandWhenSugar`) has pushed it onto
     *     `ctx.binderScopes` via `withCaseBinder` before invoking
     *     this resolver.
     *   * Literal token: parse as Int/Float/Bool and synthesize a
     *     literal node, return its id. (StringLit support requires
     *     recognizing `"..."` inside the body text; deferred —
     *     bodies needing a StringLit declare it as a separate node.)
     *   * Bare identifier matching a binder in scope: synthesize a
     *     VarRef pointing at the binder id. The binder may be either
     *     the WHEN case binder (resolved through the pushed scope) or
     *     a top-level PRC (resolved through `topLevelBinders`).
     *   * Any other bare identifier: pass through as a reference
     *     (must be a declared NodeDecl id; the verifier rejects
     *     unresolved refs).
     */
    private fun resolveWhenBody(
        bodyText: String,
        line: Int,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        // Nested expression: bodyText of the form `(CODE args...)`.
        // Tokenize via LayerAParser so nesting composes (Slice 10 v4).
        // We tokenize the body fragment; if it yields exactly one
        // Arg.Nested we synthesize a child node and return its id.
        if (bodyText.startsWith("(")) {
            val parseErrors = mutableListOf<AuthoringError>()
            val tokens = LayerAParser.tokenizeLine(bodyText, line, parseErrors)
            if (parseErrors.isNotEmpty()) {
                errors.addAll(parseErrors)
                return null
            }
            if (tokens.size == 1) {
                val token = tokens[0]
                if (token is Arg.Nested) {
                    // Reuse the standard nested-expression synthesizer; it
                    // applies the same producesValue/producesType check as
                    // the rest of the emitter and synthesizes the child.
                    return synthesizeNestedIfNested(token, "WHEN", 2, line, errors, ctx)
                }
            }
            errors += AuthoringError.ArgShapeMismatch(
                line = line, code = "WHEN", position = 2,
                expectedKind = "WHEN case body: single nested `(CODE args...)` expression",
                actualKind = "fragment `$bodyText` did not parse as a single nested expression",
            )
            return null
        }
        // Try literal first.
        val asInt = bodyText.toLongOrNull()
        if (asInt != null) {
            val id = ctx.freshLitId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "IntLit")
                put("value", asInt)
            }
            return id
        }
        if (bodyText == "true" || bodyText == "false") {
            val id = ctx.freshLitId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "BoolLit")
                put("value", bodyText == "true")
            }
            return id
        }
        val asFloat = bodyText.toDoubleOrNull()
        if (asFloat != null && '.' in bodyText) {
            val id = ctx.freshLitId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "FloatLit")
                put("value", asFloat)
            }
            return id
        }
        // Binder reference (case binder or PRC). Both routes resolve
        // through ctx.resolveBinder, which checks the WHEN-case scope
        // first (pushed by expandWhenSugar's withCaseBinder) and then
        // falls back to the static top-level PRC set.
        ctx.resolveBinder(bodyText)?.let { binderId ->
            val id = ctx.freshVarRefId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "VarRef")
                put("binder", binderId)
            }
            return id
        }
        // Plain declared reference.
        return bodyText
    }

    /**
     * Resolve a single expression-position [arg] to its dag-json id text.
     * Applies Slice 2 inline-literal synthesis and Slice 3 auto-VarRef
     * (treating the slot as a value-position REFERENCE). Returns null on
     * shape mismatch (errors recorded).
     */
    /**
     * Slice 10 (v4) — resolve the value side of a `name=value` FIELD_LIST
     * entry where the value has been tokenized separately (because of a
     * `(` or quoted-string boundary inside the entry). Accepts an inline
     * literal, a nested expression, or a bare reference (with Slice 3
     * auto-VarRef + Slice 7 `@last` resolution). The resulting string is
     * the synthetic id pointed at by the parent PFV's `value` field.
     */
    private fun resolveFieldValue(
        valueArg: Arg,
        parentCode: String,
        position: Int,
        line: Int,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        synthesizeLiteralIfLiteral(valueArg, ctx)?.let { return it }
        synthesizeNestedIfNested(valueArg, parentCode, position, line, errors, ctx)
            ?.let { return it }
        if (valueArg is Arg.Nested) {
            return null  // error already recorded
        }
        val text = (valueArg as? Arg.Bare)?.text ?: run {
            shapeMismatch(line, parentCode, position, "compact field value (ref / literal / nested)", valueArg, errors)
            return null
        }
        val resolvedAnon = resolveAtLast(text, line, parentCode, errors, ctx) ?: return null
        val binderId = ctx.resolveBinder(resolvedAnon)
        return if (binderId != null) {
            val varId = ctx.freshVarRefId()
            ctx.synthesized[varId] = buildJsonObject {
                put("type", "VarRef")
                put("binder", binderId)
            }
            varId
        } else resolvedAnon
    }

    private fun resolveExpressionRef(
        line: Int,
        code: String,
        position: Int,
        arg: Arg,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        synthesizeLiteralIfLiteral(arg, ctx)?.let { return it }
        // Slice 10 (v4): nested expression at IF/WHEN expression position.
        synthesizeNestedIfNested(arg, code, position, line, errors, ctx)?.let { return it }
        if (arg is Arg.Nested) {
            return null  // error already recorded
        }
        val text = (arg as? Arg.Bare)?.text ?: run {
            shapeMismatch(line, code, position, "bare reference or inline literal", arg, errors)
            return null
        }
        // Slice 7: resolve `@last` to the most-recent anonymous id.
        val resolved = resolveAtLast(text, line, code, errors, ctx) ?: return null
        // Treat the slot as value-position; auto-VarRef binder names
        // through the scope-aware resolver (PRC + WHEN case binder).
        val binderId = ctx.resolveBinder(resolved)
        return if (binderId != null) {
            val id = ctx.freshVarRefId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "VarRef")
                put("binder", binderId)
            }
            id
        } else resolved
    }

    private fun argToJson(
        line: Int,
        code: String,
        position: Int,
        spec: LayerAGrammar.FieldSpec,
        arg: Arg,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): JsonElement? {
        when (spec.kind) {
            LayerAGrammar.ArgKind.REFERENCE -> {
                // Slice 2: inline literal synthesizes a child literal node.
                synthesizeLiteralIfLiteral(arg, ctx)?.let { return JsonPrimitive(it) }
                // Slice 10 (v4): nested expression `(CODE args...)`
                // synthesizes a child node at a value-position REFERENCE.
                synthesizeNestedIfNested(arg, code, position, line, errors, ctx)
                    ?.let { return JsonPrimitive(it) }
                if (arg is Arg.Nested) {
                    return null  // error already recorded by synthesizeNestedIfNested
                }
                val text = (arg as? Arg.Bare)?.text ?: run {
                    shapeMismatch(line, code, position, "bare reference", arg, errors)
                    return null
                }
                // Slice 7: `@last` resolves to the most recent anonymous id.
                val resolvedAnon = resolveAtLast(text, line, code, errors, ctx) ?: return null
                // Slice 3: auto-VarRef when the parent slot is an expression
                // value-position (see [isValuePositionRefSlot]) and the
                // reference points at a PRC or LET binder.
                val resolved = maybeAutoVarRef(resolvedAnon, code, spec, ctx)
                return JsonPrimitive(resolved)
            }
            LayerAGrammar.ArgKind.KEYWORD -> {
                val text = (arg as? Arg.Bare)?.text ?: run {
                    shapeMismatch(line, code, position, "bare keyword", arg, errors)
                    return null
                }
                return JsonPrimitive(text)
            }
            LayerAGrammar.ArgKind.STRING -> {
                val text = (arg as? Arg.Str)?.value ?: run {
                    shapeMismatch(line, code, position, "quoted string", arg, errors)
                    return null
                }
                return JsonPrimitive(text)
            }
            LayerAGrammar.ArgKind.INT -> {
                val v = (arg as? Arg.IntL)?.value ?: run {
                    shapeMismatch(line, code, position, "integer", arg, errors)
                    return null
                }
                return JsonPrimitive(v)
            }
            LayerAGrammar.ArgKind.FLOAT -> {
                val v = (arg as? Arg.FloatL)?.value ?: run {
                    shapeMismatch(line, code, position, "float (must contain a dot)", arg, errors)
                    return null
                }
                return JsonPrimitive(v)
            }
            LayerAGrammar.ArgKind.BOOL -> {
                val v = (arg as? Arg.BoolL)?.value ?: run {
                    shapeMismatch(line, code, position, "true/false", arg, errors)
                    return null
                }
                return JsonPrimitive(v)
            }
            LayerAGrammar.ArgKind.LIST_REF -> {
                // Slice (v4 follow-up): `_` at a LIST_REF slot means "no
                // elements" — equivalent to `[]`. Agents naturally reach
                // for `_` (the documented null-reference placeholder) when
                // skipping an optional middle list slot to reach a later
                // one (e.g., `APP fn args _ [efd]` for "no typeArguments,
                // explicit effectInstances"). Accepted here as syntactic
                // sugar for `APP fn args [] [efd]`; both forms canonical-
                // encode identically since empty list slots gate on size
                // > 0. Run 6 measurement (2026-05-28) observed this as
                // the dominant first-attempt Layer A authoring slip.
                if (arg == Arg.Null) {
                    return JsonArray(emptyList())
                }
                val list = (arg as? Arg.Listing)?.items ?: run {
                    shapeMismatch(line, code, position, "[ref ref ...] list", arg, errors)
                    return null
                }
                val elements = list.map { elt ->
                    // Slice 2: list element can be an inline literal.
                    val litId = synthesizeLiteralIfLiteral(elt, ctx)
                    if (litId != null) {
                        return@map JsonPrimitive(litId)
                    }
                    // Slice 10 (v4): list element can be a nested expression.
                    val nestedId = synthesizeNestedIfNested(elt, code, position, line, errors, ctx)
                    if (nestedId != null) {
                        return@map JsonPrimitive(nestedId)
                    }
                    if (elt is Arg.Nested) {
                        return null  // error already recorded
                    }
                    val text = (elt as? Arg.Bare)?.text ?: run {
                        shapeMismatch(line, code, position, "list of bare references", elt, errors)
                        return null
                    }
                    // Slice 7: `@last` resolves to the most recent anonymous id.
                    val resolvedAnon = resolveAtLast(text, line, code, errors, ctx) ?: return null
                    // Slice 3: auto-VarRef applies per-element for
                    // value-position list slots (e.g., Application.arguments).
                    val resolved = maybeAutoVarRef(resolvedAnon, code, spec, ctx)
                    JsonPrimitive(resolved)
                }
                return JsonArray(elements)
            }
            LayerAGrammar.ArgKind.NULLABLE_REF -> {
                // Slice 2: nullable-ref slot can be an inline literal.
                synthesizeLiteralIfLiteral(arg, ctx)?.let { return JsonPrimitive(it) }
                // Slice 10 (v4): nullable-ref slot can be a nested expression.
                synthesizeNestedIfNested(arg, code, position, line, errors, ctx)
                    ?.let { return JsonPrimitive(it) }
                if (arg is Arg.Nested) {
                    return null  // error already recorded
                }
                return when (arg) {
                    is Arg.Bare -> {
                        val resolvedAnon = resolveAtLast(arg.text, line, code, errors, ctx)
                            ?: return null
                        val resolved = maybeAutoVarRef(resolvedAnon, code, spec, ctx)
                        JsonPrimitive(resolved)
                    }
                    Arg.Null -> JsonNull
                    else -> {
                        shapeMismatch(line, code, position, "ref or _", arg, errors)
                        null
                    }
                }
            }
            LayerAGrammar.ArgKind.FIELD_LIST -> {
                // Slice 8 (v2.5): each list entry is either a legacy bare
                // ref (to an existing PFV NodeDecl) or a compact `name=ref`
                // pair. Compact entries synthesize a PFV with an internal
                // author id; the parent PV.fields points at the synthetic.
                //
                // Slice 10 (v4): a compact field's value may be a nested
                // expression `name=(NESTED)` — the parser produces an
                // `Arg.Bare("name=")` (note trailing `=`) followed by the
                // separately-tokenized `Arg.Nested` (the `(` terminates
                // the bare-token sweep mid-stream). The iterator below
                // peeks ahead when it sees `name=` so it can pair the
                // synthetic value into one PFV synthesis.
                val list = (arg as? Arg.Listing)?.items ?: run {
                    shapeMismatch(line, code, position, "[name=ref ...] or [ref ref ...] list", arg, errors)
                    return null
                }
                val elements = mutableListOf<JsonElement>()
                var idx = 0
                while (idx < list.size) {
                    val elt = list[idx]
                    val text = (elt as? Arg.Bare)?.text ?: run {
                        shapeMismatch(line, code, position, "field list entry", elt, errors)
                        return null
                    }
                    if ('=' !in text) {
                        // Legacy bare-ref form; passes through to an existing
                        // PFV NodeDecl. `@last` resolution applies here too.
                        val resolvedAnon = resolveAtLast(text, line, code, errors, ctx)
                            ?: return null
                        elements += JsonPrimitive(resolvedAnon)
                        idx++
                        continue
                    }
                    val name: String
                    val resolvedValue: String
                    if (text.endsWith("=")) {
                        // Slice 10 (v4): split-token form. `name=` is followed
                        // by a separately-tokenized value (Arg.Bare, literal,
                        // or Arg.Nested).
                        name = text.dropLast(1)
                        if (name.isEmpty() || idx + 1 >= list.size) {
                            errors += AuthoringError.ArgShapeMismatch(
                                line = line, code = code, position = position,
                                expectedKind = "compact field `name=value` (value follows the `=`)",
                                actualKind = "malformed `$text` with no following value",
                            )
                            return null
                        }
                        val valueArg = list[idx + 1]
                        val resolved = resolveFieldValue(valueArg, code, position, line, errors, ctx)
                            ?: return null
                        resolvedValue = resolved
                        idx += 2
                    } else {
                        // Slice 8: single-token form `name=value`. Value
                        // is either an inline literal (Int / Float / Bool /
                        // String — Slice 2 composition) or a bare ref.
                        name = text.substringBefore('=')
                        val valueRef = text.substringAfter('=')
                        if (name.isEmpty() || valueRef.isEmpty()) {
                            errors += AuthoringError.ArgShapeMismatch(
                                line = line, code = code, position = position,
                                expectedKind = "compact field `name=value`",
                                actualKind = "malformed `$text`",
                            )
                            return null
                        }
                        // Try parsing `valueRef` as an inline literal first
                        // (Slice 2 composition). Strings would need to be
                        // quoted; the tokenizer doesn't preserve quotes in
                        // bare tokens, so only numeric and boolean literals
                        // are recognized here. Quoted-string field values
                        // come through the split-token form above.
                        val asInt = valueRef.toLongOrNull()
                        val asFloat = if ('.' in valueRef) valueRef.toDoubleOrNull() else null
                        resolvedValue = when {
                            asInt != null -> {
                                val litId = ctx.freshLitId()
                                ctx.synthesized[litId] = buildJsonObject {
                                    put("type", "IntLit")
                                    put("value", asInt)
                                }
                                litId
                            }
                            asFloat != null -> {
                                val litId = ctx.freshLitId()
                                ctx.synthesized[litId] = buildJsonObject {
                                    put("type", "FloatLit")
                                    put("value", asFloat)
                                }
                                litId
                            }
                            valueRef == "true" || valueRef == "false" -> {
                                val litId = ctx.freshLitId()
                                ctx.synthesized[litId] = buildJsonObject {
                                    put("type", "BoolLit")
                                    put("value", valueRef == "true")
                                }
                                litId
                            }
                            else -> {
                                val resolvedAnon = resolveAtLast(valueRef, line, code, errors, ctx)
                                    ?: return null
                                val binderId = ctx.resolveBinder(resolvedAnon)
                                if (binderId != null) {
                                    val varId = ctx.freshVarRefId()
                                    ctx.synthesized[varId] = buildJsonObject {
                                        put("type", "VarRef")
                                        put("binder", binderId)
                                    }
                                    varId
                                } else resolvedAnon
                            }
                        }
                        idx++
                    }
                    // Synthesize a PFV node with an internal author id.
                    val pfvId = "__pfv${ctx.litCounter}_${name}"
                    ctx.litCounter++
                    ctx.synthesized[pfvId] = buildJsonObject {
                        put("type", "ProductFieldValue")
                        put("fieldName", name)
                        put("value", resolvedValue)
                    }
                    elements += JsonPrimitive(pfvId)
                }
                return JsonArray(elements)
            }
            LayerAGrammar.ArgKind.PARAM_LIST -> {
                // Slice 5 (v2): each list entry is either a legacy bare ref
                // (to an existing PRC NodeDecl) or a compact `name:typeRef`
                // pair. Compact entries synthesize a PRC whose author id IS
                // the parameter name (so a Lambda body can reference it
                // directly, and Slice 3 auto-VarRef fires).
                val list = (arg as? Arg.Listing)?.items ?: run {
                    shapeMismatch(line, code, position, "[name:type ...] or [ref ref ...] list", arg, errors)
                    return null
                }
                val elements = list.map { elt ->
                    val text = (elt as? Arg.Bare)?.text ?: run {
                        shapeMismatch(line, code, position, "param list entry", elt, errors)
                        return null
                    }
                    if (':' !in text) {
                        // Legacy bare-ref form; passes through to the existing
                        // PRC NodeDecl. `@last` resolution applies here too.
                        val resolvedAnon = resolveAtLast(text, line, code, errors, ctx)
                            ?: return null
                        return@map JsonPrimitive(resolvedAnon)
                    }
                    val name = text.substringBefore(':')
                    val typeRef = text.substringAfter(':')
                    if (name.isEmpty() || typeRef.isEmpty()) {
                        errors += AuthoringError.ArgShapeMismatch(
                            line = line, code = code, position = position,
                            expectedKind = "compact param `name:typeRef`",
                            actualKind = "malformed `$text`",
                        )
                        return null
                    }
                    // Synthesize a PRC whose author id IS the parameter name.
                    // If the user also declared a PRC with the same name
                    // separately, the synthesized PRC would collide; this is
                    // a user error (don't mix compact + explicit form for the
                    // same name). The synthesized PRC's canonical bytes match
                    // a hand-authored `<name> PRC "<name>" <typeRef>`.
                    //
                    // Cross-LAM collision check: if two distinct LAMs each
                    // declare a compact param with the same name but
                    // different paramTypes, the second synthesis would
                    // silently overwrite the first. The surviving PRC's
                    // paramType is wrong for the first LAM, and if the
                    // paramType creates a Schema↔Invariant→Lambda back-
                    // reference (Q-035 pattern), the canonical encoder
                    // infinite-recurses and blows the stack. Emit a
                    // structured error here so the agent can rename one
                    // of the conflicting params instead.
                    val existing = ctx.synthesized[name]
                    if (existing != null) {
                        val existingParamType = (existing["paramType"] as? JsonPrimitive)?.content
                        if (existingParamType != null && existingParamType != typeRef) {
                            errors += AuthoringError.ArgShapeMismatch(
                                line = line, code = code, position = position,
                                expectedKind = "unique compact-LAM param name across Lambdas",
                                actualKind = "compact param `$name:$typeRef` collides with a prior compact `$name:$existingParamType` — the synthesized PRC would silently alias to the most recent declaration. Rename one of the params (e.g., `${name}_v2`) so each LAM has its own PRC.",
                            )
                            return null
                        }
                    }
                    ctx.synthesized[name] = buildJsonObject {
                        put("type", "ParameterDecl")
                        put("name", name)
                        put("paramType", typeRef)
                    }
                    JsonPrimitive(name)
                }
                return JsonArray(elements)
            }
        }
    }

    /**
     * Slice 7 (v2) — resolve `@last` to the most-recent anonymous id.
     * Returns [refText] unchanged for any other text. Errors out if the
     * `@last` appears before any anonymous declaration has been seen.
     *
     * The lookahead is forward-only because emitJson iterates the
     * NodeDecl list in source order and updates [EmitContext.lastAnonId]
     * after each node. A `@last` in node K can only see anonymous ids
     * declared in nodes 0..K-1.
     */
    private fun resolveAtLast(
        refText: String,
        line: Int,
        code: String,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        if (refText != "@last") return refText
        val anon = ctx.lastAnonId
        if (anon == null) {
            errors += AuthoringError.ArgShapeMismatch(
                line = line, code = code, position = -1,
                expectedKind = "`@last` resolved to a prior anonymous declaration",
                actualKind = "no anonymous declaration seen before this line",
            )
            return null
        }
        return anon
    }

    /**
     * If [arg] is a literal token (Int / Float / Bool / String), synthesize
     * a child literal node and return its synthetic author id. Returns null
     * if [arg] is not a literal — caller continues with the normal reference
     * path.
     *
     * UnitLit and BytesLit are not synthesizable inline: the grammar has no
     * inline-token form for them (`_` already means Null, and bytes are
     * base64-encoded strings — distinguishable from string literals only by
     * type, which the slot doesn't carry). Programs needing those declare
     * `u ULT` or `b BYT "<base64>"` explicitly.
     */
    private fun synthesizeLiteralIfLiteral(arg: Arg, ctx: EmitContext): String? {
        val (jsonType, value) = when (arg) {
            is Arg.IntL -> "IntLit" to JsonPrimitive(arg.value)
            is Arg.FloatL -> "FloatLit" to JsonPrimitive(arg.value)
            is Arg.BoolL -> "BoolLit" to JsonPrimitive(arg.value)
            is Arg.Str -> "StringLit" to JsonPrimitive(arg.value)
            else -> return null
        }
        val id = ctx.freshLitId()
        ctx.synthesized[id] = buildJsonObject {
            put("type", jsonType)
            put("value", value)
        }
        return id
    }

    /**
     * Slice 10 (Layer A density v4) — nested expression synthesis.
     *
     * If [arg] is `Arg.Nested(code, args)`, synthesize a child node by
     * generating a fresh `__expr<n>` id and recursively emitting the
     * nested code+args as a NodeDecl. Returns the synthesized id on
     * success, or null on error (errors recorded in [errors]).
     *
     * The nested code must be `producesValue = true` — type codes (PRM,
     * FNT, PRD, SUM, ...) and structural codes (PRC, Pattern variants,
     * MC, EFC, ESE/ESI/ESO, ...) are rejected as
     * [AuthoringError.ArgShapeMismatch]. This guards against authors
     * writing `(PRM Int)` at a value-position arg slot.
     *
     * Nesting composes recursively: `(APP foo [(APP bar [x])])` emits
     * one outer `__expr0` for the outer APP, then `__expr1` for the
     * inner APP (encountered while emitting the outer's arguments).
     *
     * Composes with Slices 1-3:
     *   * Slice 1 reserved names — `(APP eqInt [n 0])` resolves `eqInt`
     *     to the reserved `eqInt` ForeignNode synthesized at the document
     *     level (the same code path as a top-level NodeDecl).
     *   * Slice 2 inline literals — args inside the nested form can be
     *     literal tokens, synthesizing extra `__lit<n>` children.
     *   * Slice 3 auto-VarRef — PRC binder references inside the nested
     *     args wrap in synthetic VarRefs.
     *
     * Returns null with no error recorded when [arg] is not nested
     * (caller continues with the normal reference path).
     */
    private fun synthesizeNestedIfNested(
        arg: Arg,
        parentCode: String,
        position: Int,
        line: Int,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        if (arg !is Arg.Nested) return null
        val schema = LayerAGrammar.codes[arg.code]
        if (schema == null) {
            errors += AuthoringError.UnknownCode(line = line, code = arg.code)
            return null
        }
        // Distinguish value-position from type-position slots so a nested
        // type code (PRM, PRD, SUM, FNT, RT, RS, ...) is only legal in
        // slots that conceptually carry a type reference. The parent's
        // FieldSpec.acceptsType bit is the gate; absent that bit the
        // nested code must be value-producing.
        val parentSpec = LayerAGrammar.codes[parentCode]
            ?.let { it.required + it.optional }
            ?.getOrNull(position)
        val slotAcceptsType = parentSpec?.acceptsType ?: false
        val allowed = schema.producesValue || (schema.producesType && slotAcceptsType)
        if (!allowed) {
            val hint = when {
                arg.code == "RS" -> " (code `RS` is type-only and cannot be nested — declare a standalone `id RS` node inside the lexical RT body and reference it by id, otherwise the synthesized node loses its RecursiveType binder context and the verifier reports `UnboundRecursiveSelf`)"
                schema.producesType && !slotAcceptsType -> " (code `${arg.code}` produces a type but slot `${parentSpec?.jsonField ?: "?"}` carries a value)"
                else -> " (code `${arg.code}` is type-only or structural — declare it as a standalone node and reference by id)"
            }
            errors += AuthoringError.ArgShapeMismatch(
                line = line, code = parentCode, position = position,
                expectedKind = "value-producing nested expression$hint",
                actualKind = "nested `(${arg.code} ...)`",
            )
            return null
        }
        // Generate a fresh internal id. The synthesized NodeDecl is fed
        // through `emitNode` so the per-code schema validation, sugar
        // dispatch (IF/WHEN), and slot-aware reference resolution all run
        // recursively — nested expressions reuse the same code paths as
        // top-level declarations.
        val id = ctx.freshExprId()
        val childDecl = NodeDecl(id = id, code = arg.code, args = arg.args, line = line)
        val childJson = emitNode(childDecl, errors, ctx) ?: return null
        ctx.synthesized[id] = childJson
        return id
    }

    /**
     * Slice 3 auto-VarRef. If [refText] resolves to a `PRC` or `LET`
     * binder declared elsewhere in the document AND the parent slot is
     * an expression value-position (see [isValuePositionRefSlot]),
     * synthesize an intermediate VarRef and return ITS id; otherwise
     * return [refText] unchanged.
     *
     * The reason for the value-position restriction: the same identifier
     * can appear as a binder declaration (e.g., `Lambda.parameters: [x]`,
     * `Fixpoint.recursionType: factT`) or as a use of that binder (e.g.,
     * `Application.arguments: [x]`). Wrapping the binder declaration in a
     * VarRef would break the canonical encoder, which expects the binder
     * id directly in those slots. The slot list in [isValuePositionRefSlot]
     * names the value-positions; everything else is treated as a structural
     * reference and passed through verbatim.
     */
    private fun maybeAutoVarRef(
        refText: String,
        parentCode: String,
        parentSpec: LayerAGrammar.FieldSpec,
        ctx: EmitContext,
    ): String {
        val binderId = ctx.resolveBinder(refText) ?: return refText
        if (!isValuePositionRefSlot(parentCode, parentSpec.jsonField)) return refText
        val id = ctx.freshVarRefId()
        ctx.synthesized[id] = buildJsonObject {
            put("type", "VarRef")
            put("binder", binderId)
        }
        return id
    }

    /**
     * True if a reference at slot ([parentCode], [jsonField]) is an
     * expression value-position — i.e., a place where the referenced node
     * is evaluated as a value. False for binder-list slots (a Lambda's
     * `parameters`), type-reference slots (`paramType`, `result`,
     * `recursionType`, `ofType`, `patternType`, ...), and other structural
     * references.
     */
    private fun isValuePositionRefSlot(parentCode: String, jsonField: String): Boolean =
        when (parentCode) {
            "APP" -> jsonField == "function" || jsonField == "arguments"
            "LET" -> jsonField == "value" || jsonField == "body"
            "MC" -> jsonField == "body"
            "MAT" -> jsonField == "scrutinee"
            "PV" -> false  // ofType is structural; fields list is PFV refs
            "PFV" -> jsonField == "value"
            "PFG" -> jsonField == "target"
            "SV" -> jsonField == "payload"
            "FIX" -> jsonField == "body"
            "LAM" -> jsonField == "body"
            "TAB" -> jsonField == "body"
            "H" -> jsonField == "handle" || jsonField == "body"  // intercept is the EffectCategory id, structural
            "CAP" -> jsonField == "body"
            "TR" -> jsonField == "guard" || jsonField == "body"
            "SM" -> jsonField == "transitionFn" || jsonField == "initialState"
            "INV" -> jsonField == "body"
            "NRF" -> false  // NodeRef target is a node id, not a value
            else -> false
        }

    private fun shapeMismatch(
        line: Int,
        code: String,
        position: Int,
        expected: String,
        got: Arg,
        errors: MutableList<AuthoringError>,
    ) {
        errors += AuthoringError.ArgShapeMismatch(
            line = line,
            code = code,
            position = position,
            expectedKind = expected,
            actualKind = describeArg(got),
        )
    }

    private fun describeArg(arg: Arg): String = when (arg) {
        is Arg.Bare -> "bare token '${arg.text}'"
        is Arg.Str -> "string \"${arg.value.take(20)}\""
        is Arg.IntL -> "integer ${arg.value}"
        is Arg.FloatL -> "float ${arg.value}"
        is Arg.BoolL -> "${arg.value}"
        is Arg.Listing -> "list of ${arg.items.size}"
        is Arg.Nested -> "nested `(${arg.code} ...)`"
        Arg.Null -> "null '_'"
    }
}
