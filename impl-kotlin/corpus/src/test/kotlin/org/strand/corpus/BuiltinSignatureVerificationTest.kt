package org.strand.corpus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.BuiltinSignatures
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult

/**
 * Q-056 end-to-end: builtin signature + effect verification at ForeignNode
 * admission, exercised through the REAL ServiceLoader-resolved
 * `BuiltinsSignatureOracle` (the `:authoring` provider is on the corpus test
 * classpath). A well-formed `strand-builtin:` ForeignNode admits; an
 * under-declared-effects one is rejected with `BuiltinEffectMismatch`; a
 * wrong-signature monomorphic one with `BuiltinSignatureMismatch`; and the
 * no-oracle path degrades to skip.
 */
class BuiltinSignatureVerificationTest {

    @AfterEach
    fun teardown() {
        BuiltinSignatures.override = null
    }

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    private fun errors(result: VerifyResult): List<VerifyError> =
        (result as? VerifyResult.Failed)?.errors ?: emptyList()

    // A pure `Int.Add` foreign node applied to two literals — the canonical
    // well-formed monomorphic case.
    private fun intAddProgram(paramA: String, paramB: String, resultT: String): String = """{
      "version": 1, "root": "call",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "boolT": { "type": "PrimitiveType", "kind": "Bool" },
        "addT":  { "type": "FunctionType", "parameters": ["$paramA", "$paramB"], "result": "$resultT" },
        "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
        "a":     { "type": "IntLit", "value": 2 },
        "b":     { "type": "IntLit", "value": 3 },
        "call":  { "type": "Application", "function": "add", "arguments": ["a", "b"] }
      }
    }"""

    // An `Fs.Write` foreign node ((String, Bytes) -> Int, effect
    // Filesystem.Write) with a declared effects list of [effects].
    private fun fsWriteProgram(effects: List<String>): String {
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
        "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
        "intT":    { "type": "PrimitiveType", "kind": "Int" },
        "fsWriteT": { "type": "FunctionType", "parameters": ["stringT", "bytesT"], "result": "intT" },
        "write":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Write", "foreignType": "fsWriteT"$effectsField }
      }
    }"""
    }

    @Test
    fun `a well-formed monomorphic builtin ForeignNode admits`() {
        val result = verify(intAddProgram("intT", "intT", "intT"))
        assertTrue(result is VerifyResult.Ok) { "expected admission, got: $result" }
    }

    @Test
    fun `a well-formed effectful builtin ForeignNode admits with the exact declared effect`() {
        val result = verify(fsWriteProgram(listOf("Filesystem.Write")))
        assertTrue(result is VerifyResult.Ok) { "expected admission, got: $result" }
    }

    @Test
    fun `an under-declared-effects builtin ForeignNode is rejected with BuiltinEffectMismatch`() {
        val result = verify(fsWriteProgram(emptyList()))
        val mismatch = errors(result).filterIsInstance<VerifyError.BuiltinEffectMismatch>().single()
        assertEquals("strand-builtin:Fs.Write", mismatch.target)
        assertEquals(setOf("Filesystem.Write"), mismatch.missing)
        assertEquals(setOf("Filesystem.Write"), mismatch.actual)
        assertEquals(emptySet<String>(), mismatch.declared)
    }

    @Test
    fun `an over-declared-effects builtin ForeignNode is rejected with BuiltinEffectMismatch`() {
        // Int.Add is pure; declaring any effect over-declares.
        val json = """{
          "version": 1, "root": "add",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "fx":    { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["intT"] },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT", "effects": ["fx"] }
          }
        }"""
        val mismatch = errors(verify(json)).filterIsInstance<VerifyError.BuiltinEffectMismatch>().single()
        assertEquals("strand-builtin:Int.Add", mismatch.target)
        assertEquals(emptySet<String>(), mismatch.actual)
        assertEquals(setOf("Filesystem.Write"), mismatch.declared)
        assertEquals(emptySet<String>(), mismatch.missing)
    }

    @Test
    fun `a wrong-signature monomorphic builtin is rejected with BuiltinSignatureMismatch`() {
        // Int.Add is (Int, Int) -> Int; declare the result as Bool.
        val result = verify(intAddProgram("intT", "intT", "boolT"))
        val mismatch = errors(result).filterIsInstance<VerifyError.BuiltinSignatureMismatch>().single()
        assertEquals("strand-builtin:Int.Add", mismatch.target)
    }

    @Test
    fun `a wrong-arity monomorphic builtin is rejected with BuiltinSignatureMismatch`() {
        // Int.Add takes two parameters; declare only one.
        val json = """{
          "version": 1, "root": "add",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "addT": { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "add":  { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" }
          }
        }"""
        val mismatch = errors(verify(json)).filterIsInstance<VerifyError.BuiltinSignatureMismatch>().single()
        assertEquals("strand-builtin:Int.Add", mismatch.target)
    }

    @Test
    fun `an unknown strand-builtin target degrades to skip`() {
        // Not in any registry table — the oracle returns null and the check
        // is skipped, so the ForeignNode admits on its declared shape alone.
        val json = """{
          "version": 1, "root": "call",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "fnT":  { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "fn":   { "type": "ForeignNode", "target": "strand-builtin:Totally.Unknown.Builtin", "foreignType": "fnT" },
            "a":    { "type": "IntLit", "value": 1 },
            "call": { "type": "Application", "function": "fn", "arguments": ["a"] }
          }
        }"""
        assertTrue(verify(json) is VerifyResult.Ok) { "unknown builtin must degrade to skip: ${verify(json)}" }
    }

    @Test
    fun `a non-builtin foreign target is not cross-checked`() {
        // A wasm: binding is under the Q-006 trust model — no cross-check.
        val json = """{
          "version": 1, "root": "call",
          "nodes": {
            "intT": { "type": "PrimitiveType", "kind": "Int" },
            "fnT":  { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "fn":   { "type": "ForeignNode", "target": "wasm:custom.module", "foreignType": "fnT" },
            "a":    { "type": "IntLit", "value": 1 },
            "call": { "type": "Application", "function": "fn", "arguments": ["a"] }
          }
        }"""
        assertTrue(verify(json) is VerifyResult.Ok) { "non-builtin target must not be cross-checked: ${verify(json)}" }
    }

    @Test
    fun `with the oracle overridden to empty the check degrades to skip`() {
        // Install an override that knows no target — every strand-builtin:
        // lookup returns null, so a genuinely mis-declared Int.Add still
        // admits. This is the no-registry degrade-to-skip path.
        BuiltinSignatures.override = object : org.strand.verifier.BuiltinSignatureOracle {
            override fun effectNamesFor(target: String): Set<String>? = null
            override fun signatureShapeFor(target: String) = null
        }
        // Int.Add declared with a wrong (Bool) result — would fail with the
        // real oracle, but the override reports it unknown.
        assertTrue(verify(intAddProgram("intT", "intT", "boolT")) is VerifyResult.Ok) {
            "an oracle that knows no target must degrade to skip"
        }
    }
}
