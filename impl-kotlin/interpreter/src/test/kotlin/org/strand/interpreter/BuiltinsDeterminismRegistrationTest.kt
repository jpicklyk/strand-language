package org.strand.interpreter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Q-065 registration constraint: an effect-free builtin must take an
 * explicit determinism position at registration — there is no default
 * for that case, so the omission is a construction-time failure. The
 * tests below exercise the constraint through a test-local registry
 * (the shipping registries are built through the same
 * [Builtins.buildRegistry] path, so a passing constraint here means
 * the shipping construction enforces the same shape).
 */
class BuiltinsDeterminismRegistrationTest {

    private val unitFn = Builtins.Fn { Value.UnitV }

    @AfterEach
    fun teardown() {
        Builtins.clearTestBuiltins()
    }

    @Test
    fun `effect-free registration without a determinism declaration fails construction`() {
        val ex = assertThrows<IllegalStateException> {
            Builtins.buildRegistry(
                mapOf("test:NoPosition" to Builtins.Registration(unitFn))
            )
        }
        assertTrue("test:NoPosition" in (ex.message ?: "")) {
            "construction failure must name the offending target, got: ${ex.message}"
        }
    }

    @Test
    fun `effect-declaring registration defaults to Stateful`() {
        val built = Builtins.buildRegistry(
            mapOf("test:Effectful" to Builtins.Registration(unitFn, effectful = true))
        )
        assertEquals(Builtins.Determinism.Stateful, built.getValue("test:Effectful").determinism)
    }

    @Test
    fun `effect-free registration with an explicit Deterministic declaration succeeds`() {
        val built = Builtins.buildRegistry(
            mapOf(
                "test:Pure" to Builtins.Registration(
                    unitFn, effectful = false, declared = Builtins.Determinism.Deterministic
                )
            )
        )
        assertEquals(Builtins.Determinism.Deterministic, built.getValue("test:Pure").determinism)
    }

    @Test
    fun `effect-free force-marked Nondeterministic is constructible — the loosened-lock shape`() {
        // The registration constraint requires explicitness, not a fixed
        // value: an author CAN register an effect-free builtin as
        // Nondeterministic. No shipping entry does (the consistency sweep
        // pins that), and the verifier's NondeterministicInReplayContext
        // warning is the second lock that catches the combination at
        // composition time.
        val built = Builtins.buildRegistry(
            mapOf(
                "test:NondetPure" to Builtins.Registration(
                    unitFn, effectful = false, declared = Builtins.Determinism.Nondeterministic
                )
            )
        )
        assertEquals(
            Builtins.Determinism.Nondeterministic,
            built.getValue("test:NondetPure").determinism,
        )
    }

    @Test
    fun `shipping entries carry the expected positions`() {
        assertEquals(
            Builtins.Determinism.Deterministic,
            Builtins.determinismOf("strand-builtin:Int.Add"),
        )
        assertEquals(
            Builtins.Determinism.Stateful,
            Builtins.determinismOf("strand-builtin:Time.Now"),
        )
        assertEquals(
            Builtins.Determinism.Nondeterministic,
            Builtins.determinismOf("strand-builtin:Random.Int"),
        )
        assertEquals(
            Builtins.Determinism.Deterministic,
            Builtins.determinismOf("strand-builtin:List.Map"),
        )
        assertEquals(
            Builtins.Determinism.Stateful,
            Builtins.determinismOf("strand-builtin:Anthropic.Messages.Create"),
        )
        assertNull(Builtins.determinismOf("strand-builtin:No.Such.Builtin"))
    }

    @Test
    fun `test overlay is visible through lookup and determinismOf and clears`() {
        val target = "strand-test:OverlayProbe"
        assertNull(Builtins.lookup(target))
        assertNull(Builtins.determinismOf(target))

        Builtins.installTestBuiltin(
            target,
            effectful = false,
            determinism = Builtins.Determinism.Nondeterministic,
            fn = unitFn,
        )
        assertEquals(Value.UnitV, Builtins.lookup(target)!!.invoke(emptyList()))
        assertEquals(Builtins.Determinism.Nondeterministic, Builtins.determinismOf(target))
        assertTrue(target !in Builtins.registeredTargets()) {
            "the overlay is transient test state and must not leak into registeredTargets()"
        }

        Builtins.clearTestBuiltins()
        assertNull(Builtins.lookup(target))
        assertNull(Builtins.determinismOf(target))
    }
}
