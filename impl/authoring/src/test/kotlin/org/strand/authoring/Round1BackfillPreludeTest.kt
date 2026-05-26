package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the round-1 (Layer 4 step 2) implicit-prelude backfill —
 * the reserved-name shortcuts for Fs.* / Net.* / Http.* / Process.* /
 * Time.Sleep / monomorphic String.* and Bytes.* that originally shipped
 * without prelude entries (2026-05-26 corrective work after the
 * `When adding a new builtin` checklist landed).
 *
 * Each test compiles a tiny Layer A program using a reserved name and
 * asserts the synthesized ForeignNode + its FunctionType + effects
 * list elaborate to the expected canonical JSON shape.
 */
class Round1BackfillPreludeTest {

    private fun nodeOf(text: String, id: String) =
        Authoring.compileToJsonObject(text).jsonObject["nodes"]!!.jsonObject[id]!!.jsonObject

    private fun assertEffects(node: kotlinx.serialization.json.JsonObject, expected: List<String>) {
        val actual = node["effects"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertEquals(expected, actual)
    }

    // ---------- Filesystem ----------

    @Test
    fun `fsRead and fsWrite reserved names wire to Fs_Read and Fs_Write with the right effect categories`() {
        val readNode = nodeOf("@v=1 root=a\na APP fsRead [\"/tmp/x\"]", "fsRead")
        assertEquals("strand-builtin:Fs.Read", readNode["target"]!!.jsonPrimitive.content)
        assertEffects(readNode, listOf("readFx"))
        // readFx itself is the Filesystem.Read EffectCategory.
        val readFx = nodeOf("@v=1 root=a\na APP fsRead [\"/tmp/x\"]", "readFx")
        assertEquals("EffectCategory", readFx["type"]!!.jsonPrimitive.content)
        assertEquals("Filesystem.Read", readFx["categoryName"]!!.jsonPrimitive.content)

        val empty = "@v=1 root=a\nempty BYT \"\"\na APP fsWrite [\"/tmp/x\" empty]"
        val writeNode = nodeOf(empty, "fsWrite")
        assertEquals("strand-builtin:Fs.Write", writeNode["target"]!!.jsonPrimitive.content)
        assertEffects(writeNode, listOf("writeFx"))
    }

    @Test
    fun `fsAppend fsExists fsDelete reserved names map to correct targets and effects`() {
        for ((name, target, fx) in listOf(
            Triple("fsAppend", "strand-builtin:Fs.Append", "writeFx"),
            Triple("fsExists", "strand-builtin:Fs.Exists", "readFx"),
            Triple("fsDelete", "strand-builtin:Fs.Delete", "writeFx"),
        )) {
            val args = if (name == "fsAppend") {
                "\nempty BYT \"\"\na APP $name [\"/tmp/x\" empty]"
            } else {
                "\na APP $name [\"/tmp/x\"]"
            }
            val node = nodeOf("@v=1 root=a$args", name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            assertEffects(node, listOf(fx))
        }
    }

    // ---------- Network sockets + HTTP ----------

    @Test
    fun `netConnect netSend netRecv netClose wire to the right targets with distinct effect categories`() {
        val connect = nodeOf("@v=1 root=a\na APP netConnect [\"localhost\" 80]", "netConnect")
        assertEquals("strand-builtin:Net.Connect", connect["target"]!!.jsonPrimitive.content)
        assertEffects(connect, listOf("connectFx"))

        val send = nodeOf("@v=1 root=a\nempty BYT \"\"\na APP netSend [1 empty]", "netSend")
        assertEquals("strand-builtin:Net.Send", send["target"]!!.jsonPrimitive.content)
        assertEffects(send, listOf("netSendFx"))

        val recv = nodeOf("@v=1 root=a\na APP netRecv [1 1024]", "netRecv")
        assertEquals("strand-builtin:Net.Receive", recv["target"]!!.jsonPrimitive.content)
        assertEffects(recv, listOf("netRecvFx"))

        val close = nodeOf("@v=1 root=a\na APP netClose [1]", "netClose")
        assertEquals("strand-builtin:Net.Close", close["target"]!!.jsonPrimitive.content)
        // Net.Close has no specific effect (closing an opened resource).
        assertEffects(close, emptyList())
    }

    @Test
    fun `netSendFx and netRecvFx are distinct from sendFx and receiveFx`() {
        // sendFx and receiveFx are the StateMachine.Send / .Receive
        // categories that every state machine declares. The Net.*
        // builtins need their own distinct categories. Distinct names,
        // distinct canonical category strings — confirmed via the
        // ReservedNodeSpec specs directly so we don't need a program
        // that materializes all four at once.
        val grammar = LayerAGrammar
        assertEquals("Network.Send", grammar.reservedNodes.getValue("netSendFx").stringFields["categoryName"])
        assertEquals("StateMachine.Send", grammar.reservedNodes.getValue("sendFx").stringFields["categoryName"])
        assertEquals("Network.Receive", grammar.reservedNodes.getValue("netRecvFx").stringFields["categoryName"])
        assertEquals("StateMachine.Receive", grammar.reservedNodes.getValue("receiveFx").stringFields["categoryName"])
    }

    @Test
    fun `httpReq returns the fixed status-body ProductType and declares all three Network effects`() {
        val text = "@v=1 root=a\nempty BYT \"\"\na APP httpReq [\"GET\" \"http://example.com\" empty]"
        val req = nodeOf(text, "httpReq")
        assertEquals("strand-builtin:Http.Request", req["target"]!!.jsonPrimitive.content)
        // All three Network.* effects declared, since the underlying
        // implementation connects + sends + reads.
        assertEffects(req, listOf("connectFx", "netSendFx", "netRecvFx"))
        // FunctionType references the canned response product type.
        val fnt = nodeOf(text, "httpReqT")
        assertEquals(
            listOf("stringT", "stringT", "bytesT"),
            fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("httpRespT", fnt["result"]!!.jsonPrimitive.content)
        // httpRespT is the ProductType {status: Int, body: Bytes}.
        val resp = nodeOf(text, "httpRespT")
        assertEquals("ProductType", resp["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("httpRespStatusField", "httpRespBodyField"),
            resp["fields"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val statusField = nodeOf(text, "httpRespStatusField")
        assertEquals("status", statusField["name"]!!.jsonPrimitive.content)
        assertEquals("intT", statusField["fieldType"]!!.jsonPrimitive.content)
        val bodyField = nodeOf(text, "httpRespBodyField")
        assertEquals("body", bodyField["name"]!!.jsonPrimitive.content)
        assertEquals("bytesT", bodyField["fieldType"]!!.jsonPrimitive.content)
    }

    // ---------- Process + Time ----------

    @Test
    fun `procWait and sleep declare their respective effect categories`() {
        val wait = nodeOf("@v=1 root=a\na APP procWait [1]", "procWait")
        assertEquals("strand-builtin:Process.Wait", wait["target"]!!.jsonPrimitive.content)
        assertEffects(wait, listOf("procWaitFx"))
        assertEquals(
            "Process.Wait",
            nodeOf("@v=1 root=a\na APP procWait [1]", "procWaitFx")["categoryName"]!!.jsonPrimitive.content,
        )

        val sleep = nodeOf("@v=1 root=a\na APP sleep [100]", "sleep")
        assertEquals("strand-builtin:Time.Sleep", sleep["target"]!!.jsonPrimitive.content)
        assertEffects(sleep, listOf("sleepFx"))
        assertEquals(
            "Time.Sleep",
            nodeOf("@v=1 root=a\na APP sleep [100]", "sleepFx")["categoryName"]!!.jsonPrimitive.content,
        )
    }

    // ---------- String stdlib (monomorphic) ----------

    @Test
    fun `String stdlib reserved names map to strand-builtin String_X targets`() {
        for ((name, target) in listOf(
            "strLen" to "strand-builtin:String.Length",
            "subStr" to "strand-builtin:String.Substring",
            "indexOf" to "strand-builtin:String.IndexOf",
            "contains" to "strand-builtin:String.Contains",
            "replace" to "strand-builtin:String.Replace",
            "upper" to "strand-builtin:String.ToUpper",
            "lower" to "strand-builtin:String.ToLower",
            "trim" to "strand-builtin:String.Trim",
        )) {
            // Use a 1-arg form for most; the multi-arg ones still
            // compile to the right ForeignNode regardless of arg count.
            val text = "@v=1 root=a\na APP $name [\"hello\"]"
            val node = nodeOf(text, name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            // Pure: no effects.
            assertEffects(node, emptyList())
        }
    }

    @Test
    fun `String FromX coercions wire to String_FromInt FromFloat FromBool`() {
        val intToStr = nodeOf("@v=1 root=a\na APP intToStr [42]", "intToStr")
        assertEquals("strand-builtin:String.FromInt", intToStr["target"]!!.jsonPrimitive.content)
        val floatToStr = nodeOf("@v=1 root=a\na APP floatToStr [3.14]", "floatToStr")
        assertEquals("strand-builtin:String.FromFloat", floatToStr["target"]!!.jsonPrimitive.content)
        val boolToStr = nodeOf("@v=1 root=a\na APP boolToStr [true]", "boolToStr")
        assertEquals("strand-builtin:String.FromBool", boolToStr["target"]!!.jsonPrimitive.content)
    }

    // ---------- Bytes stdlib (monomorphic) ----------

    @Test
    fun `Bytes stdlib reserved names map to strand-builtin Bytes_X targets`() {
        // bytesLen, bytesSlice, bytesCat take Bytes args.
        val empty = "@v=1 root=a\nempty BYT \"\""
        for ((name, target) in listOf(
            "bytesLen" to "strand-builtin:Bytes.Length",
            "bytesCat" to "strand-builtin:Bytes.Concat",
            "b64Of" to "strand-builtin:Bytes.FormatBase64",
        )) {
            val args = if (name == "bytesCat") "[empty empty]" else "[empty]"
            val node = nodeOf("$empty\na APP $name $args", name)
            assertEquals(target, node["target"]!!.jsonPrimitive.content)
            assertEffects(node, emptyList())
        }
    }

    @Test
    fun `fromUtf8 wires to Bytes_FromUtf8 with String to Bytes type`() {
        val node = nodeOf("@v=1 root=a\na APP fromUtf8 [\"hello\"]", "fromUtf8")
        assertEquals("strand-builtin:Bytes.FromUtf8", node["target"]!!.jsonPrimitive.content)
        val fnt = nodeOf("@v=1 root=a\na APP fromUtf8 [\"hello\"]", "fromUtf8T")
        assertEquals(listOf("stringT"), fnt["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("bytesT", fnt["result"]!!.jsonPrimitive.content)
    }

    // ---------- Effect category sanity ----------

    @Test
    fun `new effect categories use canonical Strand effect names`() {
        // Builds a Layer A program that references each new effect category
        // by name, and confirms the synthesized EffectCategory node carries
        // the canonical Strand-level name expected by the verifier.
        for ((fx, expectedName) in listOf(
            "readFx" to "Filesystem.Read",
            "netSendFx" to "Network.Send",
            "netRecvFx" to "Network.Receive",
            "procWaitFx" to "Process.Wait",
            "sleepFx" to "Time.Sleep",
        )) {
            // Pick a builtin that references this effect to force the
            // category into the synthesized output.
            val builtin = when (fx) {
                "readFx" -> "fsRead"
                "netSendFx" -> "netSend"
                "netRecvFx" -> "netRecv"
                "procWaitFx" -> "procWait"
                "sleepFx" -> "sleep"
                else -> error("unreachable")
            }
            val args = when (builtin) {
                "fsRead" -> "[\"/tmp/x\"]"
                "netSend" -> "[1 (BYT \"\")]"
                "netRecv" -> "[1 1024]"
                "procWait", "sleep" -> "[1]"
                else -> error("unreachable")
            }
            val text = "@v=1 root=a\na APP $builtin $args"
            val node = nodeOf(text, fx)
            assertEquals("EffectCategory", node["type"]!!.jsonPrimitive.content)
            assertEquals(expectedName, node["categoryName"]!!.jsonPrimitive.content)
        }
    }
}
