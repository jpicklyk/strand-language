package org.strand.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.verifier.Verifier
import org.strand.verifier.VerifyResult

/**
 * The `group` subcommand drives the StateMachines of a program. The
 * canonical store keeps every authored node, including ones the program
 * root does not reach, and verification is rooted: a StateMachine outside
 * the root's reach has never been checked. [partitionGroupMachines]
 * separates the two so the subcommand can refuse the second kind.
 */
class GroupMachineSelectionTest {

    /** A verified root machine, and a second machine nothing references whose transition is ill-typed. */
    private val json = """{
        "version": 1, "root": "m",
        "nodes": {
          "intT":    { "type": "PrimitiveType", "kind": "Int" },
          "boolT":   { "type": "PrimitiveType", "kind": "Bool" },
          "emptyT":  { "type": "ProductType", "fields": [] },
          "sft":     { "type": "ProductTypeField", "name": "state", "fieldType": "intT" },
          "oft":     { "type": "ProductTypeField", "name": "outputs", "fieldType": "emptyT" },
          "resT":    { "type": "ProductType", "fields": ["sft", "oft"] },
          "sP":      { "type": "ParameterDecl", "name": "s", "paramType": "intT" },
          "eP":      { "type": "ParameterDecl", "name": "e", "paramType": "intT" },
          "sRef":    { "type": "VarRef", "binder": "sP" },
          "sV":      { "type": "ProductFieldValue", "fieldName": "state", "value": "sRef" },
          "emptyV":  { "type": "ProductValue", "ofType": "emptyT", "fields": [] },
          "oV":      { "type": "ProductFieldValue", "fieldName": "outputs", "value": "emptyV" },
          "result":  { "type": "ProductValue", "ofType": "resT", "fields": ["sV", "oV"] },
          "lam":     { "type": "Lambda", "parameters": ["sP", "eP"], "body": "result" },
          "zero":    { "type": "IntLit", "value": 0 },
          "recvFx":  { "type": "EffectCategory", "categoryName": "StateMachine.Receive" },
          "inStr":   { "type": "EventStream", "eventType": "intT", "streamKind": "external" },
          "m":       { "type": "StateMachine", "transitionFn": "lam", "initialState": "zero",
                       "inputStreams": ["inStr"], "outputStreams": [], "effects": ["recvFx"] },
          "yes":     { "type": "BoolLit", "value": true },
          "stray":   { "type": "StateMachine", "transitionFn": "lam", "initialState": "yes",
                       "inputStreams": ["inStr"], "outputStreams": [], "effects": [] }
        }
      }"""

    @Test
    fun `a StateMachine the root does not reach is reported as unverified`() {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val verify = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        assertTrue(verify is VerifyResult.Ok) { "the root machine verifies although the stray one is ill-formed: $verify" }

        val (verified, unverified) = partitionGroupMachines(finalized.store, verify as VerifyResult.Ok)
        assertEquals(listOf(ingest.nameMap.getValue("m")), verified)
        assertEquals(listOf(ingest.nameMap.getValue("stray")), unverified)
    }
}
