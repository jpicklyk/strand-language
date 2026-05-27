package org.strand.corpus

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.CapabilityArgument
import org.strand.interpreter.CapabilityPattern
import org.strand.interpreter.CapabilitySet
import org.strand.interpreter.EscapePolicy
import org.strand.interpreter.FsPolicy
import org.strand.interpreter.Interpreter
import org.strand.interpreter.InterpretError
import org.strand.interpreter.InterpretException
import org.strand.interpreter.NetPolicy
import org.strand.interpreter.SandboxPolicy
import org.strand.interpreter.SandboxViolationKind
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import org.strand.verifier.Verifier

/**
 * Q-041 corpus exercises:
 *  - corpus 74 (`fs-write-escape-rejected`) verifies clean, runs
 *    under the secure-default sandbox policy with workspace root,
 *    and the runtime raises [InterpretError.SandboxViolation] with
 *    kind [SandboxViolationKind.FsPathEscape] because the path
 *    `../escape.txt` escapes the workspace.
 *  - corpus 75 (`http-metadata-rejected`) verifies clean, runs
 *    under SandboxPolicy.SECURE_DEFAULT, and the runtime raises
 *    [InterpretError.SandboxViolation] with kind
 *    [SandboxViolationKind.NetHostBlocked] because the URL
 *    `http://169.254.169.254/...` resolves to the cloud-metadata
 *    blocklist.
 *
 * Both programs are pure negative-runtime exercises: the verifier
 * accepts them (sandboxing is runtime policy, not graph
 * well-formedness per § 5 of the proposal); the runtime denies.
 */
class CorpusSandboxTest {

    @BeforeEach
    fun setUp() {
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
    }

    @AfterEach
    fun tearDown() {
        Builtins.sandboxPolicy = SandboxPolicy.OPEN_DEFAULT
    }

    private fun load(resource: String): String {
        val stream = CorpusSandboxTest::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        return stream.bufferedReader().readText()
    }

    @Test
    fun `corpus 74 fs-write-escape-rejected raises FsPathEscape at runtime`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) {
        val text = load("/corpus/74-fs-write-escape-rejected.json")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok,
            "expected Ok verification for corpus 74, got $verify")

        // Install sandbox policy with workspace root = the temp dir;
        // `../escape.txt` resolves outside it under EscapePolicy.Deny.
        Builtins.sandboxPolicy = SandboxPolicy(
            fs = FsPolicy(workspaceRoot = tmp, escape = EscapePolicy.Deny),
            net = NetPolicy(defaultDeny = false),
        )

        // Grant the Filesystem.Write capability so the call passes
        // the capability + projection check and reaches the sandbox.
        val writeFx = ingest.nameMap.getValue("writeFx")
        val caps = CapabilitySet(mapOf(writeFx to listOf(
            CapabilityPattern(listOf(
                CapabilityArgument.Concrete(Value.StringV("../escape.txt")),
            ))
        )))
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, caps)
        }
        val error = ex.error as? InterpretError.SandboxViolation
            ?: error("expected InterpretError.SandboxViolation, got ${ex.error}")
        assertEquals(SandboxViolationKind.FsPathEscape, error.kind)
    }

    @Test
    fun `corpus 75 http-metadata-rejected raises NetHostBlocked at runtime`() {
        val text = load("/corpus/75-http-metadata-rejected.json")
        val ingest = JsonIngest.parse(text)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok,
            "expected Ok verification for corpus 75, got $verify")

        // Install the secure default — cloud-metadata IPs are
        // blocked regardless of any allowlist.
        Builtins.sandboxPolicy = SandboxPolicy.SECURE_DEFAULT

        // Grant all three Network.* capabilities so the call passes
        // the capability gate and reaches the sandbox layer.
        val connectFx = ingest.nameMap.getValue("connectFx")
        val sendFx = ingest.nameMap.getValue("sendFx")
        val recvFx = ingest.nameMap.getValue("recvFx")
        val wildcard = CapabilityPattern(listOf(CapabilityArgument.Wildcard))
        val caps = CapabilitySet(mapOf(
            connectFx to listOf(wildcard),
            sendFx to listOf(CapabilityPattern(emptyList())),
            recvFx to listOf(CapabilityPattern(emptyList())),
        ))
        val ex = org.junit.jupiter.api.assertThrows<InterpretException> {
            Interpreter(finalized.store, finalized.hashToNodeId)
                .eval(finalized.root, caps)
        }
        val error = ex.error as? InterpretError.SandboxViolation
            ?: error("expected InterpretError.SandboxViolation, got ${ex.error}")
        assertEquals(SandboxViolationKind.NetHostBlocked, error.kind)
    }
}
