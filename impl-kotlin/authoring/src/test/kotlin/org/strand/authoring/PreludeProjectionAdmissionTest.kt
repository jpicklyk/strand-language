package org.strand.authoring

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * End-to-end admission tests for the six Q-039-migrated prelude ForeignNodes
 * whose EffectCategory declarations carry projection sources:
 *
 *   fsRead  / fsExists  → readFx   (Filesystem.Read{path: String})
 *   fsWrite / fsAppend / fsDelete → writeFx (Filesystem.Write{path: String})
 *   netConnect → connectFx (Network.Connect{host: String, port: Int})
 *
 * Each test compiles a minimal Layer A document using the prelude name through
 * [Authoring.compileToDagJson], ingests the dag-json, finalizes, and verifies.
 * Verification must pass cleanly — the fix being tested is that the three
 * previously-parameterless EffectCategories now carry the correct parameter
 * count so that [VerifyError.ProjectionSourceArityMismatch] is no longer raised
 * at ForeignNode admission.
 *
 * The missing test-class that would have caught the original regression:
 * the Q-039 prelude tests only asserted the emission *shape* but never ran
 * an authored prelude program through the verifier.
 */
class PreludeProjectionAdmissionTest {

    /**
     * Compile [text] to dag-json, ingest, finalize, and run the verifier.
     * Asserts that [VerifyResult.Ok] is returned (zero errors).
     */
    private fun compileAndVerify(text: String): VerifyResult.Ok {
        val dagJson = Authoring.compileToDagJson(text.trimIndent())
        val ingest = JsonIngest.parse(dagJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val result = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(
            result is VerifyResult.Ok,
            "expected Ok verification, got: $result",
        )
        return result as VerifyResult.Ok
    }

    // ── readFx (Filesystem.Read{path: String}) ──────────────────────────────

    @Test
    fun `fsRead compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=readApp
            pathS STR "config.json"
            readApp APP fsRead [pathS]
        """)
    }

    @Test
    fun `fsExists compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=app
            p STR "test.txt"
            app APP fsExists [p]
        """)
    }

    // ── writeFx (Filesystem.Write{path: String}) ─────────────────────────────

    @Test
    fun `fsWrite compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=app
            p STR "out.txt"
            d BYT ""
            app APP fsWrite [p d]
        """)
    }

    @Test
    fun `fsAppend compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=app
            p STR "log.txt"
            d BYT ""
            app APP fsAppend [p d]
        """)
    }

    @Test
    fun `fsDelete compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=app
            p STR "stale.txt"
            app APP fsDelete [p]
        """)
    }

    // ── connectFx (Network.Connect{host: String, port: Int}) ─────────────────

    @Test
    fun `netConnect compiles and verifies cleanly through the verifier`() {
        compileAndVerify("""
            @v=1 root=app
            host STR "example.com"
            port ILT 443
            app APP netConnect [host port]
        """)
    }

    // ── EffectCategory parameter counts ─────────────────────────────────────

    /**
     * Structural consistency check: every [ReservedEffectProjection]'s
     * source count must equal its referenced [ReservedNodeSpec] category's
     * declared parameter count. A mismatch means the category's arity is
     * wrong relative to its projection, which is exactly the bug this test
     * class was written to prevent from regressing.
     *
     * This test operates over the prelude registry itself (no compilation
     * step needed) and covers the full set of effectProjections — not just
     * the Filesystem / Network trio.
     */
    @Test
    fun `every prelude effectProjection source count matches its category parameter count`() {
        val reserved = LayerAGrammar.reservedNodes

        // Build a map from reserved-name → declared parameter count for all
        // EffectCategory entries.
        val categoryParamCount: Map<String, Int> = reserved
            .filter { (_, spec) -> spec.jsonType == "EffectCategory" }
            .mapValues { (_, spec) ->
                // An EffectCategory may declare `parameters` via refListFields.
                // If the field is absent the category is parameterless (count=0).
                spec.refListFields["parameters"]?.size ?: 0
            }

        // Walk every ForeignNode entry that has effectProjections and assert
        // that each projection's source list has the same size as the
        // corresponding category's parameter count.
        val mismatches = mutableListOf<String>()
        for ((name, spec) in reserved) {
            for (proj in spec.effectProjections) {
                val expected = categoryParamCount[proj.category]
                    ?: error("effectProjection in '$name' references unknown category '${proj.category}'")
                val actual = proj.sources.size
                if (expected != actual) {
                    mismatches.add(
                        "prelude '$name' → category '${proj.category}': " +
                        "category declares $expected parameter(s), projection has $actual source(s)",
                    )
                }
            }
        }

        assertTrue(mismatches.isEmpty()) {
            "Prelude registry effectProjection arity mismatches:\n" +
            mismatches.joinToString("\n  ", prefix = "  ")
        }
    }

    // ── readFx / writeFx category shape ─────────────────────────────────────

    /**
     * Verify that `readFx` emits an EffectCategory node with one `stringT`
     * parameter — the E-006 Filesystem.Read{path: String} shape.
     */
    @Test
    fun `readFx emits EffectCategory with one String parameter`() {
        val json = Authoring.compileToJsonObject("""
            @v=1 root=app
            p STR "x"
            app APP fsRead [p]
        """.trimIndent())
        val nodes = json["nodes"]!!.jsonObject
        val cat = nodes["readFx"]!!.jsonObject
        assertEquals("EffectCategory", cat["type"]!!.jsonPrimitive.content)
        assertEquals("Filesystem.Read", cat["categoryName"]!!.jsonPrimitive.content)
        assertEquals(listOf("stringT"), cat["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /**
     * Verify that `writeFx` emits an EffectCategory node with one `stringT`
     * parameter — the E-007 Filesystem.Write{path: String} shape.
     */
    @Test
    fun `writeFx emits EffectCategory with one String parameter`() {
        val json = Authoring.compileToJsonObject("""
            @v=1 root=app
            p STR "x"
            d BYT ""
            app APP fsWrite [p d]
        """.trimIndent())
        val nodes = json["nodes"]!!.jsonObject
        val cat = nodes["writeFx"]!!.jsonObject
        assertEquals("EffectCategory", cat["type"]!!.jsonPrimitive.content)
        assertEquals("Filesystem.Write", cat["categoryName"]!!.jsonPrimitive.content)
        assertEquals(listOf("stringT"), cat["parameters"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /**
     * Verify that `connectFx` emits an EffectCategory node with two parameters
     * (`stringT`, `intT`) — the E-001 Network.Connect{host: String, port: Int}
     * shape.
     */
    @Test
    fun `connectFx emits EffectCategory with String and Int parameters`() {
        val json = Authoring.compileToJsonObject("""
            @v=1 root=app
            host STR "example.com"
            port ILT 8080
            app APP netConnect [host port]
        """.trimIndent())
        val nodes = json["nodes"]!!.jsonObject
        val cat = nodes["connectFx"]!!.jsonObject
        assertEquals("EffectCategory", cat["type"]!!.jsonPrimitive.content)
        assertEquals("Network.Connect", cat["categoryName"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("stringT", "intT"),
            cat["parameters"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
