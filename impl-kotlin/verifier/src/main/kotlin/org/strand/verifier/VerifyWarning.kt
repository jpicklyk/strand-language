package org.strand.verifier

import org.strand.core.NodeId

/**
 * Informational diagnostics attached to a successful verification via
 * [VerifyResult.Ok.warnings]. Warnings never fail verification — they
 * surface conditions that are legal but very likely unintended, so an
 * agent (whose only success signal is "verified") still learns about
 * them. This channel is parallel to, and independent of,
 * [VerifyResult.Ok.deferredChecks] (which is specifically the Layer 7
 * schema-deferral channel); future warning kinds extend this hierarchy.
 */
sealed class VerifyWarning {

    /**
     * A node was admitted to the store but is not reachable from any
     * verification root, so it was never type-checked and can never
     * execute. The author most likely declared it and forgot to wire it
     * into the program — the program "verifies" while silently missing
     * intended behavior.
     *
     * The reachability roots are: the program root; every
     * [org.strand.core.Node.ModuleManifest] in the store plus its export
     * targets and declared effects (manifests are deliberately allowed to
     * sit alongside the program they document — see
     * `Verifier.checkManifests`); every
     * [org.strand.core.Node.StateMachine] in the store (the group runtime
     * drives all machines in the store, not only root-reachable ones);
     * and every [org.strand.core.Node.EffectCategory] in the store (the
     * grant vocabulary host-side capability policy matches against — a
     * category may exist only to name a capability the program does not
     * exercise). Edges are every NodeId-typed field (including binder
     * declarations and metadata edges such as `Invariant.targetSchema`),
     * plus NodeRef / manifest-export Hash targets resolved through the
     * program's hash-to-NodeId map.
     *
     * [at] is the unreachable node; [nodeTypeName] its category name for
     * diagnostics.
     */
    data class UnreachableNode(
        val at: NodeId,
        val nodeTypeName: String,
    ) : VerifyWarning() {
        override fun toString(): String =
            "UnreachableNode(at=$at, nodeTypeName=$nodeTypeName): node is declared but not " +
                "reachable from any verification root (program root, ModuleManifest, " +
                "StateMachine, EffectCategory); wire it into the program or remove it"
    }
}
