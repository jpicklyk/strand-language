package org.strand.interpreter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.strand.core.NodeId
import org.strand.core.Primitive
import org.strand.verifier.TypeExpr

/**
 * Q-037 § 3.8.1 — JSON Schema projection of the irreducible TypeExpr
 * subset. Each primitive and composite case is covered, plus the
 * three rejected variants (FunctionType, ForallType, unbound TypeParameter).
 */
class JsonSchemaProjectionTest {

    private fun nodeId(n: Int) = NodeId(n)

    private fun success(t: TypeExpr): JsonObject {
        val r = JsonSchemaProjection.project(t)
        assertTrue(r is JsonSchemaProjection.Result.Success, "expected Success, got $r")
        return (r as JsonSchemaProjection.Result.Success).schema as JsonObject
    }

    private fun rejected(t: TypeExpr): JsonSchemaProjection.Result.Rejected {
        val r = JsonSchemaProjection.project(t)
        assertTrue(r is JsonSchemaProjection.Result.Rejected, "expected Rejected, got $r")
        return r as JsonSchemaProjection.Result.Rejected
    }

    // ----- Primitives -----

    @Test
    fun `Int projects to integer`() {
        assertEquals("integer", success(TypeExpr.Prim(Primitive.Int))["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Float projects to number`() {
        assertEquals("number", success(TypeExpr.Prim(Primitive.Float))["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `String projects to string`() {
        assertEquals("string", success(TypeExpr.Prim(Primitive.String))["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Bool projects to boolean`() {
        assertEquals("boolean", success(TypeExpr.Prim(Primitive.Bool))["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Bytes projects to string with base64 contentEncoding`() {
        val s = success(TypeExpr.Prim(Primitive.Bytes))
        assertEquals("string", s["type"]?.jsonPrimitive?.content)
        assertEquals("base64", s["contentEncoding"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Unit projects to empty object`() {
        val s = success(TypeExpr.Prim(Primitive.Unit))
        assertEquals("object", s["type"]?.jsonPrimitive?.content)
        assertEquals(0, (s["properties"] as JsonObject).size)
    }

    // ----- Product -----

    @Test
    fun `Product projects fields as required object`() {
        val product = TypeExpr.Product(nodeId(1), listOf(
            TypeExpr.Product.Field("x", TypeExpr.Prim(Primitive.Int)),
            TypeExpr.Product.Field("name", TypeExpr.Prim(Primitive.String)),
        ))
        val s = success(product)
        assertEquals("object", s["type"]?.jsonPrimitive?.content)
        val props = s["properties"] as JsonObject
        assertEquals(2, props.size)
        assertEquals("integer", (props["x"] as JsonObject)["type"]?.jsonPrimitive?.content)
        assertEquals("string", (props["name"] as JsonObject)["type"]?.jsonPrimitive?.content)
        val required = s["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("x", "name"), required)
    }

    // ----- Sum -----

    @Test
    fun `Sum projects with tag discriminator`() {
        val sum = TypeExpr.Sum(nodeId(2), listOf(
            TypeExpr.Sum.Case("Red", null),
            TypeExpr.Sum.Case("Custom", TypeExpr.Prim(Primitive.Int)),
        ))
        val s = success(sum)
        val variants = s["oneOf"]!!.jsonArray
        assertEquals(2, variants.size)
        val redProps = (variants[0].jsonObject["properties"] as JsonObject)
        assertEquals("Red", (redProps["tag"] as JsonObject)["const"]?.jsonPrimitive?.content)
        val customProps = (variants[1].jsonObject["properties"] as JsonObject)
        assertEquals("Custom", (customProps["tag"] as JsonObject)["const"]?.jsonPrimitive?.content)
        // Custom carries a value field.
        assertNotNull(customProps["value"])
    }

    // ----- Option special-case -----

    @Test
    fun `Option projects to nullable convention`() {
        val option = TypeExpr.Sum(nodeId(3), listOf(
            TypeExpr.Sum.Case("Some", TypeExpr.Prim(Primitive.String)),
            TypeExpr.Sum.Case("None", null),
        ))
        val s = success(option)
        val variants = s["oneOf"]!!.jsonArray
        assertEquals(2, variants.size)
        // First variant is the wrapped string; second is null.
        assertEquals("string", variants[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("null", variants[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    // ----- Recursive -----

    @Test
    fun `Recursive projects via dollar-defs and dollar-ref`() {
        // μ. {head: Int, tail: <self>}
        val recursive = TypeExpr.Recursive(
            TypeExpr.Product(nodeId(4), listOf(
                TypeExpr.Product.Field("head", TypeExpr.Prim(Primitive.Int)),
                TypeExpr.Product.Field("tail", TypeExpr.RecursiveSelf()),
            )),
        )
        val s = success(recursive)
        assertNotNull(s["\$defs"])
        // The top-level body references the def.
        assertNotNull(s["\$ref"])
    }

    // ----- SchemaType unwraps to valueType -----

    @Test
    fun `SchemaType projects through to its valueType`() {
        val st = TypeExpr.SchemaType(nodeId(5), TypeExpr.Prim(Primitive.Int), emptyList())
        val s = success(st)
        assertEquals("integer", s["type"]?.jsonPrimitive?.content)
    }

    // ----- Rejected variants -----

    @Test
    fun `FunctionType is rejected`() {
        val fn = TypeExpr.Fun(listOf(TypeExpr.Prim(Primitive.Int)), TypeExpr.Prim(Primitive.Int))
        assertEquals(JsonSchemaProjection.Result.Reason.FunctionType, rejected(fn).reason)
    }

    @Test
    fun `ForallType is rejected`() {
        val forall = TypeExpr.Forall(listOf(nodeId(6)), TypeExpr.Param(nodeId(6)))
        assertEquals(JsonSchemaProjection.Result.Reason.ForallType, rejected(forall).reason)
    }

    @Test
    fun `Unbound TypeParameter is rejected`() {
        val param = TypeExpr.Param(nodeId(7), "A")
        assertEquals(JsonSchemaProjection.Result.Reason.UnboundTypeParameter, rejected(param).reason)
    }

    @Test
    fun `FunctionType nested inside Product propagates the rejection`() {
        val product = TypeExpr.Product(nodeId(8), listOf(
            TypeExpr.Product.Field("x", TypeExpr.Prim(Primitive.Int)),
            TypeExpr.Product.Field(
                "cb",
                TypeExpr.Fun(emptyList(), TypeExpr.Prim(Primitive.Unit)),
            ),
        ))
        assertEquals(JsonSchemaProjection.Result.Reason.FunctionType, rejected(product).reason)
    }
}
