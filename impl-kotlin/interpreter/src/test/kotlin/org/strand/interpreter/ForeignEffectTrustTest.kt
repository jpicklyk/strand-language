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

    // ---- A3: callbacks run by higher-order builtins are checked ------------

    /**
     * `List.Map(fsRead, ["secret.txt"])`. The List.Map binding declares no
     * effects (Q-070: the static row of a higher-order builtin does not yet
     * carry its callback's effects), so the only runtime gate on the read is
     * the callback dispatch itself.
     */
    private val mapFsReadJson = """{
        "version": 1, "root": "app",
        "nodes": {
          "strT":     { "type": "PrimitiveType", "kind": "String" },
          "bytesT":   { "type": "PrimitiveType", "kind": "Bytes" },
          "readFx":   { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
          "readT":    { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT", "effects": ["readFx"] },
          "fsRead":   { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "readT",
                        "effects": ["readFx"],
                        "effectProjections": [ { "category": "readFx", "sources": [ { "kind": "ArgRef", "index": 0 } ] } ] },
          "headF":    { "type": "ProductTypeField", "name": "head", "fieldType": "strT" },
          "tailF":    { "type": "ProductTypeField", "name": "tail", "fieldType": "self" },
          "consP":    { "type": "ProductType", "fields": ["headF", "tailF"] },
          "consC":    { "type": "SumTypeCase", "name": "Cons", "caseType": "consP" },
          "nilC":     { "type": "SumTypeCase", "name": "Nil", "caseType": null },
          "body":     { "type": "SumType", "cases": ["consC", "nilC"] },
          "self":     { "type": "RecursiveSelf" },
          "listT":    { "type": "RecursiveType", "body": "body" },
          "headO":    { "type": "ProductTypeField", "name": "head", "fieldType": "strT" },
          "tailO":    { "type": "ProductTypeField", "name": "tail", "fieldType": "listT" },
          "consO":    { "type": "ProductType", "fields": ["headO", "tailO"] },
          "nil":      { "type": "SumValue", "ofType": "listT", "caseName": "Nil", "payload": null },
          "secret":   { "type": "StringLit", "value": "secret.txt" },
          "hv":       { "type": "ProductFieldValue", "fieldName": "head", "value": "secret" },
          "tv":       { "type": "ProductFieldValue", "fieldName": "tail", "value": "nil" },
          "cell":     { "type": "ProductValue", "ofType": "consO", "fields": ["hv", "tv"] },
          "paths":    { "type": "SumValue", "ofType": "listT", "caseName": "Cons", "payload": "cell" },
          "mapT":     { "type": "FunctionType", "parameters": ["listT", "readT"], "result": "listT" },
          "listMap":  { "type": "ForeignNode", "target": "strand-builtin:List.Map", "foreignType": "mapT" },
          "cbP":      { "type": "ParameterDecl", "name": "p", "paramType": "strT" },
          "cbRef":    { "type": "VarRef", "binder": "cbP" },
          "cb":       { "type": "Lambda", "parameters": ["cbP"], "body": "cbRef", "effects": ["readFx"] },
          "app":      { "type": "Application", "function": "listMap", "arguments": ["paths", "CALLBACK"] }
        }
      }"""

    @Test
    fun `a ForeignFn callback is refinement-checked against the callback argument`() {
        val l = load(mapFsReadJson.replace("CALLBACK", "fsRead"))
        val readFx = l.names.getValue("readFx")
        val grant = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("allowed.txt")))),
        )))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, grant)
        }
        val err = ex.error as? InterpretError.RefinementViolation
            ?: error("expected RefinementViolation, got ${ex.error}")
        assertEquals(listOf<Value>(Value.StringV("secret.txt")), err.requirement)
    }

    @Test
    fun `a ForeignFn callback with no grant raises CapabilityViolation`() {
        val l = load(mapFsReadJson.replace("CALLBACK", "fsRead"))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation) { "got ${ex.error}" }
    }

    @Test
    fun `a Closure callback's declared effects are capability-checked`() {
        val l = load(mapFsReadJson.replace("CALLBACK", "cb"))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, CapabilitySet.EMPTY)
        }
        assertTrue(ex.error is InterpretError.CapabilityViolation) { "got ${ex.error}" }
    }
}
