package org.strand.hashing

import io.github.rctcwyvrn.blake3.Blake3
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.strand.core.Node
import org.strand.core.NodeStore
import org.strand.core.Primitive
import org.strand.core.StreamKind

/**
 * Byte-by-byte validation of `design/canonical-encoding.md` against the real
 * encoder. Each test constructs the EXPECTED canonical bytes by hand from the
 * specification prose alone — framing tag, canonical-CBOR items, child
 * multihashes — and asserts the encoder produces exactly those bytes (and,
 * where the document quotes concrete digests, exactly those digests).
 *
 * If one of these tests fails after an encoder change, the canonical encoding
 * has changed: that is a hash-compatibility break and must be treated as
 * such (see CorpusGoldenHashTest in :corpus for the corpus-wide net). If a
 * test fails because the document was wrong, fix the document, not the code.
 */
class CanonicalEncodingSpecTest {

    // ----- Spec-side primitives, built independently of CanonicalCbor -----

    /** BLAKE3-256 multihash exactly as the spec defines it: 0x1e ++ 32-byte digest. */
    private fun multihash(encoding: ByteArray): ByteArray {
        val hasher = Blake3.newInstance()
        hasher.update(encoding)
        return byteArrayOf(0x1e) + hasher.digest()
    }

    /** 4-byte big-endian category tag. */
    private fun tag(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** Hash reference: CBOR byte string holding a 33-byte multihash (header 0x58 0x21). */
    private fun hashRef(multihash: ByteArray): ByteArray {
        assertEquals(33, multihash.size)
        return byteArrayOf(0x58, 0x21) + multihash
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun newEncoder(store: NodeStore): CanonicalEncoder =
        CanonicalEncoder(canonicalNodeStoreLookup(store), ::multihash)

    // ----- Trace 1: IntLit (literal framing + canonical-CBOR integer) -----

    @Test
    fun `IntLit 42 - full byte trace and digest`() {
        val store = NodeStore()
        val id = store.add(Node.IntLit(42))
        val encoding = newEncoder(store).encode(id)

        // Spec: [tag 1][int(42)] = 00 00 00 01 18 2a
        // (42 > 23, so CBOR major type 0 takes the one-byte-argument form 0x18.)
        val expected = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x18, 0x2a)
        assertArrayEquals(expected, encoding) { "IntLit 42 encoding: ${hex(encoding)}" }

        // The multihash of these 6 bytes is corpus program 01's golden root
        // hash — the worked example in design/canonical-encoding.md quotes it.
        assertEquals(
            "1e6970ba0a8f8e82923c82d5b40927ba0ba9d0dcfc526c3308e509a28e9f16caad",
            hex(multihash(encoding)),
        )
    }

    @Test
    fun `negative IntLit uses CBOR major type 1`() {
        val store = NodeStore()
        val id = store.add(Node.IntLit(-3))
        // Spec: negative n encodes as major type 1 with argument -1-n; -3 -> 0x22.
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x22),
            newEncoder(store).encode(id),
        )
    }

    // ----- Trace 2: Lambda (binder frame, type/hash references, effects) -----

    @Test
    fun `monomorphic identity Lambda - full byte trace and digests`() {
        // \x:Int. x
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val pd = store.add(Node.ParameterDecl("x", intT))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        val encoder = newEncoder(store)

        // PrimitiveType Int: [tag 7][uint(0)] (Primitive.Int ordinal 0).
        val intEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x07, 0x00)
        assertArrayEquals(intEncoding, encoder.encode(intT))
        val intHash = multihash(intEncoding)

        // VarRef under the lambda's one-frame binder context: [tag 18][uint(0)][uint(0)].
        val varRefEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x12, 0x00, 0x00)
        assertArrayEquals(varRefEncoding, encoder.encode(body, listOf(listOf(pd))))
        val varRefHash = multihash(varRefEncoding)

        // Lambda: [tag 14][array(1: T(paramType))][H(body)][array(0)].
        // The paramType is not a bound TypeParameter, so T(paramType) is a
        // hash reference; the empty effect set is the bare array header 0x80.
        val expected = tag(14) +
            byteArrayOf(0x81.toByte()) + hashRef(intHash) +
            hashRef(varRefHash) +
            byteArrayOf(0x80.toByte())
        val actual = encoder.encode(lam)
        assertArrayEquals(expected, actual) { "Lambda encoding: ${hex(actual)}" }

        // Concrete digests quoted by the worked example in
        // design/canonical-encoding.md § Worked example.
        assertEquals(
            "1e2275d419cd5ebfc869bc21332c53331f3faa772e7fdd0a40ff200aee6d43bfdc",
            hex(intHash),
        )
        assertEquals(
            "1ed076d59afd79a1e7410badf0a9120a045b693832b29089774fa6903b5d1a3480",
            hex(varRefHash),
        )
        assertEquals(
            "1ec30a2eadd3a4e604aeecb08d48c115207a4d3484f70f7360e5643de34c48e7c3",
            hex(multihash(actual)),
        )
    }

    // ----- Trace 3: TypeAbstraction (inline bound-TypeParameter form) -----

    @Test
    fun `polymorphic identity - bound TypeParameter is inlined, not hashed`() {
        // Λa. \x:a. x
        val store = NodeStore()
        val tp = store.add(Node.TypeParameter("a", bound = null))
        val pd = store.add(Node.ParameterDecl("x", tp))
        val body = store.add(Node.VarRef(pd))
        val lam = store.add(Node.Lambda(parameters = listOf(pd), body = body))
        val tabs = store.add(Node.TypeAbstraction(typeParameters = listOf(tp), body = lam))
        val encoder = newEncoder(store)

        // Inside the TypeAbstraction the binder context is [[a]]. The Lambda's
        // parameter type is the bound TypeParameter, so T(paramType) is the
        // INLINE positional form [tag 13][uint(0)][uint(0)] — six raw bytes in
        // the array element position, not a CBOR byte string.
        // The body's context is [[a], [x]]: the VarRef's binder is in the
        // innermost frame, so it still encodes as (depth 0, index 0).
        val varRefEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x12, 0x00, 0x00)
        val varRefHash = multihash(varRefEncoding)
        val expectedLambda = tag(14) +
            byteArrayOf(0x81.toByte()) + tag(13) + byteArrayOf(0x00, 0x00) +
            hashRef(varRefHash) +
            byteArrayOf(0x80.toByte())
        assertArrayEquals(expectedLambda, encoder.encode(lam, listOf(listOf(tp))))

        // TypeAbstraction: [tag 34][uint(1) arity][H(body)] where the body
        // hash is computed in the extended context.
        val expectedTabs = tag(34) + byteArrayOf(0x01) + hashRef(multihash(expectedLambda))
        assertArrayEquals(expectedTabs, encoder.encode(tabs))
    }

    // ----- Trace 4: SumValue + SumType (name sorting, presence prefix) -----

    @Test
    fun `SumValue Some(42) - case sorting and presence prefix`() {
        val store = NodeStore()
        val intT = store.add(Node.PrimitiveType(Primitive.Int))
        val caseSome = store.add(Node.SumTypeCase("Some", intT))
        val caseNone = store.add(Node.SumTypeCase("None", null))
        // Declaration order Some-then-None; canonical order is by case name.
        val sumT = store.add(Node.SumType(listOf(caseSome, caseNone)))
        val fortyTwo = store.add(Node.IntLit(42))
        val value = store.add(Node.SumValue(ofType = sumT, caseName = "Some", payload = fortyTwo))
        val encoder = newEncoder(store)

        val intHash = multihash(byteArrayOf(0x00, 0x00, 0x00, 0x07, 0x00))

        // SumTypeCase "None" (nullary): [tag 11][bytes("None")][uint(0)].
        val noneEncoding = tag(11) +
            byteArrayOf(0x44) + "None".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00)
        assertArrayEquals(noneEncoding, encoder.encode(caseNone))

        // SumTypeCase "Some" (payload Int): [tag 11][bytes("Some")][uint(1)][T(Int)].
        val someEncoding = tag(11) +
            byteArrayOf(0x44) + "Some".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x01) + hashRef(intHash)
        assertArrayEquals(someEncoding, encoder.encode(caseSome))

        // SumType: [tag 10][array(2: H(case) sorted by caseName)] — "None" < "Some".
        val sumEncoding = tag(10) + byteArrayOf(0x82.toByte()) +
            hashRef(multihash(noneEncoding)) + hashRef(multihash(someEncoding))
        assertArrayEquals(sumEncoding, encoder.encode(sumT))

        // SumValue: [tag 40][H(ofType)][bytes("Some")][uint(1)][H(payload)].
        val fortyTwoHash = multihash(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x18, 0x2a))
        val expected = tag(40) +
            hashRef(multihash(sumEncoding)) +
            byteArrayOf(0x44) + "Some".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x01) + hashRef(fortyTwoHash)
        assertArrayEquals(expected, encoder.encode(value))
    }

    // ----- Trace 5: EventStream (default-gating rules) -----

    @Test
    fun `EventStream default and non-default field gating`() {
        val store = NodeStore()
        val bytesT = store.add(Node.PrimitiveType(Primitive.Bytes))
        val allDefault = store.add(Node.EventStream(eventType = bytesT, streamKind = StreamKind.External))
        val withBuffer = store.add(
            Node.EventStream(
                eventType = bytesT,
                streamKind = StreamKind.Output,
                bufferSize = 2,
                overflowPolicy = org.strand.core.OverflowPolicy.DropOldest,
            )
        )
        val encoder = newEncoder(store)

        // PrimitiveType Bytes: [tag 7][uint(5)] (Primitive.Bytes ordinal 5).
        val bytesTEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x07, 0x05)
        assertArrayEquals(bytesTEncoding, encoder.encode(bytesT))
        val bytesTHash = multihash(bytesTEncoding)

        // All-default stream: 2 fields only — [tag 28][H(eventType)][uint(0) External].
        val expectedDefault = tag(28) + hashRef(bytesTHash) + byteArrayOf(0x00)
        assertArrayEquals(expectedDefault, encoder.encode(allDefault))

        // Any non-default optional forces all three slice-3.1/3.6 fields:
        // [tag 28][H(eventType)][uint(2) Output][uint(2) bufferSize]
        // [uint(2) DropOldest][uint(0) consumerMode sentinel Single].
        val expectedBuffered = tag(28) + hashRef(bytesTHash) +
            byteArrayOf(0x02, 0x02, 0x02, 0x00)
        assertArrayEquals(expectedBuffered, encoder.encode(withBuffer))
    }

    // ----- Trace 6: Let + unbound-VarRef sentinel -----

    @Test
    fun `Let binder frame and the unbound sentinel`() {
        val store2 = NodeStore()
        val v = store2.add(Node.IntLit(7))
        // NodeStore assigns sequential ids; the body VarRef targets the Let we
        // add afterwards, so pre-compute its id (= 2 with this insertion order).
        val bodyRef = store2.add(Node.VarRef(org.strand.core.NodeId(2)))
        val let = store2.add(Node.Let(name = "n", value = v, body = bodyRef))
        assertEquals(org.strand.core.NodeId(2), let)
        val encoder = newEncoder(store2)

        // IntLit 7: [tag 1][int(7)] = 00 00 00 01 07.
        val sevenEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x07)
        // VarRef inside the Let body: the Let pushes a single-entry frame
        // holding the LET NODE's own id -> (depth 0, index 0).
        val varRefEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x12, 0x00, 0x00)
        val expectedLet = tag(17) +
            hashRef(multihash(sevenEncoding)) +
            hashRef(multihash(varRefEncoding))
        assertArrayEquals(expectedLet, encoder.encode(let))

        // The same VarRef encoded OUTSIDE the Let's body has no binder in
        // scope: the encoder emits the unbound sentinel
        // (depth = index = 2147483647 = 0x7fffffff, CBOR 0x1a 7f ff ff ff).
        val sentinel = byteArrayOf(
            0x00, 0x00, 0x00, 0x12,
            0x1a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x1a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        )
        assertArrayEquals(sentinel, encoder.encode(bodyRef, emptyList()))
    }

    // ----- Trace 7: Attempt (single body hash reference, no content fields) -----

    @Test
    fun `Attempt over IntLit 42 - tag 47 then the body hash reference`() {
        // Spec § Control flow: Attempt (47) encodes a single field H(body) — a
        // hash reference to the body in the surrounding context — with no
        // content fields and no binder. The implementer's reference trace is
        // 00 00 00 2F 58 21 <33-byte body hash>.
        val store = NodeStore()
        val body = store.add(Node.IntLit(42))
        val att = store.add(Node.Attempt(body = body))
        val encoder = newEncoder(store)

        // The body IntLit 42 encodes as [tag 1][int(42)] = 00 00 00 01 18 2a,
        // whose multihash is corpus program 01's golden root hash.
        val bodyEncoding = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x18, 0x2a)
        assertArrayEquals(bodyEncoding, encoder.encode(body))
        val bodyHash = multihash(bodyEncoding)
        assertEquals(
            "1e6970ba0a8f8e82923c82d5b40927ba0ba9d0dcfc526c3308e509a28e9f16caad",
            hex(bodyHash),
        )

        // Attempt: [tag 47 = 00 00 00 2f][H(body)]. Tag 47 is 0x2f; the hash
        // reference is the byte-string header 0x58 0x21 then the 33 hash bytes.
        val expected = tag(47) + hashRef(bodyHash)
        val actual = encoder.encode(att)
        assertArrayEquals(expected, actual) { "Attempt encoding: ${hex(actual)}" }

        // Exactly 4 tag + 2 CBOR header + 33 hash = 39 bytes, beginning 00 00 00 2f.
        assertEquals(4 + 2 + 33, actual.size)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x2f), actual.copyOfRange(0, 4))
    }
}
