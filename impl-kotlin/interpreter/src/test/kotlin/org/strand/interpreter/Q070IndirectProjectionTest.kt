package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * Q-070 — a projected [org.strand.core.Node.ForeignNode] invoked
 * INDIRECTLY (as a value, not via a direct Application node) must still
 * refinement-check at runtime.
 *
 * The direct foreign-call path ([Interpreter.applyForeign]) synthesizes
 * the capability-check `instances` map from the node's `effectProjections`
 * plus the evaluated arguments, so an `ArgRef(0)` path refinement is
 * checked against the exact value the foreign code receives. Before this
 * fix the value-apply paths — a `Value.ForeignFn` invoked via
 * [Interpreter.applyCallable] / as a Handler handle, or as a
 * List.Map/Fold/Filter callback via `applyValueToArgs` — passed an empty
 * (or absent) instances map, so only category-PRESENCE was enforced and
 * the path refinement was skipped: a refinement bypass.
 *
 * These tests prove both indirect paths now deny an out-of-refinement
 * argument with [InterpretError.RefinementViolation] and still admit an
 * in-refinement one. The projected node is a `Test.EffectfulNoOp`
 * (1-arg StringV, no real IO) declaring `Filesystem.Write` with an
 * `ArgRef(0)` projection, so the string argument IS the refinement
 * parameter.
 */
class Q070IndirectProjectionTest {

    @AfterEach
    fun cleanup() {
        CredentialScrubber.resetForTesting()
    }

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

    /**
     * A graph whose ROOT is the projected `Test.EffectfulNoOp` ForeignNode
     * itself. Evaluating the root yields the [Value.ForeignFn] value the
     * value-apply paths receive; the effectProjections carry a single
     * `ArgRef(0)` source so the refinement parameter is the call argument.
     */
    private fun projectedWriteFnGraph(): Loaded = load("""{
      "version": 1, "root": "write",
      "nodes": {
        "intT":      { "type": "PrimitiveType", "kind": "Int" },
        "strT":      { "type": "PrimitiveType", "kind": "String" },
        "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                       "parameters": ["strT"] },
        "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                       "effects": ["fsWriteFx"] },
        "write":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                       "foreignType": "writeT", "effects": ["fsWriteFx"],
                       "effectProjections": [
                         { "category": "fsWriteFx",
                           "sources": [{ "kind": "ArgRef", "index": 0 }] }
                       ] }
      }
    }""")

    /**
     * A graph that maps the projected write ForeignNode over a one-element
     * list of a caller-supplied path string via `strand-builtin:List.Map`.
     * The list element flows into the callback as `head`, so the
     * refinement checked at the callback invocation is against that value.
     */
    private fun mapWriteOverPathGraph(path: String): Loaded = load("""{
      "version": 1, "root": "mapApp",
      "nodes": {
        "intT":      { "type": "PrimitiveType", "kind": "Int" },
        "strT":      { "type": "PrimitiveType", "kind": "String" },
        "fsWriteFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write",
                       "parameters": ["strT"] },
        "writeT":    { "type": "FunctionType", "parameters": ["strT"], "result": "intT",
                       "effects": ["fsWriteFx"] },
        "write":     { "type": "ForeignNode", "target": "strand-builtin:Test.EffectfulNoOp",
                       "foreignType": "writeT", "effects": ["fsWriteFx"],
                       "effectProjections": [
                         { "category": "fsWriteFx",
                           "sources": [{ "kind": "ArgRef", "index": 0 }] }
                       ] },

        "headFldT":  { "type": "ProductTypeField", "name": "head", "fieldType": "strT" },
        "elemPayT":  { "type": "ProductType", "fields": ["headFldT"] },
        "listT":     { "type": "PrimitiveType", "kind": "Int" },
        "mapT":      { "type": "FunctionType", "parameters": ["listT", "writeT"], "result": "listT",
                       "effects": ["fsWriteFx"] },
        "mapFn":     { "type": "ForeignNode", "target": "strand-builtin:List.Map",
                       "foreignType": "mapT", "effects": ["fsWriteFx"] },

        "pathLit":   { "type": "StringLit", "value": ${jsonString(path)} },
        "nilV":      { "type": "SumValue", "caseName": "Nil", "ofType": "listT" },
        "headFV":    { "type": "ProductFieldValue", "fieldName": "head", "value": "pathLit" },
        "tailFV":    { "type": "ProductFieldValue", "fieldName": "tail", "value": "nilV" },
        "consPay":   { "type": "ProductValue", "ofType": "elemPayT", "fields": ["headFV", "tailFV"] },
        "consV":     { "type": "SumValue", "caseName": "Cons", "ofType": "listT", "payload": "consPay" },

        "mapApp":    { "type": "Application", "function": "mapFn",
                       "arguments": ["consV", "write"], "effectInstances": [] }
      }
    }""")

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun grantForPath(category: NodeId, path: String): CapabilitySet = CapabilitySet(mapOf(
        category to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(Value.StringV(path)))),
        )
    ))

    // ----- applyCallable value-apply path (Interpreter.applyValue) -----

    @Test
    fun `projected ForeignNode applied as a value denies an out-of-refinement argument`() {
        val l = projectedWriteFnGraph()
        val fsWrite = l.names.getValue("fsWriteFx")
        val interp = Interpreter(l.store, l.hashToNodeId)
        // Evaluate the root ForeignNode to a Value.ForeignFn.
        val fnValue = interp.eval(l.root, grantForPath(fsWrite, "/tmp/allowed"))
        assertTrue(fnValue is Value.ForeignFn)

        // Grant covers only "/tmp/allowed"; apply the value with a
        // DIFFERENT path — before Q-070 this passed (emptyMap skipped
        // refinement); now it must raise RefinementViolation.
        val ex = assertThrows(InterpretException::class.java) {
            interp.applyCallable(
                fnValue,
                listOf(Value.StringV("/etc/passwd")),
                grantForPath(fsWrite, "/tmp/allowed"),
            )
        }
        val err = ex.error
        assertTrue(err is InterpretError.RefinementViolation) {
            "expected RefinementViolation, got ${err::class.simpleName}: $err"
        }
        val rv = err as InterpretError.RefinementViolation
        assertEquals(fsWrite, rv.category)
        assertEquals(listOf(Value.StringV("/etc/passwd")), rv.requirement)
    }

    @Test
    fun `projected ForeignNode applied as a value admits an in-refinement argument`() {
        val l = projectedWriteFnGraph()
        val fsWrite = l.names.getValue("fsWriteFx")
        val interp = Interpreter(l.store, l.hashToNodeId)
        val fnValue = interp.eval(l.root, grantForPath(fsWrite, "/tmp/allowed"))
        assertTrue(fnValue is Value.ForeignFn)

        // Same path as the grant — the refinement covers it, so the call
        // proceeds to the no-op builtin (which returns IntV(0)).
        val result = interp.applyCallable(
            fnValue,
            listOf(Value.StringV("/tmp/allowed")),
            grantForPath(fsWrite, "/tmp/allowed"),
        )
        assertEquals(Value.IntV(0), result)
    }

    // ----- List.Map callback path (Interpreter.applyValueToArgs) -----

    @Test
    fun `projected ForeignNode as a List_Map callback denies an out-of-refinement element`() {
        val l = mapWriteOverPathGraph("/etc/passwd")
        val fsWrite = l.names.getValue("fsWriteFx")
        val ex = assertThrows(InterpretException::class.java) {
            Interpreter(l.store, l.hashToNodeId).eval(l.root, grantForPath(fsWrite, "/tmp/allowed"))
        }
        val err = ex.error
        assertTrue(err is InterpretError.RefinementViolation) {
            "expected RefinementViolation from the map callback, got ${err::class.simpleName}: $err"
        }
        val rv = err as InterpretError.RefinementViolation
        assertEquals(fsWrite, rv.category)
        assertEquals(listOf(Value.StringV("/etc/passwd")), rv.requirement)
    }

    @Test
    fun `projected ForeignNode as a List_Map callback admits an in-refinement element`() {
        val l = mapWriteOverPathGraph("/tmp/allowed")
        val fsWrite = l.names.getValue("fsWriteFx")
        // The single list element equals the granted path, so the callback
        // refinement passes and the map completes.
        val result = Interpreter(l.store, l.hashToNodeId)
            .eval(l.root, grantForPath(fsWrite, "/tmp/allowed"))
        // Result is a Cons/Nil list of the no-op's UnitV results.
        assertTrue(result is Value.SumV)
    }
}
