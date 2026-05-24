package org.strand.core

/**
 * Opaque identifier for a node within a [NodeStore].
 *
 * In Layer 1, ids are sequential integers assigned at insertion time. Layer 2
 * will replace this with a content-addressed hash (BLAKE3 over canonical CBOR).
 * Callers must treat [NodeId] as opaque; do not depend on integer ordering for
 * semantic decisions.
 */
@JvmInline
value class NodeId(val value: Int) {
    override fun toString(): String = "#$value"
}
