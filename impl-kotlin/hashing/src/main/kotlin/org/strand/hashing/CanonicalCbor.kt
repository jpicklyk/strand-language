package org.strand.hashing

/**
 * The subset of canonical CBOR (RFC 8949 § 4.2.1) used by Strand's canonical
 * node encoding. Only three CBOR major types appear in Strand's encodings:
 *
 *   * Unsigned integer (major type 0) — used for category-tag fields, list
 *     lengths, de Bruijn `(depth, index)` pairs, and the unsigned form of
 *     positive [Long] literal values.
 *   * Negative integer (major type 1) — used for negative [Long] literal
 *     values.
 *   * Byte string (major type 2) — used for hash references (multi-hash
 *     bytes), UTF-8 byte sequences (StringLit, ProductTypeField.fieldName,
 *     SumTypeCase.caseName), arbitrary bytes (BytesLit), and the raw 8-byte
 *     IEEE 754 image of a FloatLit value.
 *   * Array (major type 4) — used for ordered child lists (Lambda parameter
 *     types, Application arguments and type arguments, ProductType fields
 *     sorted by name, SumType cases sorted by name).
 *
 * Canonical-form constraints:
 *   1. Definite-length encoding always (no indefinite length).
 *   2. Shortest argument encoding for every integer and length (no
 *      [0x18, 0x05] for the value 5; just [0x05]).
 *
 * The encoder is byte-deterministic by construction. Two implementations
 * compiled from this same logic produce byte-identical output for byte-
 * identical input.
 */
internal object CanonicalCbor {

    /** Encode a non-negative integer as canonical CBOR major type 0. */
    fun encodeUint(value: Long): ByteArray {
        require(value >= 0L) { "encodeUint requires a non-negative value; got $value" }
        return encodeTypeAndArgument(MAJOR_UNSIGNED, value)
    }

    /**
     * Encode a signed integer. Non-negative values use major type 0; negative
     * values use major type 1 with argument `-1 - value`, which is positive
     * for every representable negative [Long] including [Long.MIN_VALUE].
     */
    fun encodeInt(value: Long): ByteArray =
        if (value >= 0L) {
            encodeTypeAndArgument(MAJOR_UNSIGNED, value)
        } else {
            // -1 - Long.MIN_VALUE == Long.MAX_VALUE, which fits in an unsigned 64-bit
            // argument — no overflow at the extreme.
            encodeTypeAndArgument(MAJOR_NEGATIVE, -1L - value)
        }

    /** Encode a byte string as canonical CBOR major type 2. */
    fun encodeBytes(bytes: ByteArray): ByteArray {
        val header = encodeTypeAndArgument(MAJOR_BYTES, bytes.size.toLong())
        val out = ByteArray(header.size + bytes.size)
        header.copyInto(out, 0)
        bytes.copyInto(out, header.size)
        return out
    }

    /**
     * Encode an array of already-encoded CBOR items as canonical CBOR major
     * type 4. The caller is responsible for the canonical encoding of each
     * element; this routine only emits the array header and concatenates.
     */
    fun encodeArray(items: List<ByteArray>): ByteArray {
        val header = encodeTypeAndArgument(MAJOR_ARRAY, items.size.toLong())
        val totalSize = header.size + items.sumOf { it.size }
        val out = ByteArray(totalSize)
        header.copyInto(out, 0)
        var pos = header.size
        for (item in items) {
            item.copyInto(out, pos)
            pos += item.size
        }
        return out
    }

    /**
     * Emit a (major type, argument) header in canonical shortest form. The
     * argument is treated as an unsigned 64-bit value; callers that need
     * negative-integer semantics pass `-1 - value` and the appropriate major
     * type.
     */
    private fun encodeTypeAndArgument(majorType: Int, argument: Long): ByteArray {
        // Negative Long values are reinterpreted as unsigned 64-bit. The
        // ordering comparisons here use Long.compareUnsigned to be safe.
        return when {
            argument in 0L..23L ->
                byteArrayOf((majorType or argument.toInt()).toByte())
            java.lang.Long.compareUnsigned(argument, 256L) < 0 ->
                byteArrayOf(
                    (majorType or 24).toByte(),
                    argument.toByte(),
                )
            java.lang.Long.compareUnsigned(argument, 65536L) < 0 ->
                byteArrayOf(
                    (majorType or 25).toByte(),
                    ((argument ushr 8) and 0xFF).toByte(),
                    (argument and 0xFF).toByte(),
                )
            java.lang.Long.compareUnsigned(argument, 0x1_0000_0000L) < 0 ->
                byteArrayOf(
                    (majorType or 26).toByte(),
                    ((argument ushr 24) and 0xFF).toByte(),
                    ((argument ushr 16) and 0xFF).toByte(),
                    ((argument ushr 8) and 0xFF).toByte(),
                    (argument and 0xFF).toByte(),
                )
            else ->
                byteArrayOf(
                    (majorType or 27).toByte(),
                    ((argument ushr 56) and 0xFF).toByte(),
                    ((argument ushr 48) and 0xFF).toByte(),
                    ((argument ushr 40) and 0xFF).toByte(),
                    ((argument ushr 32) and 0xFF).toByte(),
                    ((argument ushr 24) and 0xFF).toByte(),
                    ((argument ushr 16) and 0xFF).toByte(),
                    ((argument ushr 8) and 0xFF).toByte(),
                    (argument and 0xFF).toByte(),
                )
        }
    }

    private const val MAJOR_UNSIGNED = 0x00
    private const val MAJOR_NEGATIVE = 0x20
    private const val MAJOR_BYTES = 0x40
    private const val MAJOR_ARRAY = 0x80
}
