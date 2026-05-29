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
 * - [RawModuleManifest] is the pre-finalization form of [Node.ModuleManifest]
 *   (N-046). Each export's `target` is a NodeRef-style content hash in the
 *   canonical form, but ingest only knows the target's in-document [NodeId];
 *   [Hasher.finalize] resolves every [RawManifestExport.target] NodeId to its
 *   hash and admits the canonical [Node.ModuleManifest]. Same bridge as
 *   [RawNodeRef], generalized to a node that embeds multiple hash targets
 *   alongside non-target content (the `declaredEffects` NodeId edges, which
 *   are not bridged — they stay NodeIds in both forms).
 *
 * Downstream modules (verifier, interpreter) only ever see canonical
 * [NodeStore] entries; the raw form is internal to the ingest+finalize seam.
 */
sealed class StoredNode {
    data class Canonical(val node: Node) : StoredNode()
    data class RawNodeRef(val targetId: NodeId) : StoredNode()
    data class RawModuleManifest(
        val exports: List<RawManifestExport>,
        val manifestSignature: ByteArray? = null,
    ) : StoredNode() {
        // ByteArray needs content-based value semantics (matches BytesLit /
        // Node.ModuleManifest).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawModuleManifest) return false
            if (exports != other.exports) return false
            val sig = manifestSignature
            val otherSig = other.manifestSignature
            return when {
                sig == null -> otherSig == null
                otherSig == null -> false
                else -> sig.contentEquals(otherSig)
            }
        }

        override fun hashCode(): Int =
            31 * exports.hashCode() + (manifestSignature?.contentHashCode() ?: 0)
    }
}

/**
 * The pre-finalization form of one [ManifestExport]: identical except that
 * [target] is the export's in-document [NodeId] rather than its content
 * [org.strand.core.Hash]. [Hasher.finalize] resolves [target] to a hash to
 * produce the canonical [ManifestExport].
 */
data class RawManifestExport(
    val target: NodeId,
    val declaredEffects: List<NodeId>,   // each an EffectCategory
    val displayName: String,             // metadata, hash-excluded
)
