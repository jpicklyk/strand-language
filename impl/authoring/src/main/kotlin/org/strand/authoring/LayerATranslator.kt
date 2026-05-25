package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Canonical dag-json → [LayerADocument] translator. Inverse of [DagJsonEmitter].
 *
 * **Step 1 scope (Q-036 reverse projection, first slice).** This implementation
 * produces canonical-form Layer A only: always `MAT` (never `IF` / `WHEN`),
 * always bare-ref lists for `LAM.parameters` and `PV.fields` (never the compact
 * `name:typeRef` or `name=ref` density forms), and every field present (no
 * elaboration-aware field omission). Density-sugar projection and
 * elaboration-omission land in subsequent steps; the surface here is the
 * shell the later passes plug into.
 *
 * Round-trip property (asserted by `LayerAReverseRoundTripTest`):
 * for every corpus dag-json document, parsing the rendered Layer A text via
 * [Authoring.compileToDagJson] produces canonical bytes with hash equality
 * to the original.
 *
 * The walk visits `nodes` in JSON document order (which kotlinx-serialization's
 * [JsonObject] preserves) so the rendered output's node order matches the
 * source's. Each node's `type` field is looked up against [LayerAGrammar.codes];
 * variant-bearing categories (Pattern, EventStream) are disambiguated by the
 * schema's `discriminator` pair. Sugar codes (`IF`, `WHEN`) share `jsonType`
 * with the canonical `MAT` code; Step 1 skips them so the canonical code
 * wins. Step 4 of the proposal will detect their structural trigger patterns
 * and prefer the sugar where applicable.
 */
object LayerATranslator {

    /**
     * Sugar codes that share a `jsonType` with a canonical code (today: `IF`
     * and `WHEN` both share `"Match"` with `MAT`). Step 1's translator skips
     * them during code resolution; the canonical `MAT` is the right pick
     * when no structural detection is in play. A future step that adds
     * density-sugar projection will positively detect IF/WHEN trigger
     * patterns and override this default.
     */
    private val SUGAR_CODES = setOf("IF", "WHEN")

    /**
     * Codes whose optional fields are always emitted explicitly (as empty
     * list / null when absent in the source JSON). This bypasses Elaborator
     * inference that could unsafely change the recompiled canonical bytes.
     *
     * Today this is `APP` only: case 2 (effectInstances defaulting) can
     * select an EffectDecl whose parameter VarRefs reference binders out of
     * scope at the call site — corpus programs 33-35 exhibit this. Always
     * emitting an explicit `effectInstances` field (empty list when absent)
     * forces the Elaborator's `node.args.size !in 2..3` short-circuit so the
     * inference doesn't fire. The canonical CBOR encoder gates effectInstances
     * on non-empty so byte-equality is preserved.
     */
    private val FORCE_ALL_OPTIONALS = setOf("APP")

    /**
     * Translate dag-json [text] into a [LayerADocument]. Applies the
     * Q-036 SAFE elaboration-omission rules (Step 2): the Elaborator's
     * deterministically-reproducible inferences (Lambda.effects from the
     * body's closure; recursion-slot paramType from FIX.recursionType) are
     * stripped from the output so the rendered Layer A is more compact and
     * matches the form an LLM would emit. The Elaborator re-derives the
     * stripped fields on the way back through forward compilation; the
     * round-trip hash is preserved.
     *
     * Density sugars (IF/WHEN/compact LAM/inline literals/etc.) and the
     * probe-and-fallback path for BORDERLINE elaboration cases land in
     * subsequent steps.
     */
    fun translate(text: String): LayerADocument {
        val canonical = translateCanonical(text)
        return omitSafelyInferableFields(canonical)
    }

    /** Translate without any elaboration-omission. Useful for testing and
     *  as the structural building block Step 2's omission pass works against. */
    fun translateCanonical(text: String): LayerADocument {
        val parser = Json { ignoreUnknownKeys = true }
        val root = parser.parseToJsonElement(text) as? JsonObject
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "dag-json document must be a JSON object")
            ))
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-integer 'version' field")
            ))
        val rootId = (root["root"] as? JsonPrimitive)?.contentOrNull
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-string 'root' field")
            ))
        val nodesObj = root["nodes"] as? JsonObject
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-object 'nodes' field")
            ))

        val errors = mutableListOf<AuthoringError>()
        val nodeDecls = mutableListOf<NodeDecl>()
        for ((authorId, nodeJson) in nodesObj) {
            val nodeObj = nodeJson as? JsonObject
            if (nodeObj == null) {
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' must be a JSON object",
                )
                continue
            }
            val decl = translateNode(authorId, nodeObj, errors) ?: continue
            nodeDecls += decl
        }
        if (errors.isNotEmpty()) throw AuthoringException(errors)
        return LayerADocument(version = version, rootId = rootId, nodes = nodeDecls)
    }

    private fun translateNode(
        authorId: String,
        nodeObj: JsonObject,
        errors: MutableList<AuthoringError>,
    ): NodeDecl? {
        val jsonType = (nodeObj["type"] as? JsonPrimitive)?.contentOrNull
            ?: run {
                errors += AuthoringError.HeaderError(line = 0, detail = "node '$authorId' missing 'type' field")
                return null
            }

        val code = resolveCode(jsonType, nodeObj) ?: run {
            errors += AuthoringError.HeaderError(
                line = 0,
                detail = "node '$authorId' (type=$jsonType) has no matching Layer A code — Name / Provenance / unmapped categories are not yet translatable",
            )
            return null
        }
        val schema = LayerAGrammar.codes.getValue(code)

        val args = mutableListOf<Arg>()
        for (spec in schema.required) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                // NULLABLE_REF required fields (e.g., SumValue.payload for
                // nullary case `None`) tolerate absent/explicit-null JSON by
                // emitting Arg.Null. Other required kinds error.
                if (spec.kind == LayerAGrammar.ArgKind.NULLABLE_REF) {
                    args += Arg.Null
                    continue
                }
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' (code=$code) missing required field '${spec.jsonField}'",
                )
                return null
            }
            val arg = jsonToArg(spec, jsonField, authorId, errors) ?: return null
            args += arg
        }

        // Optional slots: per Layer A grammar, positional order is fixed
        // (e.g., APP's typeArguments must come before effectInstances). When
        // a LATER optional slot is present but an EARLIER one is absent,
        // emit a placeholder for the earlier slot so positional encoding
        // stays correct (empty list for LIST_REF, `_` for NULLABLE_REF).
        //
        // For codes in [FORCE_ALL_OPTIONALS], we always emit every optional
        // slot explicitly even when absent. This blocks Elaborator inference
        // that would otherwise fire on the recompiled output (e.g., case 2 —
        // Application.effectInstances defaulting from capability context —
        // can unsafely insert an EffectDecl whose parameter VarRefs reference
        // binders out of scope at the call site). The canonical encoder
        // gates these slots on non-default values so emitting explicit empty
        // lists preserves byte-identical canonical bytes.
        val forceAll = code in FORCE_ALL_OPTIONALS
        val lastPresentOpt: Int = if (forceAll) {
            schema.optional.lastIndex
        } else {
            schema.optional.indices.lastOrNull { i ->
                val f = nodeObj[schema.optional[i].jsonField]
                f != null && f !is JsonNull
            } ?: -1
        }
        for ((j, spec) in schema.optional.withIndex()) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                if (j <= lastPresentOpt) {
                    args += placeholderFor(spec, authorId, errors) ?: return null
                }
                continue
            }
            val arg = jsonToArg(spec, jsonField, authorId, errors) ?: return null
            args += arg
        }

        return NodeDecl(id = authorId, code = code, args = args, line = 0)
    }

    /**
     * Pick the Layer A code matching [jsonType]. Pass 1 looks for variant-
     * bearing categories where the discriminator pair (jsonField, value)
     * matches. Pass 2 picks any non-variant code with matching `jsonType`.
     * Sugar codes are skipped both passes so the canonical code wins; later
     * steps' sugar projection will override this default by positive
     * structural detection.
     */
    private fun resolveCode(jsonType: String, nodeObj: JsonObject): String? {
        for ((code, schema) in LayerAGrammar.codes) {
            if (code in SUGAR_CODES) continue
            if (schema.jsonType != jsonType) continue
            val disc = schema.discriminator ?: continue
            val (field, expectedValue) = disc
            val actualValue = (nodeObj[field] as? JsonPrimitive)?.contentOrNull
            if (actualValue == expectedValue) return code
        }
        for ((code, schema) in LayerAGrammar.codes) {
            if (code in SUGAR_CODES) continue
            if (schema.jsonType == jsonType && schema.discriminator == null) return code
        }
        return null
    }

    private fun jsonToArg(
        spec: LayerAGrammar.FieldSpec,
        json: JsonElement,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? = when (spec.kind) {
        LayerAGrammar.ArgKind.REFERENCE,
        LayerAGrammar.ArgKind.KEYWORD -> {
            val text = (json as? JsonPrimitive)?.contentOrNull
            if (text == null) { typeError(spec, "string", json, authorId, errors); null }
            else Arg.Bare(text)
        }
        LayerAGrammar.ArgKind.STRING -> {
            val text = (json as? JsonPrimitive)?.contentOrNull
            if (text == null) { typeError(spec, "string", json, authorId, errors); null }
            else Arg.Str(text)
        }
        LayerAGrammar.ArgKind.INT -> {
            val value = (json as? JsonPrimitive)?.longOrNull
            if (value == null) { typeError(spec, "int", json, authorId, errors); null }
            else Arg.IntL(value)
        }
        LayerAGrammar.ArgKind.FLOAT -> {
            val value = (json as? JsonPrimitive)?.doubleOrNull
            if (value == null) { typeError(spec, "float", json, authorId, errors); null }
            else Arg.FloatL(value)
        }
        LayerAGrammar.ArgKind.BOOL -> {
            val value = (json as? JsonPrimitive)?.booleanOrNull
            if (value == null) { typeError(spec, "bool", json, authorId, errors); null }
            else Arg.BoolL(value)
        }
        LayerAGrammar.ArgKind.LIST_REF,
        LayerAGrammar.ArgKind.PARAM_LIST,
        LayerAGrammar.ArgKind.FIELD_LIST -> {
            // Step 1: emit legacy bare-ref lists for all three list-shaped
            // kinds. The compact `name:typeRef` (PARAM_LIST) and `name=ref`
            // (FIELD_LIST) density forms are emitter-only sugars; future
            // steps will project them when their trigger patterns match.
            val arr = json as? JsonArray
            if (arr == null) {
                typeError(spec, "ref array", json, authorId, errors); null
            } else {
                val items = arr.mapNotNull { elt ->
                    val text = (elt as? JsonPrimitive)?.contentOrNull
                    if (text == null) { typeError(spec, "ref in array", elt, authorId, errors); null }
                    else Arg.Bare(text)
                }
                if (items.size == arr.size) Arg.Listing(items) else null
            }
        }
        LayerAGrammar.ArgKind.NULLABLE_REF -> {
            if (json is JsonNull) Arg.Null
            else {
                val text = (json as? JsonPrimitive)?.contentOrNull
                if (text == null) { typeError(spec, "nullable ref", json, authorId, errors); null }
                else Arg.Bare(text)
            }
        }
    }

    private fun placeholderFor(
        spec: LayerAGrammar.FieldSpec,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? = when (spec.kind) {
        LayerAGrammar.ArgKind.LIST_REF -> Arg.Listing(emptyList())
        LayerAGrammar.ArgKind.NULLABLE_REF -> Arg.Null
        else -> {
            errors += AuthoringError.HeaderError(
                line = 0,
                detail = "node '$authorId' has a hole at optional '${spec.jsonField}' of non-list-non-nullable kind ${spec.kind}; cannot synthesize placeholder",
            )
            null
        }
    }

    private fun typeError(
        spec: LayerAGrammar.FieldSpec,
        expected: String,
        actual: JsonElement,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ) {
        errors += AuthoringError.HeaderError(
            line = 0,
            detail = "node '$authorId' field '${spec.jsonField}': expected $expected, got ${actual::class.simpleName}",
        )
    }

    // ========================================================================
    // Step 2 — Static SAFE elaboration omission
    // ========================================================================

    /**
     * Apply Step 2's SAFE omission rules.
     *
     * Recursion-slot `paramType` is stripped: the Elaborator's case 5 sets
     * the first parameter's type of a FIX body Lambda from
     * `FIX.recursionType`, which equals the original by the verifier's
     * Fixpoint shape check, so re-derivation reproduces the same value.
     *
     * `Lambda.effects` was initially classified SAFE (per the Step 2
     * research) but corpus programs 12, 13, 14 surfaced an
     * over-declaration counter-example: a Lambda may legally declare more
     * effects than its body's closure produces (e.g., for hand-off through
     * a CapabilityScope, or for forward-compatibility with future body
     * extensions), and the Elaborator's case 1 fires only when the closure
     * is non-empty AND inserts the *closure*, not the declared set. Strict
     * static omission therefore changes canonical bytes for over-declared
     * Lambdas. The rule is demoted to BORDERLINE; the probe-and-fallback
     * path in Step 3 handles it correctly.
     */
    private fun omitSafelyInferableFields(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val recursionSlotPrcIds = recursionSlotPrcs(doc, byId)
        val newNodes = doc.nodes.map { node ->
            when (node.code) {
                "PRC" -> if (node.id in recursionSlotPrcIds) stripPrcParamType(node) else node
                else -> node
            }
        }
        return doc.copy(nodes = newNodes)
    }

    /**
     * Find every PRC node that is the FIRST parameter of a Lambda that is
     * the body of a FIX. Those PRCs' `paramType` equals `FIX.recursionType`
     * by construction and is recoverable by the Elaborator's case 5.
     */
    private fun recursionSlotPrcs(
        doc: LayerADocument,
        byId: Map<String, NodeDecl>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        for (fix in doc.nodes.filter { it.code == "FIX" }) {
            // FIX args: recursionType (0), body (1)
            val bodyRef = (fix.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
            val body = byId[bodyRef] ?: continue
            if (body.code != "LAM") continue
            // LAM args: parameters (0), body (1), [effects (2)]
            val params = (body.args.getOrNull(0) as? Arg.Listing) ?: continue
            val firstParam = (params.items.firstOrNull() as? Arg.Bare)?.text ?: continue
            val prc = byId[firstParam] ?: continue
            if (prc.code != "PRC") continue
            result += prc.id
        }
        return result
    }

    /**
     * Drop the optional `paramType` argument from a PRC declaration. PRC
     * args are: name (required), paramType (optional). When present,
     * paramType is at index 1; truncating to 1 arg removes it.
     */
    private fun stripPrcParamType(node: NodeDecl): NodeDecl {
        if (node.args.size <= 1) return node
        return node.copy(args = node.args.subList(0, 1))
    }
}
