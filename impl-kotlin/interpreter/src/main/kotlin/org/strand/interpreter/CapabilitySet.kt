package org.strand.interpreter

import org.strand.core.NodeId

/**
 * One argument slot of a [CapabilityPattern]. A wildcard slot accepts any
 * runtime value; a concrete slot only accepts a value equal to the supplied
 * one. Strand's per-EffectCategory parameter-types determine the kind of
 * value each slot carries (a `Filesystem.Write{path: String}` capability
 * has one String slot; `Network.Connect{host: String, port: Int}` has two).
 *
 * Values are compared by Strand's existing [Value] equality:
 *   * primitives — value equality
 *   * Bytes — `contentEquals`
 *   * structured (Product/Sum) — structural equality
 *   * Closure/ForeignFn/FixpointFn — never appear as effect parameters
 *     (the verifier rejects function/Forall parameter types at the
 *     EffectCategory declaration level)
 *
 * Sub-string / glob wildcards on String slots (e.g. `host: "*.example.com"`)
 * are deliberately out of scope for Q-031; see § Tradeoffs and open
 * questions in the proposal. Any extension would be a new
 * [CapabilityArgument] variant rather than a re-interpretation of
 * [Wildcard] or [Concrete].
 */
sealed class CapabilityArgument {
    /** A wildcard slot: any value of the slot's declared type satisfies it. */
    object Wildcard : CapabilityArgument()

    /** A concrete slot: only this exact runtime [value] satisfies it. */
    data class Concrete(val value: Value) : CapabilityArgument()
}

/**
 * One pattern for one [EffectCategory][org.strand.core.Node.EffectCategory]:
 * a positional list of [CapabilityArgument]s, one per declared category
 * parameter. The length is fixed by the category's parameter arity; the
 * runtime enforces equal lengths in [CapabilitySet.covers] (where a
 * mismatch is a verifier-prevented condition rather than a user-facing
 * error, since the verifier statically enforces EffectDecl arity).
 *
 * A parameterless category (e.g. `Time.Now`) is represented by an
 * empty-argument pattern, which trivially covers an empty requirement.
 */
data class CapabilityPattern(
    val arguments: List<CapabilityArgument>,
)

/**
 * Runtime capability context for the Strand interpreter (Layer 3 step 2).
 *
 * Pre-Q-031 the capability context was `Set<NodeId>` over EffectCategory
 * NodeIds — matching was set-membership. The new shape is keyed by
 * EffectCategory and, per category, holds a list of [CapabilityPattern]s.
 * A required effect is covered iff any pattern for its category covers
 * the requirement; § 5 of the proposal pins the algorithm.
 *
 * The grants map is keyed by EffectCategory NodeId rather than flattened
 * into one big list of patterns because category lookup is the dominant
 * filter in practice ("grant Filesystem.Write on these paths") and a flat
 * list would be O(|all capabilities|) per check.
 *
 * Capabilities are never graph nodes; they exist only at runtime, granted
 * by the host. This type is the host's API for constructing them. The
 * [ofCategories] back-compat constructor produces a CapabilitySet whose
 * grants are wildcards-everywhere — every pattern accepts every value of
 * the slot's type — which restores the pre-Q-031 set-membership semantics
 * exactly. Every existing call site that hands a `Set<NodeId>` keeps
 * working via [Interpreter.eval]'s back-compat wrapper.
 */
data class CapabilitySet(
    val grants: Map<NodeId, List<CapabilityPattern>>,
) {
    fun isEmpty(): Boolean = grants.isEmpty()

    /**
     * Narrow this capability set to retain only the EffectCategory NodeIds
     * in [categories]. Per-category pattern lists are preserved verbatim
     * for retained categories — narrowing intersects categories without
     * changing the granted refinement detail. This matches the language-
     * level [Node.CapabilityScope][org.strand.core.Node.CapabilityScope]
     * semantics: a scope narrows which categories survive but does not
     * tighten the parameter constraints on retained ones.
     *
     * A future "refinement-narrowing CapabilityScope" extension (Q-031
     * § Tradeoffs and open questions) would tighten patterns within a
     * retained category; that work is deferred behind its own question.
     */
    fun intersect(categories: Set<NodeId>): CapabilitySet =
        CapabilitySet(grants.filterKeys { it in categories })

    /**
     * Does any pattern granted for [category] cover the [requirement] (the
     * evaluated EffectDecl parameter values for this call site)? Returns
     * null when the category is absent entirely (caller raises
     * [InterpretError.CapabilityViolation]); returns the matching pattern
     * if one covers, else false (caller raises
     * [InterpretError.RefinementViolation]).
     */
    fun findCovering(category: NodeId, requirement: List<Value>): CapabilityPattern? {
        val patterns = grants[category] ?: return null
        return patterns.firstOrNull { covers(it, requirement) }
    }

    companion object {
        val EMPTY: CapabilitySet = CapabilitySet(emptyMap())

        /**
         * Back-compat constructor: build a [CapabilitySet] from a flat
         * EffectCategory-NodeId set. Every category maps to a single
         * fully-wildcard pattern with one wildcard per declared parameter
         * — except we don't know the arity here, so we use a single
         * pattern with an empty argument list that the covers() check
         * treats specially: when the granted pattern is empty and the
         * requirement is also empty, it matches; for non-empty
         * requirements we need a wildcard-of-the-right-arity. To keep
         * back-compat working without category-arity lookup we use a
         * sentinel [CapabilityPattern] whose only argument is
         * [CapabilityArgument.Wildcard]; the covers() check special-cases
         * a single-Wildcard pattern as "matches anything" regardless of
         * requirement arity. See [covers] for the special-case logic.
         */
        fun ofCategories(cats: Set<NodeId>): CapabilitySet {
            val pattern = CapabilityPattern(listOf(CapabilityArgument.Wildcard))
            return CapabilitySet(cats.associateWith { listOf(pattern) })
        }
    }
}

/**
 * Does [capability] cover the [requirement] (a list of evaluated EffectDecl
 * parameter values)? Per § 5 of the proposal:
 *
 *   * Length mismatch → false (verifier-prevented in practice for
 *     statically-supplied EffectDecls; the back-compat single-Wildcard
 *     pattern is the documented exception).
 *   * Position-wise: Wildcard slot matches any value; Concrete slot matches
 *     only if Strand's [Value] equality says so.
 *
 * Back-compat special case: [CapabilitySet.ofCategories] produces patterns
 * whose `arguments == listOf(Wildcard)`. Such a pattern matches a
 * requirement of any arity — it is the "unrefined capability" sentinel
 * for pre-Q-031 callers. This is the seam that lets existing test code
 * (`Set<NodeId>` → `CapabilitySet.ofCategories`) keep working against the
 * new runtime check.
 */
fun covers(capability: CapabilityPattern, requirement: List<Value>): Boolean {
    // Back-compat wildcard sentinel: a one-slot wildcard pattern from
    // CapabilitySet.ofCategories matches a requirement of any arity.
    val args = capability.arguments
    if (args.size == 1 && args[0] is CapabilityArgument.Wildcard) {
        return true
    }
    if (args.size != requirement.size) return false
    for (i in args.indices) {
        if (!coversArg(args[i], requirement[i])) return false
    }
    return true
}

/**
 * One slot of the [covers] check: a wildcard matches any value; a
 * concrete slot matches by [Value] equality.
 */
fun coversArg(arg: CapabilityArgument, req: Value): Boolean = when (arg) {
    CapabilityArgument.Wildcard -> true
    is CapabilityArgument.Concrete -> arg.value == req
}
