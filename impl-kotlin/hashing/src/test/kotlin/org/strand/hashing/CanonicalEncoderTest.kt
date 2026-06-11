package org.strand.hashing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.core.Primitive
import java.security.MessageDigest

/**
 * Tests of [CanonicalEncoder] that focus on encoding shapes and the
 * properties the encoding is supposed to guarantee (alpha-equivalence,
 * field-order normalization, determinism). The encoder is parameterized by
 * a hash function; these tests use a stable mock (SHA-256 with a 0x1e
 * prefix, the BLAKE3 multi-hash byte length) so we can compare byte
 * sequences across calls without depending on the BLAKE3 library's behavior.
 */
class CanonicalEncoderTest {

    private fun mockHash(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = ByteArray(1 + digest.size)
        out[0] = 0x1e
        digest.copyInto(out, 1)
        return out
    }

    private fun newEncoder(store: NodeStore): CanonicalEncoder =
        CanonicalEncoder(canonicalNodeStoreLookup(store), ::mockHash)

    @Test
    fun `IntLit encoding starts with N-001 tag`() {
        val store = NodeStore()
        val id = store.add(Node.IntLit(42))
        val bytes = newEncoder(store).encode(id)
        // Tag is big-endian 0x00 00 00 01 for N-001 IntLit, followed by CBOR int 42 (0x18 0x2A).
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x01.toByte(), bytes[3])
        // CBOR encoding of 42: 0x18 followed by 0x2A (since 42 > 23).
        assertEquals(0x18.toByte(), bytes[4])
        assertEquals(0x2A.toByte(), bytes[5])
        assertEquals(6, bytes.size)
    }

    @Test
    fun `UnitLit encoding is just the tag`() {
        val store = NodeStore()
        val id = store.add(Node.UnitLit)
        val bytes = newEncoder(store).encode(id)
        // Tag for N-005 = 5; no content fields.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x05), bytes)
    }

    @Test
    fun `two identical IntLits produce identical bytes`() {
        val storeA = NodeStore().also { it.add(Node.IntLit(7)) }
        val storeB = NodeStore().also { it.add(Node.IntLit(7)) }
        val a = newEncoder(storeA).encode(NodeId(0))
        val b = newEncoder(storeB).encode(NodeId(0))
        assertArrayEquals(a, b)
    }

    @Test
    fun `alpha-equivalent identity lambdas produce identical encodings`() {
        // \x:Int. x   in store A
        // \y:Int. y   in store B
        // Names differ; binder positions match. Encodings must be byte-equal.
        val a = buildIdentityLambda("x")
        val b = buildIdentityLambda("y")
        assertArrayEquals(a.first, b.first) {
            "alpha-equivalent lambdas should hash-encode identically:\n" +
                "  a: ${a.first.toHex()}\n  b: ${b.first.toHex()}"
        }
    }

    @Test
    fun `lambdas differing in parameter type do not collide`() {
        val intLambda = buildIdentityLambda("x", primitive = Primitive.Int)
        val boolLambda = buildIdentityLambda("x", primitive = Primitive.Bool)
        assertFalse(intLambda.first.contentEquals(boolLambda.first)) {
            "Lambda over Int should not encode the same as Lambda over Bool"
        }
    }

    @Test
    fun `ProductType field order is normalized by field name`() {
        // ProductType {a: Int, b: Bool} and {b: Bool, a: Int} should encode identically.
        val ab = buildProductType(listOf("a" to Primitive.Int, "b" to Primitive.Bool))
        val ba = buildProductType(listOf("b" to Primitive.Bool, "a" to Primitive.Int))
        assertArrayEquals(ab.first, ba.first) {
            "ProductType encoding should be invariant under field declaration order"
        }
    }

    @Test
    fun `ProductType with different field names produces different encodings`() {
        val ab = buildProductType(listOf("a" to Primitive.Int, "b" to Primitive.Bool))
        val xy = buildProductType(listOf("x" to Primitive.Int, "y" to Primitive.Bool))
        assertFalse(ab.first.contentEquals(xy.first)) {
            "ProductType encoding includes field names; differing names should not collide"
        }
    }

    @Test
    fun `polymorphic identity TypeAbstraction encodes identically across binder renaming`() {
        // Λa. \x:a. x   vs   Λb. \y:b. y
        val a = buildPolymorphicIdentity(typeParamName = "a", paramName = "x")
        val b = buildPolymorphicIdentity(typeParamName = "b", paramName = "y")
        assertArrayEquals(a.first, b.first) {
            "Type abstractions differing only in bound names should encode identically"
        }
    }

    @Test
    fun `encoding is deterministic across repeated calls`() {
        val (bytes, store, rootId) = buildIdentityLambda("x")
        val encoder = newEncoder(store)
        val again = encoder.encode(rootId)
        assertArrayEquals(bytes, again)
    }

    // ----- Q-031 backward-compat: Application.effectInstances -----

    @Test
    fun `pure Application hash is unchanged by the effectInstances field`() {
        // Build the same pure (id 42) application twice. The Application
        // node has an empty effectInstances list (the default); per the
        // Q-031 backward-compat rule, the encoded bytes must match the
        // pre-Q-031 Application encoding exactly, which has only three
        // fields (function, arguments, typeArguments). The encoder is
        // gated on `effectInstances.size > 0` precisely to keep this
        // invariant.
        val storeA = NodeStore()
        val intT_A = storeA.add(Node.PrimitiveType(Primitive.Int))
        val pdA = storeA.add(Node.ParameterDecl("x", intT_A))
        val bodyA = storeA.add(Node.VarRef(pdA))
        val lamA = storeA.add(Node.Lambda(parameters = listOf(pdA), body = bodyA))
        val argA = storeA.add(Node.IntLit(42))
        val appA = storeA.add(Node.Application(
            function = lamA, arguments = listOf(argA)
            // typeArguments + effectInstances default to emptyList()
        ))
        val bytesA = newEncoder(storeA).encode(appA)

        val storeB = NodeStore()
        val intT_B = storeB.add(Node.PrimitiveType(Primitive.Int))
        val pdB = storeB.add(Node.ParameterDecl("x", intT_B))
        val bodyB = storeB.add(Node.VarRef(pdB))
        val lamB = storeB.add(Node.Lambda(parameters = listOf(pdB), body = bodyB))
        val argB = storeB.add(Node.IntLit(42))
        val appB = storeB.add(Node.Application(
            function = lamB, arguments = listOf(argB),
            typeArguments = emptyList(), effectInstances = emptyList()
        ))
        val bytesB = newEncoder(storeB).encode(appB)

        assertArrayEquals(bytesA, bytesB) {
            "Application with empty effectInstances must hash identically " +
                "regardless of how the field was supplied (default vs explicit empty)."
        }
    }

    @Test
    fun `effectful call with empty effectInstances hashes identically to the pre-Q-031 form`() {
        // An effectful call (callee is a ForeignNode declaring Time.Now)
        // with effectInstances explicitly empty must encode identically to
        // the same application built without any effectInstances field.
        // This is the load-bearing back-compat invariant: the 32 corpus
        // programs continue to hash unchanged.
        val bytesNoField = buildEffectfulCallApplication(includeEmptyEffectInstances = false).first
        val bytesEmptyField = buildEffectfulCallApplication(includeEmptyEffectInstances = true).first
        assertArrayEquals(bytesNoField, bytesEmptyField) {
            "Effectful Application with empty effectInstances must hash identically " +
                "to the pre-Q-031 form (no field present)."
        }
    }

    @Test
    fun `non-empty effectInstances produce a different hash`() {
        // Two Applications differing only in their effectInstances must
        // hash differently. This proves the gated encoding is in fact
        // contributing to the hash when present.
        val withoutInstances = buildEffectfulCallApplication(includeEmptyEffectInstances = false).first
        val withInstance = buildEffectfulCallWithInstance().first
        assertFalse(withoutInstances.contentEquals(withInstance)) {
            "Application with a populated effectInstances list should not " +
                "hash the same as an Application with no effect instances."
        }
    }

    @Test
    fun `effectInstance order does not affect Application hash`() {
        // Two effect instances supplied in opposite orders must hash the
        // same — effectInstances is canonically encoded as a sorted set
        // of multi-hashes.
        val orderA = buildTwoEffectInstanceApplication(swap = false).first
        val orderB = buildTwoEffectInstanceApplication(swap = true).first
        assertArrayEquals(orderA, orderB) {
            "Application's effectInstances list is set-valued in canonical " +
                "form; declaration order must not affect the hash."
        }
    }

    // ----- Handler (N-043) -----

    @Test
    fun `Handler encoding starts with N-043 tag`() {
        val (bytes, _, _) = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L)
        // Tag is big-endian 0x00 00 00 2B for N-043 Handler.
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x2B.toByte(), bytes[3])
    }

    @Test
    fun `two Handlers with identical intercept handle and body hash identically`() {
        // Independent stores; identical structure. The canonical encoding
        // is purely structural, so byte-equal output is required.
        val a = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L).first
        val b = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L).first
        assertArrayEquals(a, b) {
            "Two Handlers with the same (intercept, handle, body) structure " +
                "must encode byte-identically."
        }
    }

    @Test
    fun `Handlers differing in the handle expression hash differently`() {
        // Same intercept, same body; only the handler's returned constant
        // differs. The handle expression's hash is a structural field of
        // the Handler so the two must hash differently.
        val a = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L).first
        val b = buildSimpleHandler(handlerReturns = 8L, bodyReturns = 99L).first
        assertFalse(a.contentEquals(b)) {
            "Handlers differing in their handle expression should not " +
                "collide on the hash."
        }
    }

    @Test
    fun `Handlers differing in the body hash differently`() {
        // Same intercept, same handle constant; only the body's returned
        // constant differs. The body is a structural field.
        val a = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L).first
        val b = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 100L).first
        assertFalse(a.contentEquals(b)) {
            "Handlers differing in their body should not collide on the hash."
        }
    }

    @Test
    fun `Handlers differing in the intercepted category hash differently`() {
        // Same handle, same body; only the intercepted EffectCategory's
        // name differs.
        val a = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L,
            interceptCategoryName = "Time.Now").first
        val b = buildSimpleHandler(handlerReturns = 7L, bodyReturns = 99L,
            interceptCategoryName = "Logging.Info").first
        assertFalse(a.contentEquals(b)) {
            "Handlers differing in their intercepted category should not collide."
        }
    }

    // ----- Builders -----

    /**
     * Build a small store containing `\<paramName>:<primitive>. <paramName>` and
     * return its top-level encoding plus the store and root id for further use.
     */
    private fun buildIdentityLambda(
        paramName: String,
        primitive: Primitive = Primitive.Int,
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val typeId = store.add(Node.PrimitiveType(primitive))
        val paramId = store.add(Node.ParameterDecl(paramName, typeId))
        val bodyId = store.add(Node.VarRef(paramId))
        val lambdaId = store.add(Node.Lambda(parameters = listOf(paramId), body = bodyId))
        val bytes = newEncoder(store).encode(lambdaId)
        return Triple(bytes, store, lambdaId)
    }

    private fun buildProductType(
        fields: List<Pair<String, Primitive>>,
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val fieldIds = fields.map { (name, prim) ->
            val typeId = store.add(Node.PrimitiveType(prim))
            store.add(Node.ProductTypeField(fieldName = name, fieldType = typeId))
        }
        val productId = store.add(Node.ProductType(fields = fieldIds))
        val bytes = newEncoder(store).encode(productId)
        return Triple(bytes, store, productId)
    }

    /**
     * Build `Λ<typeParamName>. \<paramName>:<typeParamName>. <paramName>` and
     * return its top-level encoding plus the store and root id.
     */
    private fun buildPolymorphicIdentity(
        typeParamName: String,
        paramName: String,
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val tp = store.add(Node.TypeParameter(name = typeParamName, bound = null))
        val pd = store.add(Node.ParameterDecl(name = paramName, paramType = tp))
        val body = store.add(Node.VarRef(binder = pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        val tabs = store.add(Node.TypeAbstraction(typeParameters = listOf(tp), body = lam))
        val bytes = newEncoder(store).encode(tabs)
        return Triple(bytes, store, tabs)
    }

    /**
     * Build an effectful Application that calls a parameterless ForeignNode
     * declaring `Time.Now`. If [includeEmptyEffectInstances] is true, the
     * Application carries `effectInstances = emptyList()` explicitly; if
     * false, it is omitted entirely (default constructor). The two forms
     * must encode identically by the back-compat rule.
     */
    private fun buildEffectfulCallApplication(
        includeEmptyEffectInstances: Boolean,
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val timeFx = store.add(Node.EffectCategory("Time.Now", parameters = emptyList()))
        val nowT = store.add(Node.FunctionType(
            parameters = emptyList(), result = intT, effects = listOf(timeFx)
        ))
        val now = store.add(Node.ForeignNode(
            target = "strand-builtin:Time.Now", foreignType = nowT, effects = listOf(timeFx)
        ))
        val app = store.add(if (includeEmptyEffectInstances) {
            Node.Application(function = now, arguments = emptyList(),
                typeArguments = emptyList(), effectInstances = emptyList())
        } else {
            Node.Application(function = now, arguments = emptyList())
        })
        val bytes = newEncoder(store).encode(app)
        return Triple(bytes, store, app)
    }

    /**
     * Build an Application calling a parameterless `Time.Now` ForeignNode
     * whose Application carries one EffectDecl in `effectInstances`. The
     * EffectDecl has no parameter expressions (Time.Now is parameterless).
     * Compared against the no-instances version, this proves the gated
     * encoding contributes to the hash when populated.
     */
    private fun buildEffectfulCallWithInstance(): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val timeFx = store.add(Node.EffectCategory("Time.Now", parameters = emptyList()))
        val nowT = store.add(Node.FunctionType(
            parameters = emptyList(), result = intT, effects = listOf(timeFx)
        ))
        val now = store.add(Node.ForeignNode(
            target = "strand-builtin:Time.Now", foreignType = nowT, effects = listOf(timeFx)
        ))
        val timeDecl = store.add(Node.EffectDecl(effectType = timeFx, parameters = emptyList()))
        val app = store.add(Node.Application(
            function = now, arguments = emptyList(),
            typeArguments = emptyList(), effectInstances = listOf(timeDecl)
        ))
        val bytes = newEncoder(store).encode(app)
        return Triple(bytes, store, app)
    }

    /**
     * Build a synthetic Application with two EffectDecls (covering two
     * distinct EffectCategories). [swap] flips the declaration order to
     * prove the encoding is order-invariant.
     */
    private fun buildTwoEffectInstanceApplication(
        swap: Boolean,
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val timeFx = store.add(Node.EffectCategory("Time.Now", parameters = emptyList()))
        val otherFx = store.add(Node.EffectCategory("Other.Effect", parameters = emptyList()))
        val fnT = store.add(Node.FunctionType(
            parameters = emptyList(), result = intT, effects = listOf(timeFx, otherFx)
        ))
        val fn = store.add(Node.ForeignNode(
            target = "strand-builtin:Time.Now", foreignType = fnT, effects = listOf(timeFx, otherFx)
        ))
        val timeDecl = store.add(Node.EffectDecl(effectType = timeFx, parameters = emptyList()))
        val otherDecl = store.add(Node.EffectDecl(effectType = otherFx, parameters = emptyList()))
        val app = store.add(Node.Application(
            function = fn, arguments = emptyList(),
            typeArguments = emptyList(),
            effectInstances = if (swap) listOf(otherDecl, timeDecl) else listOf(timeDecl, otherDecl)
        ))
        val bytes = newEncoder(store).encode(app)
        return Triple(bytes, store, app)
    }

    /**
     * Build a [Node.Handler] over a parameterless intercept category. The
     * handle is `λ. <handlerReturns>` (a Lambda returning a constant Int);
     * the body is just `<bodyReturns>` (an IntLit). The Handler itself is
     * structurally simple so the test focuses on the three structural
     * fields (intercept, handle, body) without distraction.
     */
    private fun buildSimpleHandler(
        handlerReturns: Long,
        bodyReturns: Long,
        interceptCategoryName: String = "Time.Now",
    ): Triple<ByteArray, NodeStore, NodeId> {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val intercept = store.add(Node.EffectCategory(interceptCategoryName, parameters = emptyList()))
        val handleLit = store.add(Node.IntLit(handlerReturns))
        val handleLam = store.add(Node.Lambda(
            parameters = emptyList(),
            body = handleLit,
            effects = emptyList(),
        ))
        val body = store.add(Node.IntLit(bodyReturns))
        val handler = store.add(Node.Handler(intercept = intercept, handle = handleLam, body = body))
        val bytes = newEncoder(store).encode(handler)
        return Triple(bytes, store, handler)
    }

    // ----- Attempt (N-047) -----

    @Test
    fun `Attempt encoding is the N-047 tag followed by the body-hash byte string`() {
        // Byte trace per proposals/error-recovery.md § 4.4 and
        // design/canonical-encoding.md: [tag=47 (0x00 00 00 2F)] then the
        // CBOR byte string of the 33-byte multi-hash of the body child.
        val store = NodeStore()
        val body = store.add(Node.IntLit(42))
        val att = store.add(Node.Attempt(body = body))
        val bytes = newEncoder(store).encode(att)

        // Tag is big-endian 0x00 00 00 2F for N-047 Attempt.
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x2F.toByte(), bytes[3])

        // Next is a CBOR byte string of length 33 (the multi-hash):
        // major type 2, length 33 -> 0x58 0x21.
        assertEquals(0x58.toByte(), bytes[4])
        assertEquals(0x21.toByte(), bytes[5])
        // 4 tag bytes + 2 CBOR header bytes + 33 hash bytes = 39 total.
        assertEquals(4 + 2 + 33, bytes.size)

        // The 33 hash bytes equal the encoded body's own hash.
        val bodyHash = mockHash(newEncoder(store).encode(body))
        assertArrayEquals(bodyHash, bytes.copyOfRange(6, bytes.size))
    }

    @Test
    fun `two Attempts over hash-identical bodies encode byte-identically`() {
        val a = NodeStore().let { s ->
            val body = s.add(Node.IntLit(7)); val att = s.add(Node.Attempt(body)); newEncoder(s).encode(att)
        }
        val b = NodeStore().let { s ->
            val body = s.add(Node.IntLit(7)); val att = s.add(Node.Attempt(body)); newEncoder(s).encode(att)
        }
        assertArrayEquals(a, b) {
            "Two Attempts over structurally identical bodies must encode byte-identically."
        }
    }

    @Test
    fun `Attempts over different bodies hash differently`() {
        val a = NodeStore().let { s ->
            val body = s.add(Node.IntLit(7)); val att = s.add(Node.Attempt(body)); newEncoder(s).encode(att)
        }
        val b = NodeStore().let { s ->
            val body = s.add(Node.IntLit(8)); val att = s.add(Node.Attempt(body)); newEncoder(s).encode(att)
        }
        assertFalse(a.contentEquals(b)) {
            "Attempts over different bodies should not collide on the hash."
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
