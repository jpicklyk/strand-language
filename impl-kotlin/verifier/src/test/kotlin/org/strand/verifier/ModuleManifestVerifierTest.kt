package org.strand.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.core.ManifestExport
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.hashing.Hasher

/**
 * N-046 ModuleManifest verifier admission rules (Q-043 § 5.4, § 5.5).
 *
 * The verifier certifies, for every manifest in the store, that each export's
 * `declaredEffects` set *exactly* equals the export's effect surface — the
 * function's declared effect row for a function export (the common case), or
 * the construction closure for a value export. Under-declaration is rejected
 * for safety, over-declaration for precision; both surface as
 * [VerifyError.ManifestExportEffectMismatch]. An export whose target hash is
 * not resolvable surfaces as [VerifyError.ManifestExportTargetUnresolvable].
 *
 * The corpus accept/reject end-to-end path lives in CorpusManifestTest; this
 * suite pins the over-declaration direction (no corpus program), proves the
 * store-wide scan certifies manifests that are not reachable from the root,
 * and exercises the unresolvable-target path that is otherwise reachable only
 * through the (deferred) federation resolver.
 */
class ModuleManifestVerifierTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    // A pure identity export and an effectful Fs.Write-wrapping export, each
    // declaring effects that exactly match its surface. Reused (with tweaks)
    // across the accept / under-declare / over-declare cases.
    private fun manifestProgram(
        idDeclaredEffects: String,
        writerDeclaredEffects: String,
    ): String = """{
      "version": 1, "root": "lib",
      "nodes": {
        "intT":   { "type": "PrimitiveType", "kind": "Int" },
        "strT":   { "type": "PrimitiveType", "kind": "String" },
        "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
        "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
        "writeT":  { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
        "writeFn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                     "foreignType": "writeT", "effects": ["writeFx"] },
        "idParam": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
        "idBody":  { "type": "VarRef", "binder": "idParam" },
        "idFn":    { "type": "Lambda", "parameters": ["idParam"], "body": "idBody" },
        "wPath":    { "type": "ParameterDecl", "name": "path", "paramType": "strT" },
        "wData":    { "type": "ParameterDecl", "name": "data", "paramType": "bytesT" },
        "wVarPath": { "type": "VarRef", "binder": "wPath" },
        "wVarData": { "type": "VarRef", "binder": "wData" },
        "wBody":    { "type": "Application", "function": "writeFn", "arguments": ["wVarPath", "wVarData"] },
        "writer":   { "type": "Lambda", "parameters": ["wPath", "wData"], "body": "wBody", "effects": ["writeFx"] },
        "lib": { "type": "ModuleManifest", "exports": [
          { "target": "idFn",   "declaredEffects": $idDeclaredEffects,     "displayName": "Int.identity" },
          { "target": "writer", "declaredEffects": $writerDeclaredEffects, "displayName": "Fs.writeFile" }
        ] }
      }
    }"""

    @Test
    fun `manifest with matching pure and effectful exports is accepted`() {
        val r = verify(manifestProgram(idDeclaredEffects = "[]", writerDeclaredEffects = "[\"writeFx\"]"))
        assertTrue(r is VerifyResult.Ok, "expected Ok, got $r")
    }

    @Test
    fun `manifest under-declaring an effectful export is rejected`() {
        // writer's surface is {writeFx}; declaring [] under-declares.
        val r = verify(manifestProgram(idDeclaredEffects = "[]", writerDeclaredEffects = "[]"))
        val failed = r as? VerifyResult.Failed ?: error("expected Failed, got $r")
        val mismatch = failed.errors.filterIsInstance<VerifyError.ManifestExportEffectMismatch>().single()
        assertEquals(1, mismatch.exportIndex, "writer is the second export (index 1)")
        assertTrue(mismatch.declared.isEmpty())
        assertEquals(1, mismatch.actual.size)
    }

    @Test
    fun `manifest over-declaring a pure export is rejected`() {
        // idFn's surface is {}; declaring [writeFx] over-declares.
        val r = verify(manifestProgram(idDeclaredEffects = "[\"writeFx\"]", writerDeclaredEffects = "[\"writeFx\"]"))
        val failed = r as? VerifyResult.Failed ?: error("expected Failed, got $r")
        val mismatch = failed.errors.filterIsInstance<VerifyError.ManifestExportEffectMismatch>().single()
        assertEquals(0, mismatch.exportIndex, "idFn is the first export (index 0)")
        assertEquals(1, mismatch.declared.size, "declared a single (spurious) effect")
        assertTrue(mismatch.actual.isEmpty(), "the pure identity Lambda's surface is empty")
    }

    @Test
    fun `a manifest not reachable from the root is still certified`() {
        // The root is the effectful Lambda itself; the manifest is a separate
        // node that nothing references (unreachable as an expression) but is
        // present in the store. The store-wide scan must still certify it — so
        // an under-declaration surfaces as a verifier failure rather than being
        // silently ignored. This pins the store-scan admission design (vs a
        // root-only walk).
        val r = verify("""{
          "version": 1, "root": "writer",
          "nodes": {
            "strT":   { "type": "PrimitiveType", "kind": "String" },
            "bytesT": { "type": "PrimitiveType", "kind": "Bytes" },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "writeFx": { "type": "EffectCategory", "categoryName": "Filesystem.Write", "parameters": ["strT"] },
            "writeT":  { "type": "FunctionType", "parameters": ["strT", "bytesT"], "result": "intT" },
            "writeFn": { "type": "ForeignNode", "target": "strand-builtin:Fs.Write",
                         "foreignType": "writeT", "effects": ["writeFx"] },
            "wPath":    { "type": "ParameterDecl", "name": "path", "paramType": "strT" },
            "wData":    { "type": "ParameterDecl", "name": "data", "paramType": "bytesT" },
            "wVarPath": { "type": "VarRef", "binder": "wPath" },
            "wVarData": { "type": "VarRef", "binder": "wData" },
            "wBody":    { "type": "Application", "function": "writeFn", "arguments": ["wVarPath", "wVarData"] },
            "writer":   { "type": "Lambda", "parameters": ["wPath", "wData"], "body": "wBody", "effects": ["writeFx"] },
            "lib": { "type": "ModuleManifest", "exports": [
              { "target": "writer", "declaredEffects": [], "displayName": "Fs.writeFile" }
            ] }
          }
        }""")
        val failed = r as? VerifyResult.Failed ?: error("expected Failed (unreachable manifest still checked), got $r")
        assertTrue(failed.errors.any { it is VerifyError.ManifestExportEffectMismatch },
            "expected ManifestExportEffectMismatch for the unreachable manifest, got ${failed.errors}")
    }

    @Test
    fun `export target absent from the local store is unresolvable`() {
        // Built programmatically: a manifest whose export target hash is not in
        // hashToNodeId. In a single-store document every export target resolves
        // (it is a node in the same document), so this path is otherwise only
        // reachable through the deferred federation resolver. We fabricate the
        // dangling reference directly.
        val store = NodeStore()
        val danglingHash = Hash(ByteArray(33) { i -> if (i == 0) 0x1e.toByte() else 0x7f.toByte() })
        val manifestId = store.add(
            Node.ModuleManifest(
                exports = listOf(
                    ManifestExport(
                        target = danglingHash,
                        declaredEffects = emptyList(),
                        displayName = "missing",
                    )
                )
            )
        )
        val r = Verifier(store, hashToNodeId = emptyMap()).verify(manifestId)
        val failed = r as? VerifyResult.Failed ?: error("expected Failed, got $r")
        val unresolvable = failed.errors.filterIsInstance<VerifyError.ManifestExportTargetUnresolvable>().single()
        assertEquals(0, unresolvable.exportIndex)
        assertEquals(danglingHash, unresolvable.target)
    }
}
