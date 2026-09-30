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
 * target's effect floor ([BuiltinEffectTable] on this `:interpreter` classpath,
 * which carries no Q-056 oracle provider; `VerifyError.BuiltinEffectMismatch`
 * is main's Q-056 variant, which falls back to the table without an oracle).
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
        val err = f.errors.filterIsInstance<VerifyError.BuiltinEffectMismatch>().single()
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

    // ---- A4: an instance-free performing call needs an unrefined grant ----

    private val unprojectedFsReadJson = """{
        "version": 1, "root": "app",
        "nodes": {
          "strT":   { "type": "PrimitiveType", "kind": "String" },
          "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
          "readFx": { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
          "readT":  { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT", "effects": ["readFx"] },
          "fsRead": { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "readT",
                      "effects": ["readFx"] },
          "path":   { "type": "StringLit", "value": "strand-a4-missing.txt" },
          "app":    { "type": "Application", "function": "fsRead", "arguments": ["path"] }
        }
      }"""

    @Test
    fun `an unprojected read with no effectInstances is denied under a refined grant`() {
        val l = load(unprojectedFsReadJson)
        assertTrue(verify(l) is VerifyResult.Ok) { "the verifier admits omitted effectInstances: ${verify(l)}" }
        val readFx = l.names.getValue("readFx")
        val refined = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/allowed.txt")))),
        )))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, refined)
        }
        val err = ex.error as? InterpretError.RefinementViolation
            ?: error("expected RefinementViolation, got ${ex.error}")
        assertEquals(readFx, err.category)
        // Fs.Read has a registry resource projection (BuiltinEffectTable):
        // the requirement is the path the builtin would read, although the
        // binding carries no projection and the call no instance.
        assertEquals(listOf("strand-a4-missing.txt"), err.report.requested)
    }

    @Test
    fun `an unprojected read is matched against the path it reads`() {
        val l = load(unprojectedFsReadJson)
        val readFx = l.names.getValue("readFx")
        val exact = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("strand-a4-missing.txt")))),
        )))
        // A grant for exactly that path covers the call; the read then fails
        // on the missing file (or the sandbox), which is not a denial.
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, exact)
        }
        assertTrue(ex.error !is InterpretError.RefinementViolation && ex.error !is InterpretError.CapabilityViolation) {
            "a grant for the path read must admit the call; got ${ex.error}"
        }
    }

    @Test
    fun `an authored instance cannot redirect a registry builtin's capability check`() {
        // The EffectDecl declares the allowed path; the argument is another.
        // Without a binding projection the check used to take the declared
        // value (the confused-deputy gap for unprojected bindings).
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":   { "type": "PrimitiveType", "kind": "String" },
              "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
              "readFx": { "type": "EffectCategory", "categoryName": "Filesystem.Read", "parameters": ["strT"] },
              "readT":  { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT" },
              "fsRead": { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "readT",
                          "effects": ["readFx"] },
              "declared": { "type": "StringLit", "value": "/tmp/allowed.txt" },
              "actual": { "type": "StringLit", "value": "strand-a4-other.txt" },
              "decl":   { "type": "EffectDecl", "effectType": "readFx", "parameters": ["declared"] },
              "app":    { "type": "Application", "function": "fsRead", "arguments": ["actual"],
                          "effectInstances": ["decl"] }
            }
          }""")
        assertTrue(verify(l) is VerifyResult.Ok) { "the verifier admits the unprojected binding: ${verify(l)}" }
        val readFx = l.names.getValue("readFx")
        val refined = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/allowed.txt")))),
        )))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, refined)
        }
        val err = ex.error as? InterpretError.RefinementViolation
            ?: error("expected RefinementViolation, got ${ex.error}")
        assertEquals(listOf("strand-a4-other.txt"), err.report.requested)
    }

    @Test
    fun `a refined grant does not cover a category the program declares without parameters`() {
        // Declaring Filesystem.Read with no parameter must not turn a
        // path-refined grant into a grant for every path.
        val l = load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":   { "type": "PrimitiveType", "kind": "String" },
              "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
              "readFx": { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
              "readT":  { "type": "FunctionType", "parameters": ["strT"], "result": "bytesT" },
              "fsRead": { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "readT",
                          "effects": ["readFx"] },
              "path":   { "type": "StringLit", "value": "strand-a4-other.txt" },
              "app":    { "type": "Application", "function": "fsRead", "arguments": ["path"] }
            }
          }""")
        assertTrue(verify(l) is VerifyResult.Ok) { "the verifier admits the parameterless declaration: ${verify(l)}" }
        val readFx = l.names.getValue("readFx")
        val refined = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("/tmp/allowed.txt")))),
        )))
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, refined)
        }
        val err = ex.error as? InterpretError.RefinementViolation
            ?: error("expected RefinementViolation, got ${ex.error}")
        assertEquals(listOf("strand-a4-other.txt"), err.report.requested)

        // The same declaration bound to a builtin with no registry
        // projection: nothing can be matched, so the refined grant denies.
        val stub = load("""{
            "version": 1, "root": "app",
            "nodes": {
              "strT":   { "type": "PrimitiveType", "kind": "String" },
              "intT":   { "type": "PrimitiveType", "kind": "Int" },
              "logFx":  { "type": "EffectCategory", "categoryName": "Log.Write" },
              "noopT":  { "type": "FunctionType", "parameters": ["strT"], "result": "intT" },
              "noop":   { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp", "foreignType": "noopT",
                          "effects": ["logFx"] },
              "msg":    { "type": "StringLit", "value": "m" },
              "app":    { "type": "Application", "function": "noop", "arguments": ["msg"] }
            }
          }""")
        val logFx = stub.names.getValue("logFx")
        val pinned = CapabilitySet(mapOf(logFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV("audit")))),
        )))
        val stubEx = assertThrows<InterpretException> {
            Interpreter(stub.store, stub.hashToNodeId).eval(stub.root, pinned)
        }
        assertTrue(stubEx.error is InterpretError.RefinementViolation) { "got ${stubEx.error}" }
        assertEquals(Value.IntV(0), Interpreter(stub.store, stub.hashToNodeId).eval(stub.root, setOf(logFx)))
    }

    @Test
    fun `an unprojected read with no effectInstances passes the check under an unrefined grant`() {
        val l = load(unprojectedFsReadJson)
        val readFx = l.names.getValue("readFx")
        val unrefined = CapabilitySet(mapOf(readFx to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Wildcard)),
        )))
        // The capability check passes; the read itself then fails on the
        // missing file (or the sandbox), which is not a capability denial.
        val ex = assertThrows<InterpretException> {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, unrefined)
        }
        assertTrue(ex.error !is InterpretError.RefinementViolation && ex.error !is InterpretError.CapabilityViolation) {
            "unrefined grant must admit the call; got ${ex.error}"
        }
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
