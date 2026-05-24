package org.strand.core

/**
 * Intermediate representation used during ingest, before the canonical
 * content-addressed form is computed. Lives between `JsonIngest.parse` and
 * `Hasher.finalize` (Layer 2 step 2).
 *
 * - [Canonical] wraps a fully-materialized [Node]. The node's child edges are
 *   [NodeId]s in the originating [RawNodeStore]. After finalization these
 *   nodes are admitted to a [NodeStore] unchanged.
 *
 * - [RawNodeRef] is the pre-finalization form of [Node.NodeRef]. The canonical
 *   form carries `target: Hash`, but ingest only knows the target's [NodeId]
 *   (its in-document position) — the target's hash is computed by the hashing
 *   module once the full graph is known. [Hasher.finalize] replaces every
 *   [RawNodeRef] entry with `Node.NodeRef(target = <computed hash>)`.
 *
 * Downstream modules (verifier, interpreter) only ever see canonical
 * [NodeStore] entries; the raw form is internal to the ingest+finalize seam.
 */
sealed class StoredNode {
    data class Canonical(val node: Node) : StoredNode()
    data class RawNodeRef(val targetId: NodeId) : StoredNode()
}
