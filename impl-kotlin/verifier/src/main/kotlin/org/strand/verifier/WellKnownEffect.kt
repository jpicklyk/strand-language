package org.strand.verifier

/**
 * Registry of EffectCategory names that the verifier treats as
 * semantically meaningful — not just opaque NodeId-identified effects.
 *
 * The runtime exercises certain implicit effects on the user's behalf:
 * a StateMachine actor performs [StateMachineReceive] every time it
 * pulls an event from an input channel and [StateMachineSend] every
 * time it pushes an event onto an output channel. The user's transition
 * function never calls these directly, but the StateMachine declaration
 * itself acknowledges that the runtime will exercise them. This
 * registry is how the verifier looks up "is this EffectCategory the
 * Receive one?" by walking the [Node.EffectCategory.categoryName]
 * string identifier.
 *
 * Identity is by [categoryName] only — the user's EffectCategory NodeId
 * is irrelevant; what matters is that *some* EffectCategory in the
 * machine's `effects` list carries the well-known name. The verifier's
 * [WellKnownEffectRegistry.lookup] performs the dispatch.
 *
 * This registry is verifier-internal. User code never references the
 * enum directly; it just declares an EffectCategory with the matching
 * name. New entries are added when a future runtime mechanism needs
 * verifier-side acknowledgement of an implicit effect (e.g., snapshot
 * capture, supervisor spawn, foreign-binding initialization).
 */
internal enum class WellKnownEffect(val categoryName: String) {
    StateMachineReceive("StateMachine.Receive"),
    StateMachineSend("StateMachine.Send"),
    StateMachineSpawn("StateMachine.Spawn"),
    StateMachineTerminate("StateMachine.Terminate"),
}

/**
 * Helper for walking a list of EffectCategory NodeIds (e.g., a
 * StateMachine's declared `effects`) to find which well-known names
 * are present.
 */
internal object WellKnownEffectRegistry {

    private val byName: Map<String, WellKnownEffect> =
        WellKnownEffect.entries.associateBy { it.categoryName }

    /** Look up a well-known effect by its [Node.EffectCategory.categoryName]; null if unknown. */
    fun lookup(categoryName: String): WellKnownEffect? = byName[categoryName]
}
