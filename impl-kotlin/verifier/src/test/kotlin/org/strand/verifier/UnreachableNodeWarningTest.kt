package org.strand.verifier

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Store-wide unreachable-node diagnostics (VerifyWarning.UnreachableNode).
 *
 * Warnings are informational: verification still succeeds. The
 * reachability roots are the program root, every ModuleManifest (plus its
 * export targets), and every StateMachine in the store; edges are every
 * NodeId-typed field plus NodeRef Hash targets resolved through
 * hashToNodeId.
 */
class UnreachableNodeWarningTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    @Test
    fun `an unwired node produces an UnreachableNode warning but verification succeeds`() {
        val r = verify("""{
          "version": 1, "root": "main",
          "nodes": {
            "main":     { "type": "IntLit", "value": 42 },
            "forgotten": { "type": "IntLit", "value": 7 }
          }
        }""")
        val ok = r as VerifyResult.Ok
        val warning = ok.warnings.filterIsInstance<VerifyWarning.UnreachableNode>().singleOrNull()
        assertNotNull(warning) { "expected exactly one UnreachableNode warning, got: ${ok.warnings}" }
        assertEquals("IntLit", warning!!.nodeTypeName)
    }

    @Test
    fun `an unwired Lambda warns for the Lambda and its private subtree`() {
        val r = verify("""{
          "version": 1, "root": "main",
          "nodes": {
            "main":   { "type": "IntLit", "value": 42 },
            "intT":   { "type": "PrimitiveType", "kind": "Int" },
            "x":      { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
            "xRef":   { "type": "VarRef", "binder": "x" },
            "helper": { "type": "Lambda", "parameters": ["x"], "body": "xRef" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        val names = ok.warnings.filterIsInstance<VerifyWarning.UnreachableNode>()
            .map { it.nodeTypeName }
            .sorted()
        assertEquals(listOf("Lambda", "ParameterDecl", "PrimitiveType", "VarRef"), names) {
            "the unwired Lambda and everything only it references should warn, got: ${ok.warnings}"
        }
    }

    @Test
    fun `a fully wired polymorphic program produces zero warnings`() {
        // TypeParameter is referenced only through TypeAbstraction's binder
        // declaration list — an edge childNodeIds omits but the warning
        // pass must include.
        val r = verify("""{
          "version": 1, "root": "app",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "intT":    { "type": "PrimitiveType", "kind": "Int" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "arg":     { "type": "IntLit", "value": 1 },
            "app":     { "type": "Application", "function": "id", "arguments": ["arg"], "typeArguments": ["intT"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.warnings.isEmpty()) { "expected zero warnings, got: ${ok.warnings}" }
    }

    @Test
    fun `a node referenced only through a NodeRef hash boundary does not warn`() {
        val r = verify("""{
          "version": 1, "root": "main",
          "nodes": {
            "shared": { "type": "IntLit", "value": 123 },
            "main":   { "type": "NodeRef", "target": "shared" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.warnings.isEmpty()) { "expected zero warnings, got: ${ok.warnings}" }
    }

    @Test
    fun `a manifest alongside the program root does not warn`() {
        // Manifests are deliberately allowed to be non-root-reachable
        // (Verifier.checkManifests certifies them store-wide); the warning
        // pass treats every manifest as a root, including its export
        // targets and declared effects.
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
              { "target": "writer", "declaredEffects": ["writeFx"], "displayName": "Fs.writeFile" }
            ] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.warnings.isEmpty()) { "expected zero warnings, got: ${ok.warnings}" }
    }

    @Test
    fun `an EffectCategory alongside the program root does not warn`() {
        // Effect categories are the grant vocabulary consumed by host-side
        // capability policy (the CLI --grant-all path collects every
        // EffectCategory in the store); one may legitimately exist only to
        // name a capability the program does not exercise — e.g. corpus 13
        // declares netFx solely so the harness can grant it and the
        // CapabilityScope can demonstrate narrowing it away.
        val r = verify("""{
          "version": 1, "root": "main",
          "nodes": {
            "main":  { "type": "IntLit", "value": 42 },
            "strT":  { "type": "PrimitiveType", "kind": "String" },
            "netFx": { "type": "EffectCategory", "categoryName": "Network.Connect", "parameters": ["strT"] }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.warnings.isEmpty()) { "expected zero warnings, got: ${ok.warnings}" }
    }

    @Test
    fun `a StateMachine alongside the program root does not warn`() {
        // The group runtime drives every StateMachine in the store, not only
        // root-reachable ones; the warning pass treats each machine as a root
        // so its transition function, streams, and effects do not warn.
        val r = verify("""{
          "version": 1, "root": "main",
          "nodes": {
            "main":             { "type": "IntLit", "value": 42 },
            "boolT":            { "type": "PrimitiveType", "kind": "Bool" },
            "unitT":            { "type": "PrimitiveType", "kind": "Unit" },
            "emptyOutputsT":    { "type": "ProductType", "fields": [] },
            "stateFieldT":      { "type": "ProductTypeField", "name": "state", "fieldType": "boolT" },
            "outputsFieldT":    { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyOutputsT" },
            "resultT":          { "type": "ProductType", "fields": ["stateFieldT", "outputsFieldT"] },
            "boolNotFnT":       { "type": "FunctionType", "parameters": ["boolT"], "result": "boolT" },
            "boolNotFn":        { "type": "ForeignNode", "target": "strand-builtin:Bool.Not", "foreignType": "boolNotFnT" },
            "stateParam":       { "type": "ParameterDecl", "name": "s", "paramType": "boolT" },
            "eventParam":       { "type": "ParameterDecl", "name": "e", "paramType": "unitT" },
            "stateParamRef":    { "type": "VarRef", "binder": "stateParam" },
            "negatedState":     { "type": "Application", "function": "boolNotFn", "arguments": ["stateParamRef"] },
            "stateFieldV":      { "type": "ProductFieldValue", "fieldName": "state", "value": "negatedState" },
            "emptyOutputsV":    { "type": "ProductValue", "ofType": "emptyOutputsT", "fields": [] },
            "outputsFieldV":    { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyOutputsV" },
            "transitionResult": { "type": "ProductValue", "ofType": "resultT", "fields": ["stateFieldV", "outputsFieldV"] },
            "transitionLambda": { "type": "Lambda", "parameters": ["stateParam", "eventParam"], "body": "transitionResult" },
            "initialState":     { "type": "BoolLit", "value": false },
            "inputStream":      { "type": "EventStream", "eventType": "unitT", "streamKind": "external" },
            "receiveFx":        { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
            "toggleMachine":    {
              "type": "StateMachine",
              "transitionFn": "transitionLambda",
              "initialState": "initialState",
              "inputStreams": ["inputStream"],
              "outputStreams": [],
              "effects": ["receiveFx"]
            }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertTrue(ok.warnings.isEmpty()) { "expected zero warnings, got: ${ok.warnings}" }
    }
}
