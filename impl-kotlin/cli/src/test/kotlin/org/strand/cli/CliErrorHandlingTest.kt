package org.strand.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Review CLI finding: user mistakes must end in a message and an exit code, never a JVM stack
 * trace. Drives [runGuarded] (the wrapper `main` delegates to) so no test path reaches
 * `exitProcess`, following the [DenialLineTest] precedent.
 */
class CliErrorHandlingTest {

    private fun capture(block: () -> Int): Pair<Int, String> {
        val original = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf, true, "UTF-8"))
        try {
            val code = block()
            return code to buf.toString("UTF-8")
        } finally {
            System.setErr(original)
        }
    }

    private val missing = File("does-not-exist-${System.nanoTime()}.json").path

    @Test
    fun `missing input file exits 1 with a message and no stack trace`() {
        for (cmd in listOf("verify", "run", "author", "translate")) {
            val (code, err) = capture { runGuarded(arrayOf(cmd, missing)) }
            assertEquals(1, code) { "$cmd: $err" }
            assertTrue(err.contains("error:") && err.contains(missing)) { "$cmd: $err" }
            assertFalse(err.contains("\tat ")) { "$cmd printed a stack trace: $err" }
        }
    }

    @Test
    fun `malformed numeric flag exits 2 with a message and no stack trace`() {
        val (code, err) = capture { runGuarded(arrayOf("run", missing, "--max-steps", "abc")) }
        assertEquals(2, code)
        assertTrue(err.contains("--max-steps requires a Long")) { err }
        assertFalse(err.contains("\tat ")) { err }
    }

    @Test
    fun `flag missing its argument exits 2`() {
        val (code, err) = capture { runGuarded(arrayOf("run", missing, "--max-stack-depth")) }
        assertEquals(2, code)
        assertTrue(err.contains("--max-stack-depth requires an argument")) { err }
    }

    @Test
    fun `bad error-verbosity value exits 2`() {
        val (code, err) = capture { runGuarded(arrayOf("run", missing, "--error-verbosity=loud")) }
        assertEquals(2, code)
        assertTrue(err.contains("--error-verbosity expects")) { err }
    }

    @Test
    fun `unexpected throwable is one line unless error-verbosity is full`() {
        val boom = { _: Array<String> -> throw IllegalStateException("boom") }
        val (code, err) = capture { runGuarded(arrayOf("run", "x"), boom) }
        assertEquals(1, code)
        assertEquals(1, err.trim().lines().size) { err }
        assertTrue(err.contains("IllegalStateException: boom")) { err }

        val (fullCode, fullErr) = capture { runGuarded(arrayOf("run", "x", "--error-verbosity=full"), boom) }
        assertEquals(1, fullCode)
        assertTrue(fullErr.contains("\tat ")) { "expected a stack trace under full verbosity: $fullErr" }
    }

    @Test
    fun `normal completion returns 0`() {
        val (code, _) = capture { runGuarded(arrayOf("x")) { } }
        assertEquals(0, code)
    }
}
