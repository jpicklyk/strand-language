package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.hashing.Hasher

/**
 * Builtin contract violation (hygiene follow-up for N-047 Attempt, § 8).
 *
 * `Int.Div`, `Int.Mod`, and `Math.Mod` by zero previously escaped as raw
 * `java.lang.IllegalArgumentException` from `require(...)` in Builtins.kt.
 * The interpreter now catches those at the `builtin.invoke` boundary and
 * rethrows as `InterpretException(BuiltinContractViolation(...))`.
 *
 * Tests:
 *  1. Each of the three named builtins raises [InterpretError.BuiltinContractViolation]
 *     carrying the correct [target] and a [detail] mentioning division by zero.
 *  2. [InterpretError.BuiltinContractViolation.isCatchable] is false, so a
 *     surrounding `Attempt` node must NOT absorb it — the exception escapes.
 *  3. The variant's [at] is the call-site [NodeId] (non-null in the interpreter).
 */
class BuiltinsContractViolationTest {

    // ------------------------------------------------------------------
    // Unit-level: call through the full interpreter graph for each builtin
    // ------------------------------------------------------------------

    private fun loadAndEval(json: String): InterpretException {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return assertThrows {
            Interpreter(finalized.store, finalized.hashToNodeId).eval(finalized.root)
        }
    }

    private val intDivByZeroJson = """{
      "version": 1, "root": "app",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "divT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
        "divFn": { "type": "ForeignNode", "target": "strand-builtin:Int.Div",
                   "foreignType": "divT", "effects": [] },
        "ten":   { "type": "IntLit", "value": 10 },
        "zero":  { "type": "IntLit", "value": 0 },
        "app":   { "type": "Application", "function": "divFn", "arguments": ["ten", "zero"] }
      }
    }"""

    private val intModByZeroJson = """{
      "version": 1, "root": "app",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "modT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
        "modFn": { "type": "ForeignNode", "target": "strand-builtin:Int.Mod",
                   "foreignType": "modT", "effects": [] },
        "ten":   { "type": "IntLit", "value": 10 },
        "zero":  { "type": "IntLit", "value": 0 },
        "app":   { "type": "Application", "function": "modFn", "arguments": ["ten", "zero"] }
      }
    }"""

    private val mathModByZeroJson = """{
      "version": 1, "root": "app",
      "nodes": {
        "intT":  { "type": "PrimitiveType", "kind": "Int" },
        "modT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
        "modFn": { "type": "ForeignNode", "target": "strand-builtin:Math.Mod",
                   "foreignType": "modT", "effects": [] },
        "ten":   { "type": "IntLit", "value": 10 },
        "zero":  { "type": "IntLit", "value": 0 },
        "app":   { "type": "Application", "function": "modFn", "arguments": ["ten", "zero"] }
      }
    }"""

    @Test
    fun `Int_Div by zero raises BuiltinContractViolation with correct target and detail`() {
        val ex = loadAndEval(intDivByZeroJson)
        val err = ex.error
        assertTrue(err is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation, got $err"
        }
        val cv = err as InterpretError.BuiltinContractViolation
        assertEquals("strand-builtin:Int.Div", cv.target)
        assertTrue(cv.detail.contains("division by zero", ignoreCase = true)) {
            "expected detail to mention 'division by zero', got: ${cv.detail}"
        }
        // at is non-null in the interpreter (it carries the call-site NodeId)
        assertTrue(cv.at != null) { "expected non-null at in interpreter path" }
        // The variant is NOT catchable by Attempt
        assertFalse(cv.isCatchable) { "BuiltinContractViolation must not be catchable" }
    }

    @Test
    fun `Int_Mod by zero raises BuiltinContractViolation with correct target and detail`() {
        val ex = loadAndEval(intModByZeroJson)
        val err = ex.error
        assertTrue(err is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation, got $err"
        }
        val cv = err as InterpretError.BuiltinContractViolation
        assertEquals("strand-builtin:Int.Mod", cv.target)
        assertTrue(cv.detail.contains("division by zero", ignoreCase = true)) {
            "expected detail to mention 'division by zero', got: ${cv.detail}"
        }
        assertFalse(cv.isCatchable)
    }

    @Test
    fun `Math_Mod by zero raises BuiltinContractViolation with correct target and detail`() {
        val ex = loadAndEval(mathModByZeroJson)
        val err = ex.error
        assertTrue(err is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation, got $err"
        }
        val cv = err as InterpretError.BuiltinContractViolation
        assertEquals("strand-builtin:Math.Mod", cv.target)
        assertTrue(cv.detail.contains("division by zero", ignoreCase = true)) {
            "expected detail to mention 'division by zero', got: ${cv.detail}"
        }
        assertFalse(cv.isCatchable)
    }

    // ------------------------------------------------------------------
    // Scenario: Int.Div by zero INSIDE an Attempt propagates UNCAUGHT
    // The Attempt must NOT produce an Err — the exception escapes.
    // ------------------------------------------------------------------

    @Test
    fun `Int_Div by zero inside Attempt propagates uncaught — Attempt must not produce Err`() {
        val json = """{
          "version": 1, "root": "att",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "divT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "divFn": { "type": "ForeignNode", "target": "strand-builtin:Int.Div",
                       "foreignType": "divT", "effects": [] },
            "ten":   { "type": "IntLit", "value": 10 },
            "zero":  { "type": "IntLit", "value": 0 },
            "app":   { "type": "Application", "function": "divFn", "arguments": ["ten", "zero"] },
            "att":   { "type": "Attempt", "body": "app" }
          }
        }"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val ex = assertThrows<InterpretException> {
            Interpreter(finalized.store, finalized.hashToNodeId).eval(finalized.root)
        }
        assertTrue(ex.error is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation to escape TRY (uncatchable), got ${ex.error}"
        }
    }
}
