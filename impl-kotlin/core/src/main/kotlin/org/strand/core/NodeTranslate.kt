package org.strand.core

/**
 * Returns a copy of [node] with every internal [NodeId] reference rewritten by
 * [fn]. This is the structural dual of [childNodeIds]: where `childNodeIds`
 * *enumerates* a node's outgoing NodeId edges, `translateNodeIds` *rewrites*
 * them, producing a structurally-identical node in a different NodeId space.
 *
 * Used by the Layer 2 step 3 federation admission protocol
 * ([org.strand.hashing.FederatedProgram.fetchAndAdmit]) to re-base a node
 * fetched from a foreign store: each foreign NodeId reference is mapped to its
 * locally-assigned NodeId. Because the canonical encoding is positional at
 * binder sites, a faithful re-base produces a node whose canonical hash equals
 * the foreign node's hash — which is exactly the post-admission integrity
 * check the admission protocol performs.
 *
 * **Coverage differs from [childNodeIds] in three places**, because
 * translation must rewrite *every* NodeId-typed field, not just the ones the
 * federation walk descends into:
 *
 *  - **Binder declarations** — [Node.ForallType.typeParameters] and
 *    [Node.TypeAbstraction.typeParameters] (the bound [Node.TypeParameter]
 *    NodeIds) are rewritten here even though `childNodeIds` omits them as
 *    "intrinsic". A vacuously-quantified type parameter (bound but never
 *    referenced) would otherwise be left dangling.
 *  - **[Node.TypeParameter.bound]** — the (currently unused, subtyping-gated)
 *    upper bound is rewritten if present.
 *  - **[EffectProjection.category]** and **[Node.Invariant.targetSchema]** —
 *    NodeId fields that `childNodeIds` omits (the former is redundant with the
 *    enclosing `effects`, the latter would form a Schema↔Invariant walk cycle)
 *    are rewritten here. The admission DFS breaks the resulting reference
 *    cycle by early-memoization, so rewriting `targetSchema` is safe.
 *
 * **[Node.NodeRef] is unchanged** — its `target` is a [Hash], not a NodeId, so
 * it is a subgraph boundary. **[Node.ModuleManifest] export targets are also
 * unchanged** — each `ManifestExport.target` is a [Hash] (a NodeRef-style
 * lazy boundary); only the per-export `declaredEffects` NodeIds are rewritten.
 *
 * Nodes with no NodeId fields (literals, [Node.PrimitiveType],
 * [Node.RecursiveSelf]) return themselves unchanged.
 */
fun Node.translateNodeIds(fn: (NodeId) -> NodeId): Node = when (this) {
    // ----- Layer 1: literals and primitive types — no NodeId fields -----
    is Node.IntLit, is Node.FloatLit, is Node.StringLit,
    is Node.BoolLit, Node.UnitLit, is Node.BytesLit,
    is Node.PrimitiveType -> this

    // ----- Layer 1: types -----
    is Node.ProductType -> copy(fields = fields.map(fn))
    is Node.ProductTypeField -> copy(fieldType = fn(fieldType))
    is Node.SumType -> copy(cases = cases.map(fn))
    is Node.SumTypeCase -> copy(caseType = caseType?.let(fn))
    is Node.FunctionType -> copy(
        parameters = parameters.map(fn),
        result = fn(result),
        effects = effects.map(fn),
        effectProjections = effectProjections.map { it.translateNodeIds(fn) },
    )
    is Node.TypeParameter -> copy(bound = bound?.let(fn))  // name is metadata
    is Node.ForallType -> copy(typeParameters = typeParameters.map(fn), body = fn(body))

    // ----- Layer 1: functions and binding -----
    is Node.Lambda -> copy(
        parameters = parameters.map(fn),
        body = fn(body),
        effects = effects.map(fn),
    )
    is Node.TypeAbstraction -> copy(typeParameters = typeParameters.map(fn), body = fn(body))
    is Node.ParameterDecl -> copy(paramType = fn(paramType))  // name is metadata
    is Node.Application -> copy(
        function = fn(function),
        arguments = arguments.map(fn),
        typeArguments = typeArguments.map(fn),
        effectInstances = effectInstances.map(fn),
    )
    is Node.Let -> copy(value = fn(value), body = fn(body))  // name is metadata
    is Node.VarRef -> copy(binder = fn(binder))

    // ----- Layer 2 step 2: NodeRef — BOUNDARY (target is a Hash) -----
    is Node.NodeRef -> this

    // ----- Layer 4: foreign nodes -----
    is Node.ForeignNode -> copy(  // target is a String binding id, not a NodeId
        foreignType = fn(foreignType),
        effects = effects.map(fn),
        effectProjections = effectProjections.map { it.translateNodeIds(fn) },
    )

    // ----- Layer 3: effects and capabilities -----
    is Node.EffectCategory -> copy(parameters = parameters.map(fn))  // categoryName is metadata
    is Node.EffectDecl -> copy(effectType = fn(effectType), parameters = parameters.map(fn))
    is Node.CapabilityScope -> copy(capabilities = capabilities.map(fn), body = fn(body))
    is Node.Handler -> copy(intercept = fn(intercept), handle = fn(handle), body = fn(body))

    // ----- Layer 5: control flow -----
    is Node.Match -> copy(scrutinee = fn(scrutinee), cases = cases.map(fn))
    is Node.MatchCase -> copy(pattern = fn(pattern), body = fn(body))
    is Node.Pattern -> when (this) {
        is Node.Pattern.LiteralPattern -> copy(patternType = fn(patternType), literal = fn(literal))
        is Node.Pattern.VariablePattern -> copy(patternType = fn(patternType))  // name is metadata
        is Node.Pattern.WildcardPattern -> copy(patternType = fn(patternType))
        is Node.Pattern.ConstructorPattern -> copy(
            patternType = fn(patternType),
            payloadPattern = payloadPattern?.let(fn),  // caseName is metadata
        )
    }
    is Node.Fixpoint -> copy(recursionType = fn(recursionType), body = fn(body))

    // ----- Error recovery (N-047) -----
    is Node.Attempt -> copy(body = fn(body))

    // ----- Layer 5: composite values -----
    is Node.ProductValue -> copy(ofType = fn(ofType), fields = fields.map(fn))
    is Node.ProductFieldValue -> copy(value = fn(value))  // fieldName is metadata
    is Node.ProductFieldGet -> copy(target = fn(target))  // fieldName is metadata
    is Node.SumValue -> copy(ofType = fn(ofType), payload = payload?.let(fn))  // caseName is metadata

    // ----- Recursive types -----
    is Node.RecursiveType -> copy(body = fn(body))
    is Node.RecursiveSelf -> this  // depth is an Int, no NodeId field
    // N-048: only `recursiveType` is a NodeId; `path` steps carry structural
    // identifiers (caseName / fieldName) or nothing, no NodeIds to rewrite.
    is Node.RecursiveProjection -> copy(recursiveType = fn(recursiveType))

    // ----- Layer 6: state machines -----
    is Node.StateMachine -> copy(
        transitionFn = fn(transitionFn),
        initialState = fn(initialState),
        inputStreams = inputStreams.map(fn),
        outputStreams = outputStreams.map(fn),
        effects = effects.map(fn),
    )
    is Node.EventStream -> copy(  // streamKind/buffer/policy/mode are not NodeIds
        eventType = fn(eventType),
        source = source?.let(fn),  // Q-046: the IO-opening node backing this stream
    )
    is Node.Transition -> copy(guard = guard?.let(fn), body = fn(body))

    // ----- Layer 7: Schema and Invariant -----
    is Node.Schema -> copy(valueType = fn(valueType), invariants = invariants.map(fn))
    // targetSchema is rewritten here (childNodeIds omits it to avoid a walk
    // cycle); the admission DFS breaks the Schema↔Invariant reference cycle by
    // early-memoization, so the rewrite resolves to the local Schema id.
    is Node.Invariant -> copy(targetSchema = fn(targetSchema), body = fn(body))

    // ----- Agent-native capabilities -----
    is Node.ToolDef -> copy(parameterSchema = fn(parameterSchema), implementation = fn(implementation))
    is Node.ResponseSchemaSpec -> copy(schema = fn(schema))

    // ----- Composition and distribution (N-046) -----
    // export.target is a Hash boundary (unchanged); declaredEffects are
    // EffectCategory NodeId edges (rewritten). manifestSignature is metadata.
    is Node.ModuleManifest -> copy(
        exports = exports.map { it.copy(declaredEffects = it.declaredEffects.map(fn)) },
    )
}

/** Rewrite the NodeId references inside one [EffectProjection]. */
private fun EffectProjection.translateNodeIds(fn: (NodeId) -> NodeId): EffectProjection =
    EffectProjection(
        category = fn(category),
        sources = sources.map { src ->
            when (src) {
                is ProjectionSource.ArgRef -> src  // index is an Int, no NodeId
                is ProjectionSource.LiteralNode -> ProjectionSource.LiteralNode(fn(src.target))
            }
        },
    )
