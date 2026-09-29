package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.BuiltinEffectTable
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyError
import org.strand.verifier.VerifyResult

/**
 * Adversarial tests for the foreign-effect trust boundary (2026-09-29 review,
 * Stream A): a ForeignNode's declared effect row is graph-supplied, so every
 * runtime path that dispatches a foreign or closure callable must enforce the
 * same row the verifier charged, and a registry target's row must cover the
 * target's effect floor ([BuiltinEffectTable]).
 */
class ForeignEffectTrustTest {

    private data class Loaded(
        val store: NodeStore,
        val root: NodeId,
        val names: Map<String, NodeId>,
        val hashToNodeId: Map<Hash, NodeId>,
    )

    private fun load(json: String): Loaded {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Loaded(finalized.store, finalized.root, ingest.nameMap, finalized.hashToNodeId)
    }

    private fun verify(l: Loaded): VerifyResult = Verifier(l.store, l.hashToNodeId).verify(l.root)

    // ---- A1: registry ⇔ table consistency --------------------------------

    @Test
    fun `every effectful registry entry has an effect-floor row and every pure entry has none`() {
        val meta = Builtins.entryMetadata()
        val missingRow = meta.filter { it.effectful && !BuiltinEffectTable.isExempt(it.target) }
            .filter { BuiltinEffectTable.requiredCategories(it.target) == null }
            .map { it.target }
        assertTrue(missingRow.isEmpty()) { "effectful builtins without a BuiltinEffectTable row: $missingRow" }

        val pureWithRow = meta.filter { !it.effectful && it.target in BuiltinEffectTable.table }
            .map { it.target }
        assertTrue(pureWithRow.isEmpty()) { "effect-free builtins carrying an effect floor: $pureWithRow" }

        val registered = Builtins.registeredTargets()
        val orphanRows = BuiltinEffectTable.table.keys
            .filter { it.startsWith("strand-builtin:") && it !in registered }
        assertTrue(orphanRows.isEmpty()) { "table rows for unregistered targets: $orphanRows" }
    }

    @Test
    fun `the Test namespace is exempt and the effectful test builtin is registered effectful`() {
        assertTrue(BuiltinEffectTable.isExempt("strand-builtin:Test.EffectfulNoOp"))
        assertEquals(null, BuiltinEffectTable.requiredCategories("strand-builtin:Test.EffectfulNoOp"))
        assertTrue(Builtins.entryMetadata().single { it.target == "strand-builtin:Test.EffectfulNoOp" }.effectful)
    }

    // ---- A1: the exploit, admission and dispatch ---------------------------

    private fun underDeclaredFsWrite(path: String) = """{
        "version": 1, "root": "app",
        "nodes": {
          "intT":   { "type": "PrimitiveType", "kind": "Int" },
          "strT":   { "type": "PrimitiveType", "kind": "String" },
          "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
          "writeT": { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
          "write":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                      "foreignType": "writeT", "effects": [] },
          "path":   { "type": "StringLit", "value": "$path" },
          "data":   { "type": "BytesLit", "value": "deadbeef" },
          "app":    { "type": "Application", "function": "write", "arguments": ["path", "data"] }
        }
      }"""

    @Test
    fun `under-declared Fs Write is rejected at admission`() {
        val r = verify(load(underDeclaredFsWrite("pwned.txt")))
        val f = r as? VerifyResult.Failed ?: error("expected rejection, got $r")
        val err = f.errors.filterIsInstance<VerifyError.ForeignEffectUnderDeclared>().single()
        assertEquals("strand-builtin:Fs.Write", err.target)
        assertEquals(setOf("Filesystem.Write"), err.missing)
    }

    @Test
    fun `under-declared Fs Write is refused at dispatch even without verification`() {
        val l = load(underDeclaredFsWrite("strand-a1-should-not-exist.txt"))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        val err = ex.error as? InterpretError.BuiltinContractViolation ?: error("expected BuiltinContractViolation, got ${ex.error}")
        assertEquals("strand-builtin:Fs.Write", err.target)
        assertTrue(!java.io.File("strand-a1-should-not-exist.txt").exists())
    }

    @Test
    fun `honestly declared Fs Write verifies and is denied under an empty grant`() {
        val json = underDeclaredFsWrite("x.txt")
            .replace(""""nodes": {""", """"nodes": {
              "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },""")
            .replace(""""effects": [] }""", """"effects": ["writeFx"] }""")
        val l = load(json)
        assertTrue(verify(l) is VerifyResult.Ok) { "honest declaration must verify: ${verify(l)}" }
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation) { "got ${ex.error}" }
    }

    @Test
    fun `a floor declared only on the foreignType is accepted`() {
        val json = underDeclaredFsWrite("x.txt")
            .replace(""""nodes": {""", """"nodes": {
              "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },""")
            .replace(""""result": "intT" },""", """"result": "intT", "effects": ["writeFx"] },""")
        assertTrue(verify(load(json)) is VerifyResult.Ok)
    }

    // ---- A2: foreignType effects are part of the runtime row --------------

    private fun typeOnlyTimeNow(handled: Boolean) = """{
        "version": 1, "root": "${if (handled) "h" else "call"}",
        "nodes": {
          "intT":   { "type": "PrimitiveType", "kind": "Int" },
          "timeFx": { "type": "EffectCategory", "categoryName": "Time.Now" },
          "nowT":   { "type": "FunctionType", "parameters": [], "result": "intT", "effects": ["timeFx"] },
          "now":    { "type": "ForeignNode", "target": "strand-builtin:Time.Now",
                      "foreignType": "nowT", "effects": [] },
          "call":   { "type": "Application", "function": "now", "arguments": [] },
          "mock":   { "type": "IntLit", "value": 99 },
          "mockFn": { "type": "Lambda", "parameters": [], "body": "mock" },
          "h":      { "type": "Handler", "intercept": "timeFx", "handle": "mockFn", "body": "call" }
        }
      }"""

    @Test
    fun `an effect declared only on the foreignType is intercepted by a Handler`() {
        val l = load(typeOnlyTimeNow(handled = true))
        val ok = verify(l) as? VerifyResult.Ok ?: error("expected Ok, got ${verify(l)}")
        assertTrue(ok.rootClosure(l.root).isEmpty()) { "handler subtracts the type-only effect statically" }
        assertEquals(Value.IntV(99), Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY))
    }

    @Test
    fun `an effect declared only on the foreignType is capability-checked when not intercepted`() {
        val l = load(typeOnlyTimeNow(handled = false))
        val ok = verify(l) as? VerifyResult.Ok ?: error("expected Ok, got ${verify(l)}")
        assertEquals(setOf(l.names.getValue("timeFx")), ok.rootClosure(l.root))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation) { "got ${ex.error}" }
    }
}
