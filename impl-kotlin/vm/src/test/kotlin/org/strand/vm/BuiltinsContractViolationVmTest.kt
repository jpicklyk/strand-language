package org.strand.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter

/**
 * VM-side builtin contract violation tests (hygiene follow-up for N-047).
 *
 * Verifies:
 *  1. The VM's `invokeCallable` VmForeign branch catches `IllegalArgumentException`
 *     from builtins and rethrows as `InterpretException(BuiltinContractViolation)`.
 *  2. The `unwindToAttempt` filter (isCatchable == false) passes it through — the
 *     ATTEMPT_PUSH/POP machinery does NOT swallow the error.
 *  3. Interpreter == VM parity: same variant, same target, same detail substring
 *     (NodeId presence may differ — interpreter carries the call-site NodeId,
 *     VM has `at = null` because slice-1 opcodes carry no source ids).
 */
class BuiltinsContractViolationVmTest {

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

    private val intDivByZeroInAttemptJson = """{
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

    @Test
    fun `VM raises BuiltinContractViolation for Int_Div by zero`() {
        val ingest = JsonIngest.parse(intDivByZeroJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        val ex = assertThrows<InterpretException> {
            Vm(table).run()
        }
        val err = ex.error
        assertTrue(err is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation from VM, got $err"
        }
        val cv = err as InterpretError.BuiltinContractViolation
        assertEquals("strand-builtin:Int.Div", cv.target)
        assertTrue(cv.detail.contains("division by zero", ignoreCase = true)) {
            "expected detail to mention 'division by zero', got: ${cv.detail}"
        }
        // VM slice-1 carries no NodeIds — at is null
        assertTrue(cv.at == null) { "expected null at in VM path (no opcode source mapping yet)" }
        assertFalse(cv.isCatchable)
    }

    @Test
    fun `VM — Int_Div by zero inside Attempt propagates uncaught (unwind filter rejects isCatchable=false)`() {
        val ingest = JsonIngest.parse(intDivByZeroInAttemptJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        // The ATTEMPT_PUSH marker is active when the IAE fires. The unwind
        // filter checks isCatchable; BuiltinContractViolation returns false,
        // so unwindToAttempt returns false and the exception escapes.
        val ex = assertThrows<InterpretException> {
            Vm(table).run()
        }
        assertTrue(ex.error is InterpretError.BuiltinContractViolation) {
            "expected BuiltinContractViolation to escape VM Attempt, got ${ex.error}"
        }
    }

    @Test
    fun `Int_Div by zero — interpreter and VM agree on variant and target (parity)`() {
        // Both backends raise BuiltinContractViolation with the same target and
        // a detail containing "division by zero". The NodeId presence differs:
        // interpreter carries the call-site NodeId; VM has at = null.
        val ingest = JsonIngest.parse(intDivByZeroJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)

        val interpEx = assertThrows<InterpretException> {
            Interpreter(finalized.store, finalized.hashToNodeId).eval(finalized.root)
        }
        val vmEx = assertThrows<InterpretException> {
            val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
            Vm(table).run()
        }

        val interpErr = interpEx.error as InterpretError.BuiltinContractViolation
        val vmErr = vmEx.error as InterpretError.BuiltinContractViolation

        assertEquals(interpErr.target, vmErr.target,
            "interpreter and VM must agree on the builtin target")
        assertTrue(vmErr.detail.contains("division by zero", ignoreCase = true),
            "VM detail must mention 'division by zero': ${vmErr.detail}")
        assertTrue(interpErr.detail.contains("division by zero", ignoreCase = true),
            "interpreter detail must mention 'division by zero': ${interpErr.detail}")
        assertEquals(interpErr.detail, vmErr.detail,
            "interpreter and VM detail strings must be identical (same require message)")
        // at differs by design: interpreter is non-null, VM is null
        assertTrue(interpErr.at != null, "interpreter path must carry a call-site NodeId")
        assertTrue(vmErr.at == null, "VM path must have at = null (no source mapping in slice 1)")
    }
}
