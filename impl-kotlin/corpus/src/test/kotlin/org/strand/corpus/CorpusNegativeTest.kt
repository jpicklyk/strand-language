package org.strand.corpus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.core.IngestError
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.InterpretException
import org.strand.interpreter.Interpreter
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

/**
 * Drives the Q-066 negative corpus (`corpus/negative/`): curated near-miss
 * documents, each paired with an `*.expected.json` recording the error
 * family the pipeline must produce and the stage at which it must produce
 * it. See `corpus/negative/README.md` for the pairing convention.
 *
 * Stages:
 *  - `ingest`   — [JsonIngest.parse] must throw the expected [IngestError]
 *                 subclass.
 *  - `verify`   — ingest and finalize succeed; [Verifier.verify] must return
 *                 [VerifyResult.Failed] containing the expected
 *                 `VerifyError` family.
 *  - `evaluate` — ingest, finalize, and verify all succeed; evaluation under
 *                 an EMPTY capability context and default limits must throw
 *                 [InterpretException] carrying the expected `InterpretError`
 *                 family.
 *
 * A rejection at an *earlier* stage than expected is a failure: the entry
 * documents exactly where the boundary fires.
 */
class CorpusNegativeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun installFixedClock() {
            Builtins.clock = Builtins.FixedClock(Builtins.FIXED_REPLAY_TIMESTAMP)
        }

        @JvmStatic
        @AfterAll
        fun restoreSystemClock() {
            Builtins.clock = Builtins.SystemClock
        }
    }

    private data class Expected(
        val stage: String,
        val family: String,
        val exhaustionKind: String?,
    )

    private val negativeDir: Path = GoldenHashes.findCorpusDir().resolve("negative")

    private fun programFiles(): List<Path> =
        Files.list(negativeDir).use { stream ->
            stream.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".json") &&
                    !it.fileName.toString().endsWith(".expected.json")
            }.toList()
        }.sortedBy { it.fileName.toString() }

    private fun readExpected(programFile: Path): Expected {
        val expectedFile = programFile.resolveSibling(
            programFile.fileName.toString().removeSuffix(".json") + ".expected.json"
        )
        check(Files.isRegularFile(expectedFile)) {
            "negative entry ${programFile.fileName} has no paired ${expectedFile.fileName}"
        }
        val obj = Json.parseToJsonElement(Files.readString(expectedFile)).jsonObject
        return Expected(
            stage = obj.getValue("stage").jsonPrimitive.content,
            family = obj.getValue("family").jsonPrimitive.content,
            exhaustionKind = obj["exhaustionKind"]?.jsonPrimitive?.content,
        )
    }

    @TestFactory
    fun negativeCorpus(): List<DynamicTest> {
        val programs = programFiles()
        val entryTests = programs.map { file ->
            DynamicTest.dynamicTest(file.fileName.toString()) {
                runEntry(file.fileName.toString(), Files.readString(file), readExpected(file))
            }
        }
        val pairingTest = DynamicTest.dynamicTest("every expected.json has its program") {
            val programNames = programs.map { it.fileName.toString() }.toSet()
            val orphans = Files.list(negativeDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".expected.json") }.toList()
            }.map { it.fileName.toString().removeSuffix(".expected.json") + ".json" }
                .filter { it !in programNames }
            assertTrue(orphans.isEmpty()) { "expected.json files without a program: $orphans" }
            assertTrue(programNames.isNotEmpty()) { "negative corpus is empty" }
        }
        return entryTests + pairingTest
    }

    private fun runEntry(name: String, text: String, expected: Expected) {
        when (expected.stage) {
            "ingest" -> {
                val err = try {
                    JsonIngest.parse(text)
                    fail<Nothing>("$name: expected ingest rejection (${expected.family}), but ingest succeeded")
                } catch (e: IngestError) {
                    e
                }
                assertEquals(expected.family, err::class.simpleName) {
                    "$name: wrong IngestError family — got $err"
                }
                if (expected.exhaustionKind != null) {
                    err as IngestError.ResourceExhaustion
                    assertEquals(expected.exhaustionKind, err.kind.name) { "$name: wrong exhaustion kind" }
                }
            }
            "verify" -> {
                val ingest = JsonIngest.parse(text)
                val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
                val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
                assertTrue(verify is VerifyResult.Failed) {
                    "$name: expected verify rejection (${expected.family}), got $verify"
                }
                verify as VerifyResult.Failed
                assertTrue(verify.errors.any { it::class.simpleName == expected.family }) {
                    "$name: no ${expected.family} among verify errors: ${verify.errors}"
                }
            }
            "evaluate" -> {
                val ingest = JsonIngest.parse(text)
                val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
                val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
                assertTrue(verify is VerifyResult.Ok) {
                    "$name: evaluation-stage entry must verify cleanly, got $verify"
                }
                val ex = try {
                    val v = Interpreter(finalized.store, finalized.hashToNodeId)
                        .eval(finalized.root, CapabilitySet.EMPTY)
                    fail<Nothing>("$name: expected evaluation rejection (${expected.family}), evaluated to $v")
                } catch (e: InterpretException) {
                    e
                }
                assertEquals(expected.family, ex.error::class.simpleName) {
                    "$name: wrong InterpretError family — got ${ex.error}"
                }
            }
            else -> fail("$name: unknown stage '${expected.stage}' (expected ingest, verify, or evaluate)")
        }
    }
}
