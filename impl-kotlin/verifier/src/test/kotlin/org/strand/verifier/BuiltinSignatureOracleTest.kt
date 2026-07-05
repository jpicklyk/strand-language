package org.strand.verifier

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Q-056 verifier-side mechanism test: the ForeignNode admission cross-check
 * fires through the [BuiltinSignatures.override] seam, and degrades to skip
 * when no oracle is installed.
 *
 * The `:verifier` module has no `:authoring` provider on its classpath, so
 * the ServiceLoader resolves nothing and the check is off by default here —
 * which is exactly the no-oracle degrade-to-skip path. A fake oracle is
 * installed through [BuiltinSignatures.override] to exercise the fire path in
 * isolation. The end-to-end path through the real ServiceLoader-resolved
 * `:authoring` oracle is covered by `BuiltinSignatureVerificationTest` in
 * `:corpus`.
 */
class BuiltinSignatureOracleTest {

    private val effectfulTarget = "strand-builtin:Fake.Write"
    private val pureTarget = "strand-builtin:Fake.Add"

    @AfterEach
    fun removeFakeOracle() {
        BuiltinSignatures.override = null
    }

    private fun installFakeOracle() {
        BuiltinSignatures.override = object : BuiltinSignatureOracle {
            override fun effectNamesFor(target: String): Set<String>? = when (target) {
                effectfulTarget -> setOf("Filesystem.Write")
                pureTarget -> emptySet()
                else -> null
            }

            override fun signatureShapeFor(target: String): BuiltinShape.Fun? = when (target) {
                pureTarget -> BuiltinShape.Fun(
                    parameters = listOf(BuiltinShape.Prim("Int"), BuiltinShape.Prim("Int")),
                    result = BuiltinShape.Prim("Int"),
                )
                else -> null
            }
        }
    }

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private fun errors(result: VerifyResult): List<VerifyError> =
        (result as? VerifyResult.Failed)?.errors ?: emptyList()

    /** A pure `Fake.Add` foreign node with a declared (a, b) -> r signature. */
    private fun addProgram(a: String, b: String, r: String): String = """{
      "version": 1, "root": "add",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "boolT": { "type": "PrimitiveType", "kind": "Bool" },
        "addT":  { "type": "FunctionType", "parameters": ["$a", "$b"], "result": "$r" },
        "add":   { "type": "ForeignNode", "target": "$pureTarget", "foreignType": "addT" }
      }
    }"""

    /** An effectful `Fake.Write` node declaring [effects]. */
    private fun writeProgram(effects: List<String>): String {
        val effNodes = effects.mapIndexed { i, name ->
            """"fx$i": { "type": "EffectCategory", "categoryName": "$name" }"""
        }.joinToString(",\n        ")
        val effRefs = effects.indices.joinToString(", ") { "\"fx$it\"" }
        val effectsField = if (effects.isEmpty()) "" else ""","effects": [$effRefs]"""
        val effLine = if (effNodes.isEmpty()) "" else "$effNodes,\n        "
        return """{
      "version": 1, "root": "write",
      "nodes": {
        ${effLine}"stringT": { "type": "PrimitiveType", "kind": "String" },
        "intT":    { "type": "PrimitiveType", "kind": "Int" },
        "wT":      { "type": "FunctionType", "parameters": ["stringT"], "result": "intT" },
        "write":   { "type": "ForeignNode", "target": "$effectfulTarget", "foreignType": "wT"$effectsField }
      }
    }"""
    }

    @Test
    fun `with no oracle installed a mis-declared builtin admits (degrade to skip)`() {
        // No override, no ServiceLoader provider on the :verifier classpath.
        assertTrue(BuiltinSignatures.override == null)
        // Fake.Add declared with a wrong Bool result — nothing to check against.
        assertTrue(verify(addProgram("intT", "intT", "boolT")) is VerifyResult.Ok)
    }

    @Test
    fun `a well-formed pure builtin admits under the fake oracle`() {
        installFakeOracle()
        assertTrue(verify(addProgram("intT", "intT", "intT")) is VerifyResult.Ok)
    }

    @Test
    fun `a wrong-signature pure builtin is rejected with BuiltinSignatureMismatch`() {
        installFakeOracle()
        val mismatch = errors(verify(addProgram("intT", "intT", "boolT")))
            .filterIsInstance<VerifyError.BuiltinSignatureMismatch>().single()
        assertEquals(pureTarget, mismatch.target)
        assertEquals(
            BuiltinShape.Fun(
                listOf(BuiltinShape.Prim("Int"), BuiltinShape.Prim("Int")),
                BuiltinShape.Prim("Int"),
            ),
            mismatch.actual,
        )
    }

    @Test
    fun `an under-declared effectful builtin is rejected with BuiltinEffectMismatch`() {
        installFakeOracle()
        val mismatch = errors(verify(writeProgram(emptyList())))
            .filterIsInstance<VerifyError.BuiltinEffectMismatch>().single()
        assertEquals(effectfulTarget, mismatch.target)
        assertEquals(setOf("Filesystem.Write"), mismatch.missing)
    }

    @Test
    fun `a well-formed effectful builtin admits (signature deferred, effects exact)`() {
        installFakeOracle()
        assertTrue(verify(writeProgram(listOf("Filesystem.Write"))) is VerifyResult.Ok)
    }
}
