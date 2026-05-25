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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * dag-json → [LayerADocument] translator. The inverse of [DagJsonEmitter]
 * (which goes Layer A → dag-json). Used to mechanize Layer A authoring for
 * the existing canonical corpus: parse a dag-json document, produce a
 * structurally-equivalent Layer A document, render to compact text via
 * [LayerARenderer]. Round-tripping (translate → render → parse → emit →
 * ingest → finalize) produces canonical bytes equal to the original — the
 * property [LayerARoundTripTest] asserts for every translated corpus entry.
 *
 * The translator reads each node's `type` field, looks up the matching
 * [LayerAGrammar.CodeSchema] (resolving variant-bearing categories via
 * the schema's discriminator pair when present — Pattern.kind,
 * EventStream.streamKind), and walks the per-code field list to build an
 * argument list of the right [Arg] shapes.
 *
 * Returns null with errors accumulated when the JSON references a type
 * not present in the grammar (e.g., Name N-030, Provenance N-031 — the
 * two metadata-only categories not yet mapped to Layer A codes) or a
 * required field is missing.
 */
object LayerATranslator {

    /**
     * Translate dag-json [text] into a [LayerADocument]. Throws
     * [AuthoringException] when the JSON cannot be fully expressed in the
     * current Layer A grammar (e.g., uses Name / Provenance node categories
     * which have no Layer A code today).
     */
    fun translate(text: String): LayerADocument {
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
        // Author-id iteration order preserves the JSON document's order
        // (JsonObject is a LinkedHashMap-backed structure in kotlinx-
        // serialization), which keeps the rendered Layer A's line order
        // consistent with the source document.
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
        return LayerADocument(
            version = version,
            rootId = rootId,
            nodes = nodeDecls,
        )
    }

    /**
     * Translate one node's JSON object into a [NodeDecl]. Returns null
     * (with errors recorded) when the JSON type is not mapped to any
     * Layer A code or a required field is missing / malformed.
     */
    private fun translateNode(
        authorId: String,
        nodeObj: JsonObject,
        errors: MutableList<AuthoringError>,
    ): NodeDecl? {
        val jsonType = (nodeObj["type"] as? JsonPrimitive)?.contentOrNull
            ?: run {
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' missing 'type' field",
                )
                return null
            }

        // Resolve the Layer A code. For variant-bearing categories
        // (Pattern, EventStream), the discriminator field in JSON
        // (e.g., "kind": "literal") disambiguates among multiple codes
        // sharing the same jsonType. We pick the first code whose
        // discriminator matches OR whose discriminator is null and
        // jsonType matches.
        val code = resolveCode(jsonType, nodeObj) ?: run {
            errors += AuthoringError.HeaderError(
                line = 0,
                detail = "node '$authorId' (type=$jsonType) has no matching Layer A code " +
                    "— Name / Provenance / unmapped categories are not yet translatable",
            )
            return null
        }
        val schema = LayerAGrammar.codes.getValue(code)

        // Build the args list in (required then optional) declaration order.
        val args = mutableListOf<Arg>()
        for (spec in schema.required) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                // NULLABLE_REF required fields (e.g., SumValue.payload for
                // nullary case `None`) tolerate absent / explicit-null JSON
                // by emitting Arg.Null. Other required kinds error out.
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
        // (e.g., APP's typeArguments must come BEFORE effectInstances). When
        // a LATER optional slot is present but an EARLIER one is absent,
        // emit a placeholder for the earlier slot so the positional encoding
        // remains correct. For LIST_REF placeholders we emit an empty list;
        // for other absent-but-required-by-position kinds, we'd error
        // (currently only LIST_REF optionals participate in this pattern).
        val lastPresentOpt: Int = schema.optional.indices.lastOrNull { i ->
            val f = nodeObj[schema.optional[i].jsonField]
            f != null && f !is JsonNull
        } ?: -1
        for ((j, spec) in schema.optional.withIndex()) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                if (j < lastPresentOpt) {
                    // Hole: a later optional is present; emit a placeholder.
                    args += placeholderFor(spec, authorId, errors) ?: return null
                }
                continue
            }
            val arg = jsonToArg(spec, jsonField, authorId, errors) ?: return null
            args += arg
        }

        return NodeDecl(
            id = authorId,
            code = code,
            args = args,
            line = 0,  // synthetic — translation has no source line context
        )
    }

    /**
     * Pick the Layer A code matching [jsonType] in [nodeObj]. For variant-
     * bearing categories, the discriminator pair (jsonField, value) must
     * match the JSON's actual values. For non-variant categories, the
     * first (and only) code with matching jsonType wins.
     */
    private fun resolveCode(jsonType: String, nodeObj: JsonObject): String? {
        // Pass 1: exact discriminator match (skip sugar-only codes).
        for ((code, schema) in LayerAGrammar.codes) {
            if (schema.sugarOnly) continue
            if (schema.jsonType != jsonType) continue
            val disc = schema.discriminator ?: continue
            val (field, expectedValue) = disc
            val actualValue = (nodeObj[field] as? JsonPrimitive)?.contentOrNull
            if (actualValue == expectedValue) return code
        }
        // Pass 2: any non-variant (discriminator-less) code with matching
        // type. Skip sugar-only codes — IF/WHEN have jsonType="Match" but
        // there's no unambiguous reverse mapping; the canonical MAT code
        // is the right pick.
        for ((code, schema) in LayerAGrammar.codes) {
            if (schema.sugarOnly) continue
            if (schema.jsonType == jsonType && schema.discriminator == null) return code
        }
        return null
    }

    /**
     * Convert a JSON value to the [Arg] shape the field's [ArgKind] expects.
     * Returns null with an error recorded when the JSON shape doesn't
     * match what the field declares.
     */
    private fun jsonToArg(
        spec: LayerAGrammar.FieldSpec,
        json: JsonElement,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? {
        return when (spec.kind) {
            LayerAGrammar.ArgKind.REFERENCE -> {
                val text = (json as? JsonPrimitive)?.contentOrNull
                    ?: run { typeError(spec, "string ref", json, authorId, errors); return null }
                Arg.Bare(text)
            }
            LayerAGrammar.ArgKind.KEYWORD -> {
                val text = (json as? JsonPrimitive)?.contentOrNull
                    ?: run { typeError(spec, "keyword string", json, authorId, errors); return null }
                Arg.Bare(text)
            }
            LayerAGrammar.ArgKind.STRING -> {
                val text = (json as? JsonPrimitive)?.contentOrNull
                    ?: run { typeError(spec, "string", json, authorId, errors); return null }
                Arg.Str(text)
            }
            LayerAGrammar.ArgKind.INT -> {
                val value = (json as? JsonPrimitive)?.longOrNull
                    ?: run { typeError(spec, "int", json, authorId, errors); return null }
                Arg.IntL(value)
            }
            LayerAGrammar.ArgKind.FLOAT -> {
                val value = (json as? JsonPrimitive)?.doubleOrNull
                    ?: run { typeError(spec, "float", json, authorId, errors); return null }
                Arg.FloatL(value)
            }
            LayerAGrammar.ArgKind.BOOL -> {
                val value = (json as? JsonPrimitive)?.booleanOrNull
                    ?: run { typeError(spec, "bool", json, authorId, errors); return null }
                Arg.BoolL(value)
            }
            LayerAGrammar.ArgKind.LIST_REF -> {
                val arr = json as? JsonArray
                    ?: run { typeError(spec, "ref array", json, authorId, errors); return null }
                Arg.Listing(arr.map { elt ->
                    val text = (elt as? JsonPrimitive)?.contentOrNull
                        ?: run { typeError(spec, "ref in array", elt, authorId, errors); return null }
                    Arg.Bare(text)
                })
            }
            LayerAGrammar.ArgKind.NULLABLE_REF -> {
                if (json is JsonNull) return Arg.Null
                val text = (json as? JsonPrimitive)?.contentOrNull
                    ?: run { typeError(spec, "nullable ref", json, authorId, errors); return null }
                Arg.Bare(text)
            }
            LayerAGrammar.ArgKind.PARAM_LIST -> {
                // Slice 5 (v2): reverse-translate as a legacy bare-ref list.
                // The compact `name:typeRef` form is emitter-only; the
                // canonical JSON always carries an explicit PRC declaration
                // plus a ref in the Lambda's parameters array, and that's
                // what we re-emit as a Layer A list.
                val arr = json as? JsonArray
                    ?: run { typeError(spec, "ref array", json, authorId, errors); return null }
                Arg.Listing(arr.map { elt ->
                    val text = (elt as? JsonPrimitive)?.contentOrNull
                        ?: run { typeError(spec, "ref in array", elt, authorId, errors); return null }
                    Arg.Bare(text)
                })
            }
        }
    }

    /**
     * Placeholder for an absent optional slot when a later optional slot
     * is present (positional encoding requires the earlier slot to occupy
     * its index). Only LIST_REF and NULLABLE_REF kinds have meaningful
     * placeholders (empty list and `_` respectively); other kinds aren't
     * expected to participate in this pattern but error if encountered.
     */
    private fun placeholderFor(
        spec: LayerAGrammar.FieldSpec,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? {
        return when (spec.kind) {
            LayerAGrammar.ArgKind.LIST_REF -> Arg.Listing(emptyList())
            LayerAGrammar.ArgKind.NULLABLE_REF -> Arg.Null
            else -> {
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' has a hole at optional '${spec.jsonField}' " +
                        "of non-list-non-nullable kind ${spec.kind}; cannot synthesize placeholder",
                )
                null
            }
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
}
