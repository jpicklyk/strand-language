package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Q-045 prelude coverage. Only the monomorphic `LLM.Stream.Close`
 * ((handle: Int) -> Unit) earns a prelude entry; the streaming open
 * (*.CreateStream, agent-typed against GenerateRequest) and the
 * Option<Bytes>-returning drains (LLM.Stream.Receive / Net.Stream.Receive)
 * stay out of the prelude per the documented exceptions in
 * impl-kotlin/CLAUDE.md — agents declare explicit FNT + FRN at the use
 * site for those.
 */
class StreamingPreludeTest {

    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    @Test
    fun `llmStreamClose wires to LLM_Stream_Close with Int-Unit FNT and no effect`() {
        val text = "@v=1 root=a\na APP llmStreamClose [7]"
        val node = nodeOf(text, "llmStreamClose")
        assertEquals("ForeignNode", node["type"]!!.jsonPrimitive.content)
        assertEquals("strand-builtin:LLM.Stream.Close", node["target"]!!.jsonPrimitive.content)
        assertEquals("llmStreamCloseT", node["foreignType"]!!.jsonPrimitive.content)
        // Close declares no effect.
        assertTrue(node["effects"] == null || node["effects"]!!.jsonArray.isEmpty())
        val fnt = nodeOf(text, "llmStreamCloseT")
        assertEquals(listOf("intT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("unitT", fnt["result"]!!.jsonPrimitive.content)
    }
}
