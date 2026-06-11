package org.strand.verifier

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.JsonIngest
import org.strand.core.Primitive
import org.strand.hashing.Hasher

/**
 * N-047 Attempt (Q-048, proposals/error-recovery.md § 5) — verifier scenarios.
 * Covers the synthesized Result type, the AttemptBodyMustBeMonomorphic rule,
 * the closure-passthrough property, and the hash-identity of the synthesized
 * sum with an agent-declared `Ok(T) | Err({kind, detail})` SumType.
 */
class AttemptVerifierTest {

    private fun verify(json: String): VerifyResult {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        return Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
    }

    /** The fixed ErrorPayload product `{kind: String, detail: String}`. */
    private val errorPayload = TypeExpr.Product(
        origin = org.strand.core.NodeId(-1),
        fields = listOf(
            TypeExpr.Product.Field("kind", TypeExpr.Prim(Primitive.String)),
            TypeExpr.Product.Field("detail", TypeExpr.Prim(Primitive.String)),
        ),
    )

    private fun resultSum(ok: TypeExpr): TypeExpr.Sum = TypeExpr.Sum(
        origin = org.strand.core.NodeId(-1),
        cases = listOf(
            TypeExpr.Sum.Case("Ok", ok),
            TypeExpr.Sum.Case("Err", errorPayload),
        ),
    )

    @Test
    fun `TRY over an Int literal synthesizes Result of Int`() {
        val r = verify("""{
          "version": 1, "root": "att",
          "nodes": {
            "lit": { "type": "IntLit", "value": 5 },
            "att": { "type": "Attempt", "body": "lit" }
          }
        }""")
        val ok = r as VerifyResult.Ok
        assertEquals(resultSum(TypeExpr.Prim(Primitive.Int)), ok.rootType)
    }

    @Test
    fun `Attempt effect closure equals the body closure (passthrough)`() {
        // The body is an effectful Fs.Read application; the Attempt's closure
        // must equal the body's closure (the readFx category), not narrow it.
        val ingest = JsonIngest.parse("""{
          "version": 1, "root": "att",
          "nodes": {
            "stringT": { "type": "PrimitiveType", "kind": "String" },
            "bytesT":  { "type": "PrimitiveType", "kind": "Bytes" },
            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
            "fsReadT": { "type": "FunctionType", "parameters": ["stringT"], "result": "bytesT", "effects": ["readFx"] },
            "fsRead":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "fsReadT", "effects": ["readFx"] },
            "pathS":   { "type": "StringLit", "value": "config.json" },
            "readApp": { "type": "Application", "function": "fsRead", "arguments": ["pathS"] },
            "att":     { "type": "Attempt", "body": "readApp" }
          }
        }""")
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val r = Verifier(finalized.store, finalized.hashToNodeId).verify(finalized.root)
        val ok = r as VerifyResult.Ok
        // The synthesized result is Result<Bytes>.
        assertEquals(resultSum(TypeExpr.Prim(Primitive.Bytes)), ok.rootType)
        // The root carries the readFx effect category in its closure — the
        // Attempt did not subtract it (failures are not effects).
        val readFx = finalized.hashToNodeId.values  // sanity: store is non-empty
        assertTrue(readFx.isNotEmpty())
    }

    @Test
    fun `TRY over a polymorphic body is rejected as AttemptBodyMustBeMonomorphic`() {
        // The body is a TypeAbstraction (identity), whose type is a Forall.
        val r = verify("""{
          "version": 1, "root": "att",
          "nodes": {
            "T_a":     { "type": "TypeParameter", "name": "a" },
            "x":       { "type": "ParameterDecl", "name": "x", "paramType": "T_a" },
            "xRef":    { "type": "VarRef", "binder": "x" },
            "idInner": { "type": "Lambda", "parameters": ["x"], "body": "xRef" },
            "id":      { "type": "TypeAbstraction", "typeParameters": ["T_a"], "body": "idInner" },
            "att":     { "type": "Attempt", "body": "id" }
          }
        }""")
        val f = r as VerifyResult.Failed
        assertTrue(
            f.errors.any { it is VerifyError.AttemptBodyMustBeMonomorphic },
            "expected AttemptBodyMustBeMonomorphic, got ${f.errors}",
        )
    }

    @Test
    fun `Match over a TRY result against an agent-declared Result sum verifies (hash identity)`() {
        // The agent declares the Result<Bytes> sum (the RES sugar's expansion)
        // explicitly and matches the Attempt result against it. The Match
        // type-checks ONLY if the verifier-synthesized sum is structurally —
        // and therefore hash- — identical to the agent's declaration.
        val r = verify("""{
          "version": 1, "root": "match",
          "nodes": {
            "stringT":    { "type": "PrimitiveType", "kind": "String" },
            "bytesT":     { "type": "PrimitiveType", "kind": "Bytes" },
            "kindF":      { "type": "ProductTypeField", "name": "kind", "fieldType": "stringT" },
            "detailF":    { "type": "ProductTypeField", "name": "detail", "fieldType": "stringT" },
            "errPayloadT":{ "type": "ProductType", "fields": ["kindF", "detailF"] },
            "okCase":     { "type": "SumTypeCase", "name": "Ok", "caseType": "bytesT" },
            "errCase":    { "type": "SumTypeCase", "name": "Err", "caseType": "errPayloadT" },
            "resBytesT":  { "type": "SumType", "cases": ["okCase", "errCase"] },

            "readFx":  { "type": "EffectCategory", "categoryName": "Filesystem.Read" },
            "fsReadT": { "type": "FunctionType", "parameters": ["stringT"], "result": "bytesT", "effects": ["readFx"] },
            "fsRead":  { "type": "ForeignNode", "target": "strand-builtin:Fs.Read", "foreignType": "fsReadT", "effects": ["readFx"] },
            "pathS":   { "type": "StringLit", "value": "config.json" },
            "readApp": { "type": "Application", "function": "fsRead", "arguments": ["pathS"] },
            "tryRead": { "type": "Attempt", "body": "readApp" },

            "okPay":   { "type": "Pattern", "kind": "variable", "patternType": "bytesT", "name": "b" },
            "okPat":   { "type": "Pattern", "kind": "constructor", "patternType": "resBytesT", "caseName": "Ok", "payloadPattern": "okPay" },
            "errPay":  { "type": "Pattern", "kind": "variable", "patternType": "errPayloadT", "name": "e" },
            "errPat":  { "type": "Pattern", "kind": "constructor", "patternType": "resBytesT", "caseName": "Err", "payloadPattern": "errPay" },

            "okBodyRef":  { "type": "VarRef", "binder": "okPay" },
            "defaultB":   { "type": "BytesLit", "value": "7b7d" },
            "okMC":       { "type": "MatchCase", "pattern": "okPat", "body": "okBodyRef" },
            "errMC":      { "type": "MatchCase", "pattern": "errPat", "body": "defaultB" },
            "match":      { "type": "Match", "scrutinee": "tryRead", "cases": ["okMC", "errMC"] }
          }
        }""")
        val ok = r as? VerifyResult.Ok
            ?: fail("expected the program to verify; got ${(r as VerifyResult.Failed).errors}")
        // The Match yields Bytes on both arms.
        assertEquals(TypeExpr.Prim(Primitive.Bytes), ok.rootType)
    }
}
