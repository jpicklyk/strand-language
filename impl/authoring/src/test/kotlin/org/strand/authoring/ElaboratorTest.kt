package org.strand.authoring

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [Elaborator] — Layer C first slice (Q-034 step 1, effect-
 * closure inference on Lambda).
 *
 * Each test parses a small Layer A document with at least one Lambda
 * whose `effects` argument is absent, runs the elaborator, and asserts
 * that the inferred effects appear on the corresponding Lambda node in
 * the emitted dag-json.
 */
class ElaboratorTest {

    @Test
    fun `Lambda with no effects gets the body's foreign-call effects filled in`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            lam LAM [] callNow
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // The original LAM had 2 args ([params], body); elaboration should
        // add a third arg (the effects list).
        assertEquals(3, lamNode.args.size) {
            "elaborator should have filled in the LAM's effects arg; got args=${lamNode.args}"
        }
        val effectsArg = lamNode.args[2] as Arg.Listing
        val effectRefs = effectsArg.items.map { (it as Arg.Bare).text }
        assertEquals(listOf("timeFx"), effectRefs)
    }

    @Test
    fun `Lambda with no effects and pure body is left unchanged`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            x PRC "x" intT
            xRef VAR x
            lam LAM [x] xRef
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // Pure body: inferred closure is empty; LAM stays as 2-arg.
        assertEquals(2, lamNode.args.size) {
            "pure Lambda's args should be unchanged; got ${lamNode.args}"
        }
    }

    @Test
    fun `Lambda with explicit effects is left unchanged by elaboration`() {
        val text = """
            @v=1 root=lam
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            lam LAM [] callNow [timeFx]
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        // Explicit effects: elaborator must not touch it.
        assertEquals(3, lamNode.args.size)
        val effectsArg = lamNode.args[2] as Arg.Listing
        assertEquals(listOf("timeFx"), effectsArg.items.map { (it as Arg.Bare).text })
    }

    @Test
    fun `compileWithElaboration emits the inferred effects in the dag-json`() {
        val text = """
            @v=1 root=app
            intT PRM Int
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            myLam LAM [] callNow
            app APP myLam []
        """.trimIndent()

        val jsonText = Authoring.compileWithElaboration(text)
        // Cheap structural check: the resulting JSON must contain a Lambda
        // with effects = ["timeFx"]. We parse the JSON and inspect.
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as JsonObject
        val nodes = obj["nodes"] as JsonObject
        val lamObj = nodes["myLam"] as JsonObject
        val effects = lamObj["effects"] as JsonArray
        assertEquals(1, effects.size)
        assertEquals("timeFx", (effects[0] as JsonPrimitive).content)
    }

    @Test
    fun `Match with effectful body propagates effects through the case bodies`() {
        // A Lambda whose body Matches on a Bool and one of the cases calls
        // an effectful builtin. The Lambda's closure should include the
        // effects from any case body.
        val text = """
            @v=1 root=lam
            intT PRM Int
            boolT PRM Bool
            timeFx EFC "Time.Now"
            nowT FNT [] intT
            now FN "strand-builtin:Time.Now" nowT [timeFx]
            callNow APP now []
            zero ILT 0
            patTrue PLT boolT trueLit
            trueLit BLT true
            caseTrue MC patTrue callNow
            patFalse PLT boolT falseLit
            falseLit BLT false
            caseFalse MC patFalse zero
            b PRC "b" boolT
            bRef VAR b
            m MAT bRef [caseTrue caseFalse]
            lam LAM [b] m
        """.trimIndent()

        val doc = LayerAParser.parse(text)
        val elaborated = Elaborator.elaborate(doc)
        val lamNode = elaborated.nodes.first { it.id == "lam" }
        assertTrue(lamNode.args.size == 3) {
            "elaborator should have filled in effects; got ${lamNode.args}"
        }
        val effectRefs = (lamNode.args[2] as Arg.Listing).items.map { (it as Arg.Bare).text }
        assertEquals(listOf("timeFx"), effectRefs)
    }
}
