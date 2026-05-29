package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.cli.main
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Q-043 step 3a — the CLI federation surface, exercised through the real
 * `strand` entry point (`org.strand.cli.main`) on **happy paths only**. A
 * failure path calls `exitProcess`, which would terminate the test JVM, so this
 * test never drives an erroring invocation; the federation *logic* (including
 * the rejection cases) is covered by [CorpusFederationTest].
 *
 * corpus resources are written to a temp directory because the CLI reads its
 * inputs as filesystem paths via `File(path)`, not from the classpath.
 */
class CliFederationTest {

    private val incHashHex = "1e16f603b7c76b000f78d534a466d8099a7fa299877a755fdb80cd2eafc98adc8b"

    private fun resource(path: String): String =
        CliFederationTest::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.readText() ?: error("missing resource $path")

    /** Run [block] (a `main(...)` invocation) capturing everything it writes to stdout. */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.out.flush()
            System.setOut(original)
        }
        return buffer.toString("UTF-8")
    }

    private fun writeCorpus76(dir: Path): Pair<String, String> {
        val lib = dir.resolve("lib.json").toFile()
            .apply { writeText(resource("/corpus/76-multi-store-composition/lib.json")) }
        val app = dir.resolve("app.json").toFile()
            .apply { writeText(resource("/corpus/76-multi-store-composition/app.json")) }
        return app.path to lib.path
    }

    @Test
    fun `run --peer-store evaluates a cross-store program end-to-end`(@TempDir dir: Path) {
        val (app, lib) = writeCorpus76(dir)
        val out = captureStdout { main(arrayOf("run", app, "--peer-store", lib)) }
        assertTrue(out.contains("value: IntV(v=42)")) {
            "expected inc(inc(40)) = 42 across the store boundary; got:\n$out"
        }
    }

    @Test
    fun `verify --peer-store accepts a cross-store program`(@TempDir dir: Path) {
        val (app, lib) = writeCorpus76(dir)
        val out = captureStdout { main(arrayOf("verify", app, "--peer-store", lib)) }
        assertTrue(out.contains("type: Int")) { "expected the app to verify to Int; got:\n$out" }
    }

    @Test
    fun `registry resolve prints corpus 77's bound hash`(@TempDir dir: Path) {
        val reg = dir.resolve("registry.json").toFile()
            .apply { writeText(resource("/corpus/77-name-registry-resolution/registry.json")) }
        val out = captureStdout { main(arrayOf("registry", "resolve", "stdlib/inc", "--registry", reg.path)) }
        assertEquals(incHashHex, out.trim())
    }

    @Test
    fun `registry put then resolve round-trips`(@TempDir dir: Path) {
        val reg = dir.resolve("registry.json").toFile()
        captureStdout { main(arrayOf("registry", "put", "lib/inc", incHashHex, "--registry", reg.path)) }
        val out = captureStdout { main(arrayOf("registry", "resolve", "lib/inc", "--registry", reg.path)) }
        assertEquals(incHashHex, out.trim())
    }
}
