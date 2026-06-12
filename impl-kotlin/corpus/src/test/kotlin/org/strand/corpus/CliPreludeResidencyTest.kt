package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.authoring.PreludeModule
import org.strand.cli.main
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Q-063 scenarios 5 and 6 — the prelude as the federation machinery's first
 * permanent resident, exercised through the real `strand` entry point on
 * happy paths only (a failure path calls `exitProcess`, which would kill the
 * test JVM — same constraint as [CliFederationTest]).
 *
 * Scenario 5 (registry residency): the default name registry ships with
 * `prelude` bound to the module's manifest hash and every reserved name
 * resolvable, so `strand registry resolve fsWrite` answers from a clean
 * checkout — the `--registry` paths below intentionally point at files that
 * do not exist.
 *
 * Scenario 6 (federation fetch): a program in one store references a prelude
 * export by content hash, and `--peer-store` pointed at the bundled prelude
 * snapshot resolves it through the ordinary Q-043 admission path.
 */
class CliPreludeResidencyTest {

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

    // ── scenario 5: registry residency ───────────────────────────────────────

    @Test
    fun `registry resolve answers a prelude export with no registry file`(@TempDir dir: Path) {
        val absent = dir.resolve("no-such-registry.json")
        val out = captureStdout {
            main(arrayOf("registry", "resolve", "fsWrite", "--registry", absent.toString()))
        }
        assertEquals(
            PreludeModule.loaded.exportHashes.getValue("fsWrite").toString(),
            out.trim(),
        )
    }

    @Test
    fun `registry resolve answers the prelude manifest name with no registry file`(@TempDir dir: Path) {
        val absent = dir.resolve("no-such-registry.json")
        val out = captureStdout {
            main(arrayOf("registry", "resolve", PreludeModule.REGISTRY_NAME, "--registry", absent.toString()))
        }
        assertEquals(PreludeModule.loaded.manifestHash.toString(), out.trim())
    }

    @Test
    fun `registry list includes the prelude entries with no registry file`(@TempDir dir: Path) {
        val absent = dir.resolve("no-such-registry.json")
        val out = captureStdout {
            main(arrayOf("registry", "list", "--registry", absent.toString()))
        }
        val lines = out.trim().lines()
        assertTrue(lines.any { it.startsWith("prelude ") }) {
            "expected a 'prelude' entry in registry list; got:\n$out"
        }
        assertTrue(lines.any { it.startsWith("fsWrite ") }) {
            "expected an 'fsWrite' entry in registry list; got:\n$out"
        }
        // Every reserved name plus the manifest name itself.
        assertEquals(PreludeModule.loaded.nodeHashes.size + 1, lines.size) {
            "expected one line per reserved name plus 'prelude'; got:\n$out"
        }
    }

    @Test
    fun `user registry entries overlay the prelude defaults`(@TempDir dir: Path) {
        val reg = dir.resolve("registry.json")
        val userHash = PreludeModule.loaded.manifestHash.toString()
        captureStdout {
            main(arrayOf("registry", "put", "app/main", userHash, "--registry", reg.toString()))
        }
        // The user entry resolves...
        val userOut = captureStdout {
            main(arrayOf("registry", "resolve", "app/main", "--registry", reg.toString()))
        }
        assertEquals(userHash, userOut.trim())
        // ...and the prelude defaults still answer beneath it.
        val preludeOut = captureStdout {
            main(arrayOf("registry", "resolve", "fsWrite", "--registry", reg.toString()))
        }
        assertEquals(
            PreludeModule.loaded.exportHashes.getValue("fsWrite").toString(),
            preludeOut.trim(),
        )
    }

    // ── scenario 6: federation fetch from the bundled snapshot ───────────────

    @Test
    fun `verify --peer-store resolves a prelude export from the bundled snapshot`(@TempDir dir: Path) {
        val peer = dir.resolve("prelude-module.json").toFile()
            .apply { writeText(PreludeModule.loaded.text) }
        val fsWriteHash = PreludeModule.loaded.exportHashes.getValue("fsWrite")
        val app = dir.resolve("app.json").toFile().apply {
            writeText(
                """{"version":1,"root":"r","nodes":{"r":{"type":"NodeRef","targetHash":"$fsWriteHash"}}}"""
            )
        }
        val out = captureStdout { main(arrayOf("verify", app.path, "--peer-store", peer.path)) }
        assertTrue(out.contains("type:")) {
            "expected the cross-store NodeRef to the prelude's fsWrite export to verify; got:\n$out"
        }
    }
}
