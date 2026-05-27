package org.strand.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.strand.bytecode.Lowerer
import org.strand.core.EvaluationLimits
import org.strand.core.ExhaustionKind
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-040 cross-backend equivalence — the bytecode VM must honor the same
 * [EvaluationLimits] as the tree-walking interpreter, and a program that
 * exhausts the AllocatedValues cap under one backend must exhaust it
 * under the other (raising the same [InterpretError.ResourceExhaustion]
 * shape). The proposal § 7 scenario 11 captures this.
 *
 * The VM's `atNode` is null because slice 1 opcodes do not carry source
 * NodeIds (a Q-017 step 2 follow-up); the interpreter's `atNode` is
 * non-null. The test compares only `kind`, matching the proposal's
 * cross-backend contract.
 */
class VmLimitsTest {

    @Test
    fun `scenario 11 - allocation explosion raises ResourceExhaustion in both backends`() {
        // Build an int-allocating Application chain that allocates many
        // IntV literals from a Let-bound IntLit. Each Application
        // re-evaluates the body, allocating a fresh IntV; with a tight
        // maxAllocatedValues cap both backends should reject. We use
        // 250 chained Applications and cap at 100 allocations.
        val depth = 250
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"app$depth","nodes":{""")
        sb.append("""${'"'}T_a${'"'}:{"type":"TypeParameter","name":"a"},""")
        sb.append("""${'"'}intT${'"'}:{"type":"PrimitiveType","kind":"Int"},""")
        sb.append("""${'"'}p${'"'}:{"type":"ParameterDecl","name":"p","paramType":"T_a"},""")
        sb.append("""${'"'}pRef${'"'}:{"type":"VarRef","binder":"p"},""")
        sb.append("""${'"'}idInner${'"'}:{"type":"Lambda","parameters":["p"],"body":"pRef"},""")
        sb.append("""${'"'}id${'"'}:{"type":"TypeAbstraction","typeParameters":["T_a"],"body":"idInner"},""")
        sb.append("""${'"'}leaf${'"'}:{"type":"IntLit","value":0}""")
        for (i in 1..depth) {
            val prev = if (i == 1) "leaf" else "app${i - 1}"
            sb.append(""",${'"'}app$i${'"'}:{"type":"Application","function":"id","arguments":["$prev"],"typeArguments":["intT"]}""")
        }
        sb.append("}}")
        val json = sb.toString()

        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verifyResult is VerifyResult.Ok) { "fixture failed verify: $verifyResult" }

        val limits = EvaluationLimits.DEFAULTS.copy(maxAllocatedValues = 100L)

        // Interpreter side — expect ResourceExhaustion(AllocatedValues).
        val interpErr = assertThrows<InterpretException> {
            Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, CapabilitySet.EMPTY, limits)
        }
        val interpRe = interpErr.error
        assertTrue(interpRe is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $interpRe" }
        interpRe as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.AllocatedValues, interpRe.kind)

        // VM side — expect the same kind. atNode is null per slice 1's
        // missing source-mapping; we don't compare `at`.
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        val vmErr = assertThrows<InterpretException> {
            Vm(table).run(initialCaps = emptySet(), limits = limits)
        }
        val vmRe = vmErr.error
        assertTrue(vmRe is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $vmRe" }
        vmRe as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.AllocatedValues, vmRe.kind)
        // The VM does not carry source NodeIds at slice 1 — its at must
        // be null. The interpreter carries the offending node.
        assertEquals(null, vmRe.at)
    }

    @Test
    fun `VM step cap fires on a deep Application chain`() {
        // Build a moderately deep Application chain. Tight step budget
        // ensures the Steps cap fires before any other limit.
        val depth = 100
        val sb = StringBuilder()
        sb.append("""{"version":1,"root":"app$depth","nodes":{""")
        sb.append("""${'"'}T_a${'"'}:{"type":"TypeParameter","name":"a"},""")
        sb.append("""${'"'}intT${'"'}:{"type":"PrimitiveType","kind":"Int"},""")
        sb.append("""${'"'}p${'"'}:{"type":"ParameterDecl","name":"p","paramType":"T_a"},""")
        sb.append("""${'"'}pRef${'"'}:{"type":"VarRef","binder":"p"},""")
        sb.append("""${'"'}idInner${'"'}:{"type":"Lambda","parameters":["p"],"body":"pRef"},""")
        sb.append("""${'"'}id${'"'}:{"type":"TypeAbstraction","typeParameters":["T_a"],"body":"idInner"},""")
        sb.append("""${'"'}leaf${'"'}:{"type":"IntLit","value":0}""")
        for (i in 1..depth) {
            val prev = if (i == 1) "leaf" else "app${i - 1}"
            sb.append(""",${'"'}app$i${'"'}:{"type":"Application","function":"id","arguments":["$prev"],"typeArguments":["intT"]}""")
        }
        sb.append("}}")
        val json = sb.toString()

        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verifyResult = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        check(verifyResult is VerifyResult.Ok) { "fixture failed verify: $verifyResult" }

        val limits = EvaluationLimits.DEFAULTS.copy(maxSteps = 50L)
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        val err = assertThrows<InterpretException> {
            Vm(table).run(initialCaps = emptySet(), limits = limits)
        }
        val re = err.error
        assertTrue(re is InterpretError.ResourceExhaustion) { "expected ResourceExhaustion, got $re" }
        re as InterpretError.ResourceExhaustion
        assertEquals(ExhaustionKind.Steps, re.kind)
    }

    @Test
    fun `VM defaults pass-through - simple program runs unchanged`() {
        val json = """{"version":1, "root":"n", "nodes":{"n":{"type":"IntLit","value":42}}}"""
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
        // Both default and explicit-default paths produce the same value.
        val v1 = Vm(table).run()
        val v2 = Vm(table).run(initialCaps = emptySet(), limits = EvaluationLimits.DEFAULTS)
        assertEquals(v1, v2)
    }
}
