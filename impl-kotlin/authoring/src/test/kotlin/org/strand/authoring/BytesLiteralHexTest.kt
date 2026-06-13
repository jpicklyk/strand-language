package org.strand.authoring

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher

/**
 * Pins the Layer A `BYT` payload convention: the string argument is HEX,
 * passed through verbatim to the emitted `BytesLit.value` field, which the
 * `:core` JsonIngest hex-decodes (`JsonIngest.hexDecode`).
 *
 * A prior doc-comment in [LayerAGrammar] and the agent-facing grammar
 * reference claimed the payload was base64; the code never decoded base64.
 * These assertions lock the actual (hex) behavior so the doc and code agree.
 */
class BytesLiteralHexTest {

    @Test
    fun `BYT emits the hex string verbatim into BytesLit value`() {
        val json = Authoring.compileToJsonObject(
            """
            @v=1 root=bs
            bs BYT "deadbeef"
            """.trimIndent()
        )
        val node = json["nodes"]!!.jsonObject["bs"]!!.jsonObject
        assertEquals("BytesLit", node["type"]!!.jsonPrimitive.content)
        // Verbatim pass-through: no base64 transform, no re-encoding.
        assertEquals("deadbeef", node["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `BYT payload is hex-decoded on ingest, not base64-decoded`() {
        val dagJson = Authoring.compileToDagJson(
            """
            @v=1 root=bs
            bs BYT "deadbeef"
            """.trimIndent()
        )
        val ingest = JsonIngest.parse(dagJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val bytesLit = finalized.store.entries()
            .map { it.second }
            .filterIsInstance<Node.BytesLit>()
            .single()
        // Hex decoding: "deadbeef" → four bytes 0xDE 0xAD 0xBE 0xEF.
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            bytesLit.value,
        )
    }

    @Test
    fun `a base64-shaped BYT payload is rejected by ingest, confirming hex not base64`() {
        // "aGVsbG8=" is valid base64 ("hello") but not valid hex — it carries
        // a '=' and is odd-charactered. If the surface were base64 this would
        // succeed; because it is hex, ingest rejects it as malformed.
        val dagJson = Authoring.compileToDagJson(
            """
            @v=1 root=bs
            bs BYT "aGVsbG8="
            """.trimIndent()
        )
        val ex = assertThrows<IngestError.Malformed> {
            JsonIngest.parse(dagJson)
        }
        assertTrue("hex" in ex.message!!.lowercase()) {
            "ingest must reject the base64-shaped payload as invalid hex, got: ${ex.message}"
        }
    }
}
