package org.strand.corpus

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.strand.bytecode.Lowerer
import org.strand.core.JsonIngest
import org.strand.core.Node
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.Interpreter
import org.strand.interpreter.SandboxPolicy
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier
import org.strand.vm.Vm
import java.nio.file.Files

/**
 * Q-017 step 1 corpus equivalence test. For every value-path corpus
 * program, the test asserts the bytecode VM produces the same result as
 * the tree-walking interpreter.
 *
 * The program set is DERIVED from a directory scan of the shared
 * top-level `../corpus/` directory (via [GoldenHashes.enumerateProgramFiles],
 * the same enumeration [CorpusGoldenHashTest] uses) MINUS the explicit
 * [EXCLUSIONS] set below. This closes the silent-coverage gap the old
 * hand-maintained `listOf(...)` left: a newly-added value-path corpus
 * program is now equivalence-tested automatically rather than being
 * omitted until someone remembers to append it.
 *
 * Every exclusion carries a documented reason. The three categories:
 *
 *  - **Not a value path.** State-machine, async-actor, and stream-bridge
 *    programs are covered by [VmMachineEquivalenceTest] /
 *    [VmAsyncMachineEquivalenceTest]; the lowerer raises
 *    [org.strand.bytecode.LoweringNotImplemented] for StateMachine and
 *    friends, so they cannot run here. Multi-store composition programs
 *    reference sibling stores this single-store harness does not wire up.
 *  - **Schema / rejection semantics.** Schema-invariant programs are
 *    covered by [VmSchemaEquivalenceTest]; the schema-violation-by-design
 *    programs (e.g. corpus 83) diverge on purpose because the VM erases
 *    schemas pre-bytecode while the interpreter enforces them at runtime.
 *    Programs whose canonical *root* fails to verify or evaluate (the
 *    type-declaration-only 08/09) are also excluded.
 *  - **Representation inequality.** Corpus 05 returns a bare closure; the
 *    interpreter's [org.strand.interpreter.Value.Closure] and the VM's
 *    `VmClosure` are behaviorally equivalent but not Kotlin-equal (one
 *    captures its environment by reference, the other by content array).
 *
 * Both runs grant ALL effect categories in the store (mirrors CLI
 * `--grant-all`). `CapabilitySet.ofCategories` produces wildcards that
 * cover any refinement, so this class is value parity only; the
 * refinement-bearing programs (33-35, 39, 70, 74) run under the
 * interpreter corpus tests' exact refined grants, with denial parity, in
 * [VmRefinedCapabilityEquivalenceTest] (review H2).
 *
 * The state-machine / async / schema equivalence suites are left as-is;
 * they are covered by their own directory-aware or hand-maintained
 * fixtures.
 */
class VmEquivalenceTest {

    companion object {
        // Corpus 16/17 (and the 12-14/33-40 families) call Time.Now, which
        // reads the injectable Builtins.clock. The interpreter run and the
        // VM run of the same program execute milliseconds apart, so under
        // the default SystemClock the two results can straddle a
        // millisecond boundary and the equality assertion flakes. Install
        // the fixed replay clock for the duration of this class, mirroring
        // CorpusTest.
        @JvmStatic
        @BeforeAll
        fun installFixedClock() {
            Builtins.clock = Builtins.FixedClock(Builtins.FIXED_REPLAY_TIMESTAMP)
            // Corpus 70 performs a real Fs.Write to the absolute literal path
            // '/safe' on both the interpreter and VM paths — admissible only
            // under the open sandbox this class ran under implicitly before
            // Q-075 flipped the library default to SECURE_DEFAULT. Opt in
            // explicitly (the Q-075 pattern), restoring the default after.
            Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
        }
        @JvmStatic
        @AfterAll
        fun restoreSystemClock() {
            Builtins.clock = Builtins.SystemClock
            Builtins.sandboxPolicy = Builtins.DEFAULT_SANDBOX_POLICY
        }

        /**
         * Corpus-relative program paths excluded from the value-path
         * equivalence scan, each with the reason it is not admissible
         * here. Keyed by the same corpus-relative path
         * [GoldenHashes.enumerateProgramFiles] yields (forward slashes).
         */
        private val EXCLUSIONS: Map<String, String> = mapOf(
            // --- Type-declaration-only roots: the interpreter declines to
            // evaluate a root that is a Type, so there is no value to compare.
            "08-product-type-decl.json" to
                "type-declaration only; root evaluates to a Type the interpreter declines to run",
            "09-sum-type-decl.json" to
                "type-declaration only; root evaluates to a Type the interpreter declines to run",

            // --- Representation inequality: bare-closure result.
            "05-s-combinator-typed.json" to
                "returns a bare closure; Value.Closure (env by reference) and VmClosure " +
                    "(captures by content array) are behaviorally equivalent but not Kotlin-equal",
            "92-utf8-sort-divergence.json" to
                "verify-only epoch-3 divergence pin (Q-074); the root is a bare Lambda over the " +
                    "non-ASCII ProductType, so it returns a bare closure like corpus 05",

            // --- State-machine / sync-trace programs: covered by
            // VmMachineEquivalenceTest; the lowerer has no StateMachine rule.
            "41-toggle-machine.json" to "state machine — covered by VmMachineEquivalenceTest",
            "42-counter-machine.json" to "state machine — covered by VmMachineEquivalenceTest",
            "43-counter-with-overflow-output.json" to "state machine — covered by VmMachineEquivalenceTest",
            "44-request-response-echo.json" to "state machine — covered by VmMachineEquivalenceTest",
            "45-bank-account-machine.json" to "state machine — covered by VmMachineEquivalenceTest",

            // --- Async actor / stream programs: covered by
            // VmAsyncMachineEquivalenceTest; not a synchronous value path.
            "46-async-single-machine-counter.json" to "async actor group — covered by VmAsyncMachineEquivalenceTest",
            "47-async-multi-input-merge.json" to "async actor group — covered by VmAsyncMachineEquivalenceTest",
            "48-async-supervisor-one-for-one.json" to "async actor group — covered by VmAsyncMachineEquivalenceTest",
            "49-async-tagged-output-list.json" to "async actor group — covered by VmAsyncMachineEquivalenceTest",
            "57-dropoldest-overflow.json" to "async overflow-policy state machine — not a value path",
            "67-llm-state-machine-with-tool.json" to
                "state machine (LLM tool loop) — root is a StateMachine the interpreter declines to apply " +
                    "(NotCallable gotKind=StateMachine); covered by the machine-equivalence suites",
            "81-llm-stream-drain.json" to "stream-drain state machine — not a value path",
            "84-bridged-stream.json" to "actor-runtime stream bridge — not a value path",

            // --- Foreign-transport programs: the value path issues a real
            // provider call that needs an injected mock HTTP/vector transport
            // this single-store harness does not install.
            "68-vector-pinecone-upsert-query.json" to
                "Pinecone vector-store call needs an injected mock vectorHttpTransport " +
                    "(raises IoFailure vector-bad-pinecone-config without one); not a self-contained value path",

            // --- Non-terminating-by-design fixture: an intentionally
            // base-caseless Fixpoint that overflows before any comparison
            // (a resource-limits / rejection fixture, not an equivalence case).
            "71-fixpoint-no-base-case.json" to
                "intentionally base-caseless Fixpoint — recurses to StackOverflowError before producing a value",

            // --- Schema-invariant programs: covered by VmSchemaEquivalenceTest,
            // or reject at verify (the *-fail siblings), or diverge by design.
            "50-positive-int-schema-pass.json" to "schema-invariant program — covered by VmSchemaEquivalenceTest",
            "51-positive-int-schema-fail.json" to "schema-invariant rejection program — verifier rejects the root",
            "52-non-empty-list-schema-pass.json" to "schema-invariant program — covered by VmSchemaEquivalenceTest",
            "53-non-empty-list-schema-fail.json" to "schema-invariant rejection program — verifier rejects the root",
            "55-json-object-unique-keys.json" to "schema-invariant program — covered by VmSchemaEquivalenceTest",
            "56-json-object-duplicate-keys-fail.json" to "schema-invariant rejection program — verifier rejects the root",
            "59-non-empty-text-pass.json" to "schema-invariant program — covered by VmSchemaEquivalenceTest",
            "60-non-empty-text-fail.json" to "schema-invariant rejection program — verifier rejects the root",
            "62-non-empty-markdown-pass.json" to "schema-invariant program — covered by VmSchemaEquivalenceTest",
            "63-non-empty-markdown-fail.json" to "schema-invariant rejection program — verifier rejects the root",
            "83-runtime-schema-dynamic-violation.json" to
                "schema-violation-by-design: the VM erases schemas pre-bytecode while the " +
                    "interpreter enforces the runtime obligation, so the two diverge on purpose",

            // --- Manifest / composition / verifier-fixture programs: no
            // single-store runnable value path here.
            "69-response-schema-spec.json" to "ResponseSchemaSpec verifier fixture — not a runnable value path",
            "79-module-manifest-with-effects.json" to "ModuleManifest — informational, no runtime evaluation",
            "80-manifest-effect-mismatch-rejected.json" to "ModuleManifest rejection program — verifier rejects the root",
            "76-multi-store-composition/app.json" to
                "multi-store composition — references a sibling store this single-store harness does not wire up",
            "76-multi-store-composition/lib.json" to "library store for corpus 76 — no standalone root value",
            "77-name-registry-resolution/registry.json" to
                "name-registry resolution fixture — cross-store, not a single-store value path",
            "prelude-manifest.json" to "implicit-prelude manifest — a name registry, not a runnable program",

            // --- Sandbox-rejection programs: the value path is a deliberate
            // SandboxViolation, not a comparable value.
            "73-fs-write-projection-drift.json" to
                "projection-drift rejection program — verifier rejects the root (ProjectionMismatch)",
            "74-fs-write-escape-rejected.json" to
                "workspace-escape rejection — raises SandboxViolation under a secure policy, not a value",
            "75-http-metadata-rejected.json" to
                "SSRF-rejection program — raises SandboxViolation under a secure policy, not a value",
        )
    }

    private val corpusDir by lazy { GoldenHashes.findCorpusDir() }

    /**
     * The derived value-path program set: every enumerated corpus program
     * not in [EXCLUSIONS]. `.events.json` companion files are not program
     * documents (no `root`/`nodes`) and are excluded by the enumeration.
     */
    private fun valuePathPrograms(): List<String> =
        GoldenHashes.enumerateProgramFiles(corpusDir)
            .filter { it !in EXCLUSIONS }

    @TestFactory
    fun vmEquivalentToInterpreter(): List<DynamicTest> = valuePathPrograms().map { relPath ->
        DynamicTest.dynamicTest(relPath) {
            val canonicalText = Files.readString(corpusDir.resolve(relPath))
            val ingest = JsonIngest.parse(canonicalText)
            val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
            val verifyResult = Verifier(finalized.store, finalized.hashToNodeId)
                .verify(finalized.root)
            assertTrue(verifyResult is VerifyResult.Ok) {
                "$relPath: verifier failed: $verifyResult"
            }

            // Grant all EffectCategory NodeIds (mirrors CLI --grant-all)
            // so effect-using programs can run end-to-end. Pure programs
            // are unaffected — they don't consult capabilities.
            val effectCategoryIds = finalized.store.entries()
                .asSequence()
                .filter { it.second is Node.EffectCategory }
                .map { it.first }
                .toSet()

            val interpValue = Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, capabilities = CapabilitySet.ofCategories(effectCategoryIds))
            val table = Lowerer(finalized.store, finalized.hashToNodeId).lower(finalized.root)
            val vmValue = Vm(table).run(initialCaps = effectCategoryIds.map { it.value }.toSet())

            assertEquals(interpValue, vmValue) {
                "$relPath: VM=$vmValue interpreter=$interpValue"
            }
        }
    }
}
