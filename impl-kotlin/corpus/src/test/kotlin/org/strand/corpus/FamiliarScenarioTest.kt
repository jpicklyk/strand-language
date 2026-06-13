package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-061 Layer F — the proposal § 7 scenarios that are not the
 * cross-surface hash property (see FamiliarSurfaceHashEqualityTest for
 * scenario 1):
 *
 *  * scenario 5 — effect omission: a function calling an effectful
 *    builtin without the matching `uses` clause fails verification
 *    with the standard UncoveredEffects, attributable to the Layer F
 *    line the agent wrote.
 *  * scenario 6 — recursion lifting: factorial via Fixpoint evaluates
 *    correctly.
 *  * scenario 8 — attempt + match reproduces the corpus-86 Fs.Read
 *    fallback semantics.
 *  * scenario 9 — error-line mapping: an injected semantic error
 *    reports the Layer F line, not a bare node id.
 */
class FamiliarScenarioTest {

    private class Compiled(
        val sourceLines: Map<String, Int>,
        val nameMap: Map<String, NodeId>,
        val root: NodeId,
        val store: org.strand.core.NodeStore,
        val hashToNodeId: Map<org.strand.core.Hash, NodeId>,
        val verify: VerifyResult,
    )

    private fun compileAndVerify(text: String): Compiled {
        val result = Authoring.compile(text, Authoring.Surface.FAMILIAR)
        val ingest = JsonIngest.parse(result.dagJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        return Compiled(
            sourceLines = result.sourceLines,
            nameMap = ingest.nameMap,
            root = finalized.root,
            store = finalized.store,
            hashToNodeId = finalized.hashToNodeId,
            verify = verify,
        )
    }

    private fun Compiled.authorIdOf(id: NodeId): String? =
        nameMap.entries.firstOrNull { it.value == id }?.key

    /** The 1-based line of the first source line containing [needle]. */
    private fun lineOf(source: String, needle: String): Int =
        source.lines().indexOfFirst { it.contains(needle) } + 1

    // ------------------------------------------------------------------
    // Scenario 5 — effect omission
    // ------------------------------------------------------------------

    @Test
    fun `effect omission is the standard UncoveredEffects against the Layer F line`() {
        val source = """
            use prelude.{add}

            declare function write(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)

            function appendLog(): Int {
              return add(write("/tmp/strand-eval.log"), 1)
            }

            function main(): Int {
              return add(appendLog(), 10)
            }
        """.trimIndent()
        val compiled = compileAndVerify(source)
        val failed = compiled.verify as? VerifyResult.Failed
            ?: error("expected verification failure, got ${compiled.verify}")
        val uncovered = failed.errors.filterIsInstance<VerifyError.UncoveredEffects>()
        assertTrue(uncovered.isNotEmpty()) { "expected UncoveredEffects, got ${failed.errors}" }
        // The error's node maps back to the `function appendLog` line.
        val authorId = compiled.authorIdOf(uncovered.first().at)
            ?: error("UncoveredEffects node has no author id")
        assertEquals("appendLog", authorId)
        assertEquals(lineOf(source, "function appendLog"), compiled.sourceLines[authorId]) {
            "the Lambda must carry the Layer F line of the function declaration"
        }
    }

    @Test
    fun `the corrected uses clause verifies clean`() {
        val source = """
            use prelude.{add}

            declare function write(path: String): Int from "strand-builtin:Filesystem.Write" uses Filesystem.Write(path)

            function appendLog(): Int uses Filesystem.Write("/tmp/strand-eval.log") {
              return add(write("/tmp/strand-eval.log"), 1)
            }

            function main(): Int {
              return add(appendLog(), 10)
            }
        """.trimIndent()
        val compiled = compileAndVerify(source)
        assertTrue(compiled.verify is VerifyResult.Ok) { "expected Ok, got ${compiled.verify}" }
    }

    // ------------------------------------------------------------------
    // Scenario 6 — recursion lifting
    // ------------------------------------------------------------------

    @Test
    fun `factorial via Fixpoint evaluates to 120`() {
        val compiled = compileAndVerify(
            """
            use prelude.{eqInt, sub, mul}

            function fact(n: Int): Int {
              return match (eqInt(n, 0)) {
                true => 1,
                false => mul(n, fact(sub(n, 1))),
              }
            }

            function main(): Int {
              return fact(5)
            }
            """.trimIndent(),
        )
        assertTrue(compiled.verify is VerifyResult.Ok) { "verify failed: ${compiled.verify}" }
        val value = Interpreter(compiled.store, compiled.hashToNodeId)
            .eval(compiled.root, emptySet<NodeId>())
        assertEquals(Value.IntV(120L), value)
    }

    // ------------------------------------------------------------------
    // Scenario 8 — attempt + match (corpus 86 semantics)
    // ------------------------------------------------------------------

    @Test
    fun `attempt over a missing-file read falls back through the Err arm`() {
        val compiled = compileAndVerify(
            """
            use prelude.{fsRead, fromUtf8}

            function main(): Bytes {
              return match (attempt { fsRead("strand-attempt-nonexistent/config.json") }) {
                Ok(b) => b,
                Err(e) => fromUtf8("{}"),
              }
            }
            """.trimIndent(),
        )
        assertTrue(compiled.verify is VerifyResult.Ok) { "verify failed: ${compiled.verify}" }
        // Corpus 86 contract: the Fs.Read call still needs its category
        // granted (Attempt is transparent to the capability check); the
        // catchable IoFailure becomes Err and the fallback bytes win.
        val readFx = compiled.nameMap["readFx"] ?: error("prelude readFx not in the compiled document")
        val value = Interpreter(compiled.store, compiled.hashToNodeId)
            .eval(compiled.root, setOf(readFx))
        assertEquals(Value.BytesV("{}".toByteArray()), value)
    }

    // ------------------------------------------------------------------
    // Scenario 9 — error-line mapping
    // ------------------------------------------------------------------

    @Test
    fun `an injected semantic error reports the Layer F line`() {
        val source = """
            use prelude.{add}

            function main(): Int {
              return add(1, "oops")
            }
        """.trimIndent()
        val compiled = compileAndVerify(source)
        val failed = compiled.verify as? VerifyResult.Failed
            ?: error("expected verification failure, got ${compiled.verify}")
        val badLine = lineOf(source, "\"oops\"")
        val mapped = failed.errors.mapNotNull { e ->
            compiled.authorIdOf(e.at)?.let { compiled.sourceLines[it] }
        }
        assertTrue(badLine in mapped) {
            "expected an error attributed to Layer F line $badLine; errors ${failed.errors} " +
                "mapped to lines $mapped"
        }
    }
}
