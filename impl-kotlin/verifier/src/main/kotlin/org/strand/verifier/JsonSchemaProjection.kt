package org.strand.verifier

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.strand.core.Primitive

/**
 * Translator from Strand [TypeExpr] to JSON Schema for the agent-native
 * LLM tool-use protocol (Q-037 § 3.8.1).
 *
 * The translator supports an *irreducible subset* of TypeExpr — the
 * variants whose semantics map cleanly to JSON Schema's constrained-
 * decoding shape used by Anthropic's tool_use, OpenAI's
 * `response_format: json_schema`, and Gemini's `responseSchema`. Variants
 * that have no JSON Schema representation ([TypeExpr.Fun],
 * [TypeExpr.Forall]) are explicitly rejected with a structured
 * [Rejected] result rather than silently producing an under-specified
 * schema.
 *
 * The two-tier validation contract (proposal § 3.8.1):
 *
 *  1. The provider receives a JSON Schema (the subset expressible here)
 *     and constrains its decoding so the tool input it emits parses to
 *     a value of the declared TypeExpr.
 *  2. After the provider returns a value, Strand parses it and
 *     re-validates against the *full* Schema (including non-translatable
 *     invariants like `x + y < 100`) via `SchemaChecker`.
 *
 * The result type is a sealed class so callers (the LLM Generate
 * builtins, the future ToolParamTypeUnsupported verifier rule) can
 * distinguish "this type projects" from "this type is rejected" without
 * inspecting strings.
 */
object JsonSchemaProjection {

    /** Result of attempting to project a [TypeExpr] to JSON Schema. */
    sealed class Result {
        /** The type was projected; [schema] is the JSON Schema fragment. */
        data class Success(val schema: JsonElement) : Result()

        /**
         * The type was rejected. [reason] is one of the rejection categories
         * documented in proposal § 3.8.1; [type] is the offending TypeExpr.
         */
        data class Rejected(val type: TypeExpr, val reason: Reason) : Result()

        enum class Reason {
            /** [TypeExpr.Fun] in a tool parameter slot — no JSON Schema callback shape. */
            FunctionType,

            /** [TypeExpr.Forall] in a tool parameter slot — no parametric polymorphism. */
            ForallType,

            /** [TypeExpr.Param] reached outside a Forall (unbound generic). */
            UnboundTypeParameter,
        }
    }

    /**
     * Project [type] to a JSON Schema element. The result is a JSON object
     * whose contents match the table in proposal § 3.8.1.
     *
     * - `Primitive.Int` → `{"type": "integer"}`
     * - `Primitive.Float` → `{"type": "number"}`
     * - `Primitive.String` → `{"type": "string"}`
     * - `Primitive.Bool` → `{"type": "boolean"}`
     * - `Primitive.Bytes` → `{"type": "string", "contentEncoding": "base64"}`
     * - `Primitive.Unit` → `{"type": "object", "properties": {}}`
     * - `Product({name: T, ...})` → object schema with every field required
     *   (Strand has no optional fields outside `Option<T>`).
     * - `Sum({case: T?, ...})` → `{"oneOf": [...]}` with a `tag`
     *   discriminator per case. Cases without a payload encode as
     *   `{"type": "object", "properties": {"tag": {"const": "Name"}},
     *   "required": ["tag"]}`. Cases with a payload encode as the same
     *   shape plus a `value: <T>` field.
     * - `Option<T>` (a sum with cases `Some(T) | None`) → projected as
     *   `{"oneOf": [<T>, {"type": "null"}]}`. The general `Sum` path
     *   detects this pattern by case names and produces the nullable
     *   form rather than the verbose tagged-discriminator form.
     * - `Recursive(body)` → `{"$ref": "#/$defs/<name>"}` plus a `$defs`
     *   entry holding the body's projection. Recursive references
     *   inside the body become `{"$ref": "#/$defs/<name>"}`. The outer
     *   result wraps the projection with a top-level `$defs` map.
     *
     * Rejected variants:
     * - [TypeExpr.Fun] anywhere in the tree → [Result.Rejected] with
     *   [Result.Reason.FunctionType].
     * - [TypeExpr.Forall] anywhere → [Result.Rejected] with
     *   [Result.Reason.ForallType].
     * - [TypeExpr.Param] reached at projection time (no surrounding
     *   substitution available) → [Result.Rejected] with
     *   [Result.Reason.UnboundTypeParameter].
     *
     * [TypeExpr.SchemaType] is *not* rejected — the projection unwraps to
     * the underlying `valueType`. Non-static invariants (like `x > 0`
     * that JSON Schema can express via `minimum`/`maximum`/`pattern`)
     * are NOT projected in this initial slice; agents that want
     * provider-side range checking declare them inline via the
     * `providerExtras` field on the GenerateRequest. The full Strand
     * Schema is re-validated by `SchemaChecker` after the provider
     * returns, which catches what JSON Schema cannot.
     */
    fun project(type: TypeExpr): Result {
        val defs = mutableMapOf<String, JsonElement>()
        val recursiveDepthToName = mutableMapOf<Int, String>()
        return when (val body = projectInternal(type, defs, recursiveDepthToName, depth = 0)) {
            is Internal.Ok -> {
                if (defs.isEmpty()) Result.Success(body.schema)
                else Result.Success(
                    buildJsonObject {
                        // Splice the body's top-level fields, then add $defs.
                        val bodyAsObject = body.schema as? JsonObject
                        if (bodyAsObject != null) {
                            for ((key, value) in bodyAsObject) put(key, value)
                        } else {
                            // Body wasn't an object (e.g., $ref-only); wrap.
                            put("\$ref", body.schema)
                        }
                        put("\$defs", JsonObject(defs))
                    }
                )
            }
            is Internal.Failed -> Result.Rejected(body.offendingType, body.reason)
        }
    }

    private sealed class Internal {
        data class Ok(val schema: JsonElement) : Internal()
        data class Failed(val offendingType: TypeExpr, val reason: Result.Reason) : Internal()
    }

    private fun projectInternal(
        t: TypeExpr,
        defs: MutableMap<String, JsonElement>,
        recursiveDepthToName: MutableMap<Int, String>,
        depth: Int,
    ): Internal {
        return when (t) {
            is TypeExpr.Prim -> Internal.Ok(projectPrim(t.kind))
            is TypeExpr.Fun -> Internal.Failed(t, Result.Reason.FunctionType)
            is TypeExpr.Forall -> Internal.Failed(t, Result.Reason.ForallType)
            is TypeExpr.Param -> Internal.Failed(t, Result.Reason.UnboundTypeParameter)
            is TypeExpr.Product -> projectProduct(t, defs, recursiveDepthToName, depth)
            is TypeExpr.Sum -> projectSum(t, defs, recursiveDepthToName, depth)
            is TypeExpr.Recursive -> {
                val name = "type${defs.size}"
                recursiveDepthToName[depth] = name
                // Reserve the slot before recursing so the body's
                // RecursiveSelf can find it via $ref.
                defs[name] = JsonObject(emptyMap())
                val bodyResult = projectInternal(t.body, defs, recursiveDepthToName, depth + 1)
                recursiveDepthToName.remove(depth)
                when (bodyResult) {
                    is Internal.Ok -> {
                        defs[name] = bodyResult.schema
                        Internal.Ok(buildJsonObject { put("\$ref", JsonPrimitive("#/\$defs/$name")) })
                    }
                    is Internal.Failed -> {
                        defs.remove(name)
                        bodyResult
                    }
                }
            }
            is TypeExpr.RecursiveSelf -> {
                // RecursiveSelf resolves to the innermost binder (depth=0
                // in the current node algebra; the field is reserved for
                // higher-arity binders later). At projection time, the
                // depth is relative to the *enclosing recursive walk*, so
                // we look up the topmost active binder.
                val targetDepth = (depth - 1 - t.depth).coerceAtLeast(0)
                val name = recursiveDepthToName[targetDepth]
                if (name == null) {
                    // Defensive — verifier should have caught this as
                    // UnboundRecursiveSelf. Project as an empty schema
                    // rather than throwing.
                    Internal.Ok(JsonObject(emptyMap()))
                } else {
                    Internal.Ok(buildJsonObject { put("\$ref", JsonPrimitive("#/\$defs/$name")) })
                }
            }
            is TypeExpr.SchemaType -> {
                // Unwrap the schema and project the underlying valueType.
                // Non-static invariants (range bounds, regex, etc.) are
                // re-checked by SchemaChecker after the provider returns;
                // mapping a subset to JSON Schema keywords is a follow-up.
                projectInternal(t.valueType, defs, recursiveDepthToName, depth)
            }
        }
    }

    private fun projectPrim(kind: Primitive): JsonElement = when (kind) {
        Primitive.Int -> buildJsonObject { put("type", JsonPrimitive("integer")) }
        Primitive.Float -> buildJsonObject { put("type", JsonPrimitive("number")) }
        Primitive.String -> buildJsonObject { put("type", JsonPrimitive("string")) }
        Primitive.Bool -> buildJsonObject { put("type", JsonPrimitive("boolean")) }
        Primitive.Bytes -> buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("contentEncoding", JsonPrimitive("base64"))
        }
        Primitive.Unit -> buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(emptyMap()))
        }
    }

    private fun projectProduct(
        t: TypeExpr.Product,
        defs: MutableMap<String, JsonElement>,
        recursiveDepthToName: MutableMap<Int, String>,
        depth: Int,
    ): Internal {
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<String>()
        for (field in t.fields) {
            val fieldResult = projectInternal(field.type, defs, recursiveDepthToName, depth)
            when (fieldResult) {
                is Internal.Ok -> {
                    properties[field.name] = fieldResult.schema
                    required += field.name
                }
                is Internal.Failed -> return fieldResult
            }
        }
        return Internal.Ok(buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(properties))
            put("required", buildJsonArray { for (r in required) add(JsonPrimitive(r)) })
        })
    }

    private fun projectSum(
        t: TypeExpr.Sum,
        defs: MutableMap<String, JsonElement>,
        recursiveDepthToName: MutableMap<Int, String>,
        depth: Int,
    ): Internal {
        // Special-case Option<T> = Some(T) | None into JSON Schema's
        // nullable convention. Pattern: exactly two cases named "Some"
        // (with a payload) and "None" (without). Order-insensitive.
        val cases = t.cases
        if (cases.size == 2) {
            val some = cases.find { it.name == "Some" }
            val none = cases.find { it.name == "None" }
            if (some != null && none != null && some.type != null && none.type == null) {
                return when (val inner = projectInternal(some.type!!, defs, recursiveDepthToName, depth)) {
                    is Internal.Ok -> Internal.Ok(buildJsonObject {
                        put("oneOf", buildJsonArray {
                            add(inner.schema)
                            add(buildJsonObject { put("type", JsonPrimitive("null")) })
                        })
                    })
                    is Internal.Failed -> inner
                }
            }
        }

        // General tagged-discriminator encoding. Each case projects to
        // an object schema with a `tag: {"const": "Name"}` field, plus
        // a `value: <T>` field when the case carries a payload.
        val variants = mutableListOf<JsonElement>()
        for (case in cases) {
            val variantProps = mutableMapOf<String, JsonElement>()
            val variantRequired = mutableListOf<String>()
            variantProps["tag"] = buildJsonObject { put("const", JsonPrimitive(case.name)) }
            variantRequired += "tag"
            val payloadType = case.type
            if (payloadType != null) {
                val payloadResult = projectInternal(payloadType, defs, recursiveDepthToName, depth)
                when (payloadResult) {
                    is Internal.Ok -> {
                        variantProps["value"] = payloadResult.schema
                        variantRequired += "value"
                    }
                    is Internal.Failed -> return payloadResult
                }
            }
            variants += buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(variantProps))
                put("required", buildJsonArray { for (r in variantRequired) add(JsonPrimitive(r)) })
            }
        }
        return Internal.Ok(buildJsonObject {
            put("oneOf", buildJsonArray { for (v in variants) add(v) })
        })
    }
}
