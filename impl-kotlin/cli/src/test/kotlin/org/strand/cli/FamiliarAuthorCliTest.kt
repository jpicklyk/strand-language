package org.strand.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

/**
 * Q-061 Layer F CLI: `strand author --surface familiar` compiles the
 * dialect through the shared pipeline and reports the root type; a
 * `.familiar` extension auto-selects the surface. (Failure-path
 * annotation — Layer F lines on verifier errors — is covered at the
 * library level in `:corpus` FamiliarScenarioTest; the CLI failure
 * paths call exitProcess and are not invocable in-process.)
 */
class FamiliarAuthorCliTest {

    private val originalOut = System.out
    private val captured = ByteArrayOutputStream()

    @BeforeEach
    fun captureOut() {
        System.setOut(PrintStream(captured, true))
    }

    @AfterEach
    fun restoreOut() {
        System.setOut(originalOut)
    }

    private val program = """
        use prelude.{eqInt, sub, mul}

        function fact(n: Int): Int {
          return match (eqInt(n, 0)) {
            true => 1,
            false => mul(n, fact(sub(n, 1))),
          }
        }

        function main(): Int {
          return fact(5)
        }
    """.trimIndent()

    @Test
    fun `author --surface familiar compiles and reports the root type`() {
        val file = Files.createTempFile("strand-familiar-cli", ".txt")
        try {
            Files.writeString(file, program)
            main(arrayOf("author", file.toString(), "--surface", "familiar"))
            val out = captured.toString()
            assertTrue(out.contains("type:")) { "expected a root-type report; got: $out" }
            assertTrue(out.contains("Int")) { "expected the Int root type; got: $out" }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a familiar file extension auto-selects the surface`() {
        val file = Files.createTempFile("strand-familiar-cli", ".familiar")
        try {
            Files.writeString(file, program)
            main(arrayOf("author", file.toString(), "--emit-json"))
            val out = captured.toString()
            assertTrue(out.contains("\"Fixpoint\"")) {
                "expected the emitted dag-json (with the lifted Fixpoint); got: ${out.take(400)}"
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
