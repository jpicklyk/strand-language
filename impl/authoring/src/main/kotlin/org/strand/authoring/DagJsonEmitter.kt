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
        val synthesized: LinkedHashMap<String, JsonObject> = linkedMapOf()

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
         */
        val binderIds: Set<String> = doc.nodes
            .filter { it.code == "PRC" }
            .map { it.id }
            .toSet()

        fun freshLitId(): String = "__lit${litCounter++}"
        fun freshVarRefId(): String = "__var${varRefCounter++}"
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
                // Slice 3: auto-VarRef when the parent slot is an expression
                // value-position (see [isValuePositionRefSlot]) and the
                // reference points at a PRC or LET binder.
                val resolved = maybeAutoVarRef(text, code, spec, ctx)
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
                    // Slice 3: auto-VarRef applies per-element for
                    // value-position list slots (e.g., Application.arguments).
                    val resolved = maybeAutoVarRef(text, code, spec, ctx)
                    JsonPrimitive(resolved)
                }
                return JsonArray(elements)
            }
            LayerAGrammar.ArgKind.NULLABLE_REF -> {
                // Slice 2: nullable-ref slot can be an inline literal.
                synthesizeLiteralIfLiteral(arg, ctx)?.let { return JsonPrimitive(it) }
                return when (arg) {
                    is Arg.Bare -> {
                        val resolved = maybeAutoVarRef(arg.text, code, spec, ctx)
                        JsonPrimitive(resolved)
                    }
                    Arg.Null -> JsonNull
                    else -> {
                        shapeMismatch(line, code, position, "ref or _", arg, errors)
                        null
                    }
                }
            }
        }
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
