package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.cli.main
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Q-058: the `strand store` / `--store` CLI surface, exercised through the real
 * entry point ([org.strand.cli.main]) on happy paths only (a failure path calls
 * `exitProcess`, which would terminate the test JVM — the fail-closed logic is
 * covered by the `:hashing` / `:runtime` tests). Proves run-by-hash equals
 * run-from-file at the CLI, that `verify --store` reuses the cached verdict,
 * and that a registry name dereferences against the local store.
 */
class CliStoreTest {

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

    /** corpus 02 (TypeAbstraction + Lambda + Application) evaluates to IntV(42). */
    private fun writeProgram(dir: Path): String {
        val text = CliStoreTest::class.java.getResourceAsStream("/corpus/02-identity-applied.json")
            ?.bufferedReader()?.readText() ?: error("missing corpus 02")
        val f = dir.resolve("prog.json").toFile().apply { writeText(text) }
        return f.path
    }

    @Test
    fun `store ingest then run-by-hash equals run-from-file`(@TempDir dir: Path) {
        val prog = writeProgram(dir)
        val storeDir = dir.resolve("store").toString()

        // run from file
        val fromFile = captureStdout { main(arrayOf("run", prog)) }
        assertTrue(fromFile.contains("value: IntV(v=42)")) { "run-from-file:\n$fromFile" }

        // ingest -> root hash on stdout
        val rootHash = captureStdout { main(arrayOf("store", "ingest", prog, "--store", storeDir)) }.trim()
        assertTrue(rootHash.matches(Regex("^1e[0-9a-f]{64}$"))) { "expected a multihash hex, got '$rootHash'" }

        // run by hash against the store
        val byHash = captureStdout { main(arrayOf("run", rootHash, "--store", storeDir)) }
        assertTrue(byHash.contains("value: IntV(v=42)")) { "run-by-hash:\n$byHash" }
    }

    @Test
    fun `verify --store reuses the cached verdict`(@TempDir dir: Path) {
        val prog = writeProgram(dir)
        val storeDir = dir.resolve("store").toString()
        val rootHash = captureStdout { main(arrayOf("store", "ingest", prog, "--store", storeDir)) }.trim()

        val out = captureStdout { main(arrayOf("verify", rootHash, "--store", storeDir)) }
        assertTrue(out.contains("type: Int")) { "verify-by-hash should print the cached type; got:\n$out" }
    }

    @Test
    fun `registry name dereferences against the local store and runs`(@TempDir dir: Path) {
        val prog = writeProgram(dir)
        val storeDir = dir.resolve("store").toString()
        val regFile = dir.resolve("registry.json").toString()

        val rootHash = captureStdout { main(arrayOf("store", "ingest", prog, "--store", storeDir)) }.trim()
        // Bind a name to the hash, then run that name against the store — no
        // --peer-store, no file path. Closes the "registry is half a mechanism"
        // gap: a resolved name is runnable.
        captureStdout { main(arrayOf("registry", "put", "app/identity", rootHash, "--registry", regFile)) }
        val out = captureStdout {
            main(arrayOf("run", "app/identity", "--store", storeDir, "--registry", regFile))
        }
        assertTrue(out.contains("value: IntV(v=42)")) { "registry-name run-by-hash:\n$out" }
    }

    @Test
    fun `file path still works when --store is set and the positional names a file`(@TempDir dir: Path) {
        val prog = writeProgram(dir)
        val storeDir = dir.resolve("store").toString()
        // The positional is an existing file, so it stays file-path mode even
        // with --store present (the existing invocation shape is preserved).
        val out = captureStdout { main(arrayOf("run", prog, "--store", storeDir)) }
        assertTrue(out.contains("value: IntV(v=42)")) { "file-path-with-store:\n$out" }
    }
}
