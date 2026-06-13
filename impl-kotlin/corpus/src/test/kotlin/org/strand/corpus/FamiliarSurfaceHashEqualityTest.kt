package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.authoring.Authoring
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Q-061 Layer F scenario 1 — cross-surface hash equality, the
 * proposal's defining correctness property: for every step-1-coverable
 * evaluation task, the `reference.familiar` Layer F file lowers to the
 * IDENTICAL root content hash as the task's `reference.layer-a`
 * compile. No goldens are added — both sides compile live, so the
 * property is invariant under any future (epoch-batched) encoding
 * change.
 *
 * Tasks whose reference programs need step-2 constructs (machine /
 * schema / handler / capability blocks) are recorded explicitly as
 * pending — each pending entry asserts that no `reference.familiar`
 * exists yet, so adding one in step 2 forces the task into the
 * covered list. No silent skips.
 */
class FamiliarSurfaceHashEqualityTest {

    /** Step-1-coverable tasks: pure subset + effects + attempt. */
    private val covered = listOf(
        "01-factorial",
        "04-option-unwrap-default",
        "05-sum-list",
        "09-file-write-capability",
        "11-effect-coverage-gap",
        "15-recursive-self-flat-list",
        "16-audit-log-effects",
        "19-tree-sum-leaves",
        "22-list-append-sum",
    )

    /** Step-2-pending tasks, each with the construct that blocks it. */
    private val pendingStep2 = mapOf(
        "02-json-value" to "schema (N-032) — Layer F `schema` template is step 2",
        "03-toggle-machine" to "state machine (N-027) — Layer F `machine` template is step 2",
        "06-counter-machine" to "state machine (N-027) — Layer F `machine` template is step 2",
        "07-bounded-counter-schema" to "schema + invariant (N-032/N-033) — step 2",
        "08-nonempty-list-schema" to "schema + invariant (N-032/N-033) — step 2",
        "10-handler-intercept" to "handler (N-043) — Layer F `handler` form is step 2",
        "12-effect-decl-arity" to "handler (N-043) — Layer F `handler` form is step 2",
        "13-handler-return-type" to "handler (N-043) — Layer F `handler` form is step 2",
        "14-schema-invariant-rescue" to "schema + invariant (N-032/N-033) — step 2",
        "17-handler-config-read" to "handler (N-043) — Layer F `handler` form is step 2",
        "18-schema-username-truncate" to "schema + invariant (N-032/N-033) — step 2",
        "20-connect-effect-decl" to "handler (N-043) — Layer F `handler` form is step 2",
        "21-capability-scoped-write" to "capability block (N-036) — Layer F `capability` form is step 2",
    )

    private fun tasksDir(): Path? {
        var search = Paths.get("").toAbsolutePath()
        while (true) {
            val candidate = search.resolve("evaluation").resolve("dynamic").resolve("tasks")
            if (Files.isDirectory(candidate)) return candidate
            search = search.parent ?: return null
        }
    }

    private fun rootHashOf(dagJson: String): Pair<String, VerifyResult> {
        val ingest = JsonIngest.parse(dagJson)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        return finalized.nodeIdToHash.getValue(finalized.root).toString() to verify
    }

    @TestFactory
    fun crossSurfaceHashEquality(): List<DynamicTest> {
        val tasks = tasksDir()
        val coverageGate = DynamicTest.dynamicTest("coverage accounting: 9 covered + 13 pending = 22 tasks") {
            Assumptions.assumeTrue(tasks != null, "evaluation/dynamic/tasks not reachable")
            val all = Files.list(tasks!!).use { s -> s.filter { Files.isDirectory(it) }.count() }
            assertEquals(all, (covered.size + pendingStep2.size).toLong()) {
                "every evaluation task must be either covered or recorded step-2 pending"
            }
            assertTrue(covered.toSet().intersect(pendingStep2.keys).isEmpty())
        }

        val coveredTests = covered.map { task ->
            DynamicTest.dynamicTest("$task: Layer F root hash == Layer A root hash") {
                Assumptions.assumeTrue(tasks != null, "evaluation/dynamic/tasks not reachable")
                val dir = tasks!!.resolve(task)
                val familiarText = Files.readString(dir.resolve("reference.familiar"))
                val layerAText = Files.readString(dir.resolve("reference.layer-a"))

                val familiarJson = Authoring.compile(familiarText, Authoring.Surface.FAMILIAR).dagJson
                val layerAJson = Authoring.compile(layerAText).dagJson

                val (familiarHash, familiarVerify) = rootHashOf(familiarJson)
                val (layerAHash, _) = rootHashOf(layerAJson)

                assertEquals(layerAHash, familiarHash) {
                    "$task: the Layer F reference lowers to root hash $familiarHash but the " +
                        "Layer A reference produces $layerAHash — the surfaces must be " +
                        "indistinguishable at the canonical graph"
                }
                assertTrue(familiarVerify is VerifyResult.Ok) {
                    "$task: the Layer F compile failed verification: $familiarVerify"
                }
            }
        }

        val pendingTests = pendingStep2.map { (task, reason) ->
            DynamicTest.dynamicTest("$task: step-2 pending ($reason)") {
                Assumptions.assumeTrue(tasks != null, "evaluation/dynamic/tasks not reachable")
                val familiar = tasks!!.resolve(task).resolve("reference.familiar")
                assertTrue(!Files.exists(familiar)) {
                    "$task has a reference.familiar but is still recorded as step-2 pending — " +
                        "move it into the covered list"
                }
            }
        }

        return listOf(coverageGate) + coveredTests + pendingTests
    }
}
