package org.strand.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.hashing.NodeResolverIntegrityViolation
import org.strand.hashing.PersistentStore
import org.strand.hashing.StoredVerdict
import org.strand.interpreter.HostPolicy
import org.strand.interpreter.Value
import org.strand.verifier.VerifyResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Q-058: admit-and-verify-once and run-by-hash on the [StrandRuntime] facade.
 *
 *  - A run-by-hash trajectory equals the same program run from its file.
 *  - The verdict cache reuses a recorded verdict (the decision path performs no
 *    re-verify; the recorded rootType/warnings/failure are reproduced).
 *  - An integrity violation on a tampered store entry fails closed on read.
 */
class StrandRuntimeStoreTest {

    private fun finalize(json: String) =
        JsonIngest.parse(json).let { Hasher(it.rawStore).finalize(it.root) }

    @Test
    fun `run-by-hash equals run-from-file`(@TempDir dir: Path) {
        val rt = StrandRuntime(HostPolicy.OPEN)

        // Run from file (the in-memory finalized program).
        val finalized = finalize(SUM_TO_TEN)
        val fileImage = ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId)
        val fromFile = rt.run(fileImage)
        val fileValue = assertInstanceOf(RunOutcome.Ok::class.java, fromFile).value
        assertEquals(Value.IntV(55), fileValue)

        // Ingest into the store, then run by root hash.
        val store = PersistentStore.open(dir)
        val verify = rt.verify(fileImage)
        rt.ingestToStore(store, finalized, verify, finalized.nodeIdToHash)
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

        val byHashImage = rt.loadImageFromStore(store, rootHash)
        assertTrue(byHashImage != null)
        val fromHash = rt.run(byHashImage!!)
        val hashValue = assertInstanceOf(RunOutcome.Ok::class.java, fromHash).value
        assertEquals(fileValue, hashValue, "run-by-hash produces the same value as run-from-file")
    }

    @Test
    fun `verdict reuse reproduces the recorded verdict without re-verifying`(@TempDir dir: Path) {
        val rt = StrandRuntime(HostPolicy.OPEN)
        val finalized = finalize(SUM_TO_TEN)
        val verify = rt.verify(finalized.let { ProgramImage(it.store, it.root, it.hashToNodeId) })
        val okVerify = assertInstanceOf(VerifyResult.Ok::class.java, verify)

        val store = PersistentStore.open(dir)
        rt.ingestToStore(store, finalized, verify, finalized.nodeIdToHash)
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

        // The decision path reads the recorded verdict — no verifier involvement.
        val cached = rt.cachedVerdict(store, rootHash)
        val cachedOk = assertInstanceOf(StoredVerdict.Ok::class.java, cached)
        assertEquals(okVerify.rootType.toString(), cachedOk.rootType)
        // The root's type appears in the per-node-hash map keyed by the root hash.
        assertEquals(okVerify.rootType.toString(), cachedOk.nodeTypesByHash[rootHash.toString()])
    }

    @Test
    fun `cached failed verdict is recorded and reused`(@TempDir dir: Path) {
        val rt = StrandRuntime(HostPolicy.OPEN)
        val finalized = finalize(TYPE_ERROR_PROGRAM)
        val verify = rt.verify(ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId))
        assertInstanceOf(VerifyResult.Failed::class.java, verify)

        val store = PersistentStore.open(dir)
        rt.ingestToStore(store, finalized, verify, finalized.nodeIdToHash)
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

        val cached = rt.cachedVerdict(store, rootHash)
        val failed = assertInstanceOf(StoredVerdict.Failed::class.java, cached)
        assertTrue(failed.errors.isNotEmpty(), "the cached verdict records the verify errors")
    }

    @Test
    fun `integrity violation on a tampered store entry fails closed on load`(@TempDir dir: Path) {
        val rt = StrandRuntime(HostPolicy.OPEN)
        val finalized = finalize(SUM_TO_TEN)
        val store = PersistentStore.open(dir)
        val verify = rt.verify(ProgramImage(finalized.store, finalized.root, finalized.hashToNodeId))
        rt.ingestToStore(store, finalized, verify, finalized.nodeIdToHash)
        val rootHash = finalized.nodeIdToHash.getValue(finalized.root)

        // loadImageFromStore reads the ROOT's record (the self-contained
        // subgraph; there are no NodeRef boundaries here, so every reachable
        // node is inlined in the root file). Tamper a hash-relevant literal in
        // that file: IntLit(10) -> IntLit(11). The Merkle root re-hash on
        // admission then rejects the unfaithful subgraph.
        val shard = "%02x".format(rootHash.bytes[0])
        val file = dir.resolve("nodes").resolve(shard).resolve("$rootHash.json")
        val text = Files.readString(file)
        val tampered = text.replace("\"value\":10", "\"value\":11")
        assertTrue(tampered != text, "the tamper changed the root record")
        Files.writeString(file, tampered)

        val reopened = StrandRuntime(HostPolicy.OPEN)
        val store2 = PersistentStore.open(dir)
        assertThrows(NodeResolverIntegrityViolation::class.java) {
            reopened.loadImageFromStore(store2, rootHash)
        }
    }

    @Test
    fun `loadImageFromStore returns null for an unheld root`(@TempDir dir: Path) {
        val rt = StrandRuntime(HostPolicy.OPEN)
        val store = PersistentStore.open(dir)
        val unknown = Hash(byteArrayOf(0x1e.toByte()) + ByteArray(32) { 0x00.toByte() })
        assertNull(rt.loadImageFromStore(store, unknown))
    }

    companion object {
        // Fixpoint sum 1..10 = 55 (lifted from the isolation test / corpus 22).
        val SUM_TO_TEN = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "intT":      { "type": "PrimitiveType", "kind": "Int" },
            "boolT":     { "type": "PrimitiveType", "kind": "Bool" },
            "sumT":      { "type": "FunctionType", "parameters": ["intT"], "result": "intT" },
            "addT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":       { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "subT":      { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "sub":       { "type": "ForeignNode", "target": "strand-builtin:Int.Sub", "foreignType": "subT" },
            "leT":       { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
            "le":        { "type": "ForeignNode", "target": "strand-builtin:Int.Le", "foreignType": "leT" },
            "recurse":   { "type": "ParameterDecl", "name": "recurse", "paramType": "sumT" },
            "n":         { "type": "ParameterDecl", "name": "n", "paramType": "intT" },
            "zero":      { "type": "IntLit", "value": 0 },
            "one":       { "type": "IntLit", "value": 1 },
            "nRef":      { "type": "VarRef", "binder": "n" },
            "nLeZero":   { "type": "Application", "function": "le", "arguments": ["nRef", "zero"] },
            "litTrue":   { "type": "BoolLit", "value": true },
            "patTrue":   { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litTrue" },
            "caseTrue":  { "type": "MatchCase", "pattern": "patTrue", "body": "zero" },
            "litFalse":  { "type": "BoolLit", "value": false },
            "patFalse":  { "type": "Pattern", "kind": "literal", "patternType": "boolT", "literal": "litFalse" },
            "recRef":    { "type": "VarRef", "binder": "recurse" },
            "nMinus1":   { "type": "Application", "function": "sub", "arguments": ["nRef", "one"] },
            "recCall":   { "type": "Application", "function": "recRef", "arguments": ["nMinus1"] },
            "addBody":   { "type": "Application", "function": "add", "arguments": ["nRef", "recCall"] },
            "caseFalse": { "type": "MatchCase", "pattern": "patFalse", "body": "addBody" },
            "matchBody": { "type": "Match", "scrutinee": "nLeZero", "cases": ["caseTrue", "caseFalse"] },
            "bodyLam":   { "type": "Lambda", "parameters": ["recurse", "n"], "body": "matchBody" },
            "sum":       { "type": "Fixpoint", "recursionType": "sumT", "body": "bodyLam" },
            "ten":       { "type": "IntLit", "value": 10 },
            "app":       { "type": "Application", "function": "sum", "arguments": ["ten"] }
          }
        }
        """.trimIndent()

        // A type error: apply Int.Add to a String — verifies to Failed.
        val TYPE_ERROR_PROGRAM = """
        {
          "version": 1,
          "root": "app",
          "nodes": {
            "intT":  { "type": "PrimitiveType", "kind": "Int" },
            "addT":  { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "intT" },
            "add":   { "type": "ForeignNode", "target": "strand-builtin:Int.Add", "foreignType": "addT" },
            "one":   { "type": "IntLit", "value": 1 },
            "s":     { "type": "StringLit", "value": "not an int" },
            "app":   { "type": "Application", "function": "add", "arguments": ["one", "s"] }
          }
        }
        """.trimIndent()
    }
}
