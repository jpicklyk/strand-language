package org.strand.core

/**
 * A multi-hash digest as specified in ADR-003: a one-byte function-identifier
 * prefix followed by the hash bytes. Strand identifies every committed graph
 * node by a [Hash]; references between committed nodes carry the full prefixed
 * digest.
 *
 * Equality is by byte content. Two [Hash] instances with identical bytes are
 * equal regardless of which function produced them, which is the property
 * downstream code (deduplication, store lookup) depends on.
 *
 * The default function in the reference implementation is BLAKE3
 * ([HashFunction.Blake3]); SHA-256 is reserved via a different prefix per
 * ADR-003 but not implemented in Layer 2 step 1.
 *
 * Lives in [org.strand.core] (not in the `:hashing` module) so that
 * [Node.NodeRef.target] — the only edge type that crosses a content-addressing
 * boundary — can carry a [Hash] without inverting the module dependency
 * direction (`:hashing` depends on `:core`, not the other way around).
 */
class Hash(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "Hash bytes must not be empty" }
        // Validate the prefix corresponds to a known function and that the
        // digest length matches that function's specification.
        val fn = HashFunction.fromPrefix(bytes[0])
        require(bytes.size == 1 + fn.digestSize) {
            "Hash with prefix 0x%02x must carry %d digest bytes; got %d".format(
                bytes[0], fn.digestSize, bytes.size - 1
            )
        }
    }

    /** The hash function that produced this digest, recovered from the prefix byte. */
    val function: HashFunction get() = HashFunction.fromPrefix(bytes[0])

    /** The digest bytes, excluding the one-byte function-identifier prefix. */
    val digest: ByteArray get() = bytes.copyOfRange(1, bytes.size)

    override fun equals(other: Any?): Boolean =
        other is Hash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Lowercase hex of the full multi-hash (prefix + digest). */
    override fun toString(): String =
        bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The supported hash functions, identified by their one-byte multi-hash prefix
 * per ADR-003. Prefix values follow the IETF multicodec assignments: BLAKE3 is
 * 0x1e.
 */
enum class HashFunction(val prefix: Byte, val digestSize: Int) {
    Blake3(0x1e.toByte(), 32);

    companion object {
        fun fromPrefix(prefix: Byte): HashFunction =
            entries.firstOrNull { it.prefix == prefix }
                ?: error("Unknown hash function prefix: 0x%02x".format(prefix))
    }
}
