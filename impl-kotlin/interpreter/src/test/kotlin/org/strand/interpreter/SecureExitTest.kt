package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Review (Low): under [HostPolicy.SECURE], `System.Exit` called the real
 * exit handler and so terminated the embedding host. SECURE now installs
 * [Builtins.RefusingExitHandler], which turns the call into an uncatchable
 * [SandboxViolationKind.SystemExitRefused] that ends the evaluation, not
 * the JVM. (If this test regressed, the JVM would exit and the suite
 * would abort, which is itself a loud failure.)
 */
class SecureExitTest {

    @Test
    fun `SECURE installs the refusing exit handler`() {
        assertSame(Builtins.RefusingExitHandler, HostPolicy.SECURE.exitHandler)
        assertSame(Builtins.RealExitHandler, HostPolicy.OPEN.exitHandler)
    }

    @Test
    fun `System_Exit under SECURE is a sandbox violation, not a process exit`() {
        val ctx = HostContext.fromPolicy(HostPolicy.SECURE)
        val ex = assertThrows<SandboxViolation> {
            Builtins.lookup("strand-builtin:System.Exit")!!.invoke(ctx, listOf(Value.IntV(3)))
        }
        assertEquals(SandboxViolationKind.SystemExitRefused, ex.kind)
        assertTrue(ex.detail.contains("3"), ex.detail)
    }
}
