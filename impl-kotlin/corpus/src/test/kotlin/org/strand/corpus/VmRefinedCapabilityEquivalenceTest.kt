package org.strand.corpus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Hash
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm
import java.nio.file.Path

/**
 * Review H2: the VM used to check capabilities by category only, and the
 * corpus equivalence tests granted every category as a wildcard, so the
 * "interpreter == VM" claim said nothing about refinements. These cases run
 * the refined corpus programs (33-35), the handler-performs-effect program
 * (39), the Q-039 projection program (70) and the sandbox program (74) under
 * the SAME refined grants the interpreter corpus tests use, and assert value
 * parity on success and full error parity (variant, category, report, site)
 * on denial.
 */
class VmRefinedCapabilityEquivalenceTest {

    @AfterEach
    fun resetSandbox() {
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
    }

    private class Program(
        val store: NodeStore,
        val root: NodeId,
        val hashToNodeId: Map<Hash, NodeId>,
        val names: Map<String, NodeId>,
    )

    private fun load(baseName: String): Program {
        val text = VmRefinedCapabilityEquivalenceTest::class.java
            .getResourceAsStream("/corpus/$baseName.json")!!.bufferedReader().readText()
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "$baseName failed verify: $verify" }
        return Program(finalized.store, finalized.root, finalized.hashToNodeId, ingest.nameMap)
    }

    private fun interp(p: Program, caps: CapabilitySet): Value =
        Interpreter(p.store, p.hashToNodeId).eval(p.root, caps)

    private fun vm(p: Program, caps: CapabilitySet): Value =
        Vm(Lowerer(p.store, p.hashToNodeId).lower(p.root)).run(caps)

    private fun assertValueParity(p: Program, caps: CapabilitySet, expected: Value) {
        assertEquals(expected, interp(p, caps))
        assertEquals(expected, vm(p, caps))
    }

    private fun assertDenialParity(p: Program, caps: CapabilitySet): InterpretError {
        val i = assertThrows<InterpretException> { interp(p, caps) }.error
        val v = assertThrows<InterpretException> { vm(p, caps) }.error
        assertTrue(i is InterpretError.CapabilityViolation || i is InterpretError.RefinementViolation) {
            "expected a capability denial from the interpreter, got $i"
        }
        assertEquals(i, v) { "VM denial must equal the interpreter's" }
        return v
    }

    private fun grant(category: NodeId, vararg args: CapabilityArgument) =
        CapabilitySet(mapOf(category to listOf(CapabilityPattern(args.toList()))))

    private fun str(s: String) = CapabilityArgument.Concrete(Value.StringV(s))
    private fun int(n: Long) = CapabilityArgument.Concrete(Value.IntV(n))

    // ----- value parity under the refined grants CorpusTest uses -----

    @Test
    fun `33 refined network connect - value parity under the exact grant`() {
        val p = load("33-refined-network-connect")
        assertValueParity(p, grant(p.names.getValue("netConnFx"), str("api.example.com"), int(443)), Value.IntV(1))
    }

    @Test
    fun `34 refined wildcard port - value parity under the wildcard-port grant`() {
        val p = load("34-refined-wildcard-port")
        assertValueParity(
            p,
            grant(p.names.getValue("netConnFx"), str("api.example.com"), CapabilityArgument.Wildcard),
            Value.IntV(1),
        )
    }

    @Test
    fun `35 refined logger - value parity under the authorized path grant`() {
        val p = load("35-refined-logger-authorized-path")
        assertValueParity(p, grant(p.names.getValue("fsWriteFx"), str("/var/log/app.log")), Value.IntV(0))
    }

    // ----- denial parity -----

    @Test
    fun `33 under a wrong-port refinement - both engines raise the same RefinementViolation`() {
        // Pre-fix the VM saw only the category and returned IntV(1) here.
        val p = load("33-refined-network-connect")
        val err = assertDenialParity(p, grant(p.names.getValue("netConnFx"), str("api.example.com"), int(8443)))
        err as InterpretError.RefinementViolation
        assertEquals(p.names.getValue("netConnFx"), err.category)
        assertEquals("Network.Connect", err.report.category)
    }

    @Test
    fun `35 under a different path refinement - both engines deny the inner write identically`() {
        val p = load("35-refined-logger-authorized-path")
        val err = assertDenialParity(p, grant(p.names.getValue("fsWriteFx"), str("/etc/other.log")))
        assertTrue(err is InterpretError.RefinementViolation)
        assertEquals(listOf("/var/log/app.log"), (err as InterpretError.RefinementViolation).report.requested)
    }

    @Test
    fun `33 under an empty grant - both engines raise the same CapabilityViolation`() {
        val p = load("33-refined-network-connect")
        val err = assertDenialParity(p, CapabilitySet.EMPTY)
        assertEquals(setOf(p.names.getValue("netConnFx")), (err as InterpretError.CapabilityViolation).missing)
    }

    @Test
    fun `39 handler's own declared effect is checked when the handler is invoked`() {
        // Grant only the intercepted category: the handler itself performs
        // Filesystem.Write, which the surrounding context does not hold.
        val p = load("39-handler-itself-performs-effect")
        val err = assertDenialParity(p, CapabilitySet.ofCategories(setOf(p.names.getValue("timeFx"))))
        assertTrue(err is InterpretError.CapabilityViolation)
        assertEquals("Filesystem.Write", (err as InterpretError.CapabilityViolation).report.category)
    }

    @Test
    fun `70 Q-039 projection - both engines synthesize the instance from the argument and deny`() {
        val p = load("70-fs-write-projection-happy")
        val err = assertDenialParity(p, grant(p.names.getValue("writeFx"), str("/elsewhere")))
        err as InterpretError.RefinementViolation
        assertEquals(listOf(Value.StringV("/safe")), err.requirement)
    }

    @Test
    fun `74 sandbox escape - both engines raise the same FsPathEscape SandboxViolation`(@TempDir tmp: Path) {
        val p = load("74-fs-write-escape-rejected")
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
            net = NetPolicy(defaultDeny = false),
        )
        val caps = grant(p.names.getValue("writeFx"), str("../escape.txt"))
        val i = assertThrows<InterpretException> { interp(p, caps) }.error as InterpretError.SandboxViolation
        val v = assertThrows<InterpretException> { vm(p, caps) }.error as InterpretError.SandboxViolation
        assertEquals(i.kind, v.kind)
        assertEquals(i.detail, v.detail)
    }
}
