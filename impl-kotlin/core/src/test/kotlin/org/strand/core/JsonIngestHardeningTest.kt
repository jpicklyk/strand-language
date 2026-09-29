package org.strand.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Ingest-boundary hardening from the 2026-09-29 review (core/hashing
 * findings). Each test is the exploit document; the pipeline must reject
 * it with a structured [IngestError] instead of admitting it.
 */
class JsonIngestHardeningTest {

    private fun doc(nodes: String, root: String = "r") =
        """{ "version": 1, "root": "$root", "nodes": { $nodes } }"""

    // ----- H2: ill-formed UTF-16 -----

    @Test
    fun `unpaired high surrogate in a StringLit is rejected`() {
        val err = assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "StringLit", "value": "a\ud800b" } """))
        }
        assertTrue(err.message!!.contains("surrogate"), err.message)
    }

    @Test
    fun `unpaired low surrogate in a name field is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "EffectCategory", "categoryName": "\udc00" } """))
        }
    }

    @Test
    fun `unpaired surrogate in an author id key is rejected`() {
        assertThrows<IngestError.Malformed> {
            JsonIngest.parse(doc(""" "r": { "type": "UnitLit" }, "\ud800": { "type": "UnitLit" } """))
        }
    }

    @Test
    fun `a well-formed surrogate pair is accepted`() {
        val result = JsonIngest.parse(doc(""" "r": { "type": "StringLit", "value": "😀" } """))
        val stored = result.rawStore.get(result.root) as StoredNode.Canonical
        assertEquals(Node.StringLit("😀"), stored.node)
    }
}
