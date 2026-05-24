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
        val nodesObj = buildJsonObject {
            for (node in doc.nodes) {
                val nodeJson = emitNode(node, errors) ?: continue
                put(node.id, nodeJson)
            }
        }
        if (errors.isNotEmpty()) throw AuthoringException(errors)
        return buildJsonObject {
            put("version", doc.version)
            put("root", doc.rootId)
            put("nodes", nodesObj)
        }
    }

    private fun emitNode(node: NodeDecl, errors: MutableList<AuthoringError>): JsonObject? {
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
        // Emitted before the positional fields so the JSON appears in the
        // same key order as a hand-authored canonical document (helps when
        // round-trip diagnostics show source vs. compiled side-by-side).
        schema.discriminator?.let { (field, value) ->
            fields[field] = JsonPrimitive(value)
        }

        // Required slots.
        for ((i, spec) in schema.required.withIndex()) {
            val arg = node.args[i]
            val value = argToJson(node.line, node.code, i, spec, arg, errors) ?: return null
            fields[spec.jsonField] = value
        }
        // Optional slots (consumed in declaration order from the tail).
        for ((j, spec) in schema.optional.withIndex()) {
            val i = schema.required.size + j
            if (i >= node.args.size) break
            val arg = node.args[i]
            val value = argToJson(node.line, node.code, i, spec, arg, errors) ?: return null
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
    ): JsonElement? {
        when (spec.kind) {
            LayerAGrammar.ArgKind.REFERENCE -> {
                val text = (arg as? Arg.Bare)?.text ?: run {
                    shapeMismatch(line, code, position, "bare reference", arg, errors)
                    return null
                }
                return JsonPrimitive(text)
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
                // FloatL holds the dotted form; an IntL would lose the dot,
                // so we require the explicit float lexical form here.
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
                    val text = (elt as? Arg.Bare)?.text ?: run {
                        shapeMismatch(line, code, position, "list of bare references", elt, errors)
                        return null
                    }
                    JsonPrimitive(text)
                }
                return JsonArray(elements)
            }
            LayerAGrammar.ArgKind.NULLABLE_REF -> {
                return when (arg) {
                    is Arg.Bare -> JsonPrimitive(arg.text)
                    Arg.Null -> JsonNull
                    else -> {
                        shapeMismatch(line, code, position, "ref or _", arg, errors)
                        null
                    }
                }
            }
        }
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
