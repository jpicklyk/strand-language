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
        val synthesized: LinkedHashMap<String, JsonObject> = linkedMapOf()

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
         * pre-pass below adds those names to binderIds so the auto-
         * VarRef rule fires correctly when the Lambda body references
         * the parameter by name.
         */
        val binderIds: Set<String> = run {
            val ids = mutableSetOf<String>()
            for (node in doc.nodes) {
                if (node.code == "PRC") ids += node.id
                // Slice 5: scan LAM's parameters list for compact-form
                // entries (`name:typeRef`) and treat each `name` as a
                // binder. Legacy bare-ref entries are already covered by
                // the PRC NodeDecl scan above.
                if (node.code == "LAM") {
                    val params = node.args.firstOrNull() as? Arg.Listing ?: continue
                    for (entry in params.items) {
                        val text = (entry as? Arg.Bare)?.text ?: continue
                        if (':' in text) ids += text.substringBefore(':')
                    }
                }
            }
            ids
        }

        fun freshLitId(): String = "__lit${litCounter++}"
        fun freshVarRefId(): String = "__var${varRefCounter++}"
        fun freshIfPrefix(): String = "__if${ifCounter++}"
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
     * Resolve a single expression-position [arg] to its dag-json id text.
     * Applies Slice 2 inline-literal synthesis and Slice 3 auto-VarRef
     * (treating the slot as a value-position REFERENCE). Returns null on
     * shape mismatch (errors recorded).
     */
    private fun resolveExpressionRef(
        line: Int,
        code: String,
        position: Int,
        arg: Arg,
        errors: MutableList<AuthoringError>,
        ctx: EmitContext,
    ): String? {
        synthesizeLiteralIfLiteral(arg, ctx)?.let { return it }
        val text = (arg as? Arg.Bare)?.text ?: run {
            shapeMismatch(line, code, position, "bare reference or inline literal", arg, errors)
            return null
        }
        // Slice 7: resolve `@last` to the most-recent anonymous id.
        val resolved = resolveAtLast(text, line, code, errors, ctx) ?: return null
        // Treat the slot as value-position; auto-VarRef PRC binders.
        return if (resolved in ctx.binderIds) {
            val id = ctx.freshVarRefId()
            ctx.synthesized[id] = buildJsonObject {
                put("type", "VarRef")
                put("binder", resolved)
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
        if (refText !in ctx.binderIds) return refText
        if (!isValuePositionRefSlot(parentCode, parentSpec.jsonField)) return refText
        val id = ctx.freshVarRefId()
        ctx.synthesized[id] = buildJsonObject {
            put("type", "VarRef")
            put("binder", refText)
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
        Arg.Null -> "null '_'"
    }
}
