package org.strand.core

/**
 * Enumerates every internal [NodeId] child of [node]: the outgoing edges
 * that reference other nodes within the same store. A [Node.NodeRef] has
 * no [NodeId] children — its `target` is a [Hash] and represents a
 * boundary to another subgraph.
 *
 * Used by [org.strand.hashing.LocalHashStoreResolver] (Q-043 step 3a)
 * to walk a self-contained subgraph from a requested root, and is also
 * the basis for the symmetric [translateNodeIds] rewrite the
 * federation admission protocol uses to re-base foreign NodeIds.
 *
 * The order is the natural left-to-right declaration order of each
 * Node's fields; the caller may treat it as a stable traversal sequence
 * for diagnostic purposes but the federation walk treats it as a set.
 *
 * Layer 1 + 2 cover N-001..N-019 plus the bound nodes N-034/N-035
 * (TypeAbstraction / ForallType). Layer 3 effects, Layer 4 ForeignNode,
 * Layer 5 Match/Fixpoint/composite values, Layer 6 state machines,
 * Layer 7 Schema/Invariant, and the agent-native ToolDef/
 * ResponseSchemaSpec nodes all have explicit cases below.
 */
fun Node.childNodeIds(): List<NodeId> = when (this) {
    // ----- Layer 1: literals and primitive types — no children -----
    is Node.IntLit, is Node.FloatLit, is Node.StringLit,
    is Node.BoolLit, Node.UnitLit, is Node.BytesLit,
    is Node.PrimitiveType -> emptyList()

    // ----- Layer 1: types -----
    is Node.ProductType -> fields
    is Node.ProductTypeField -> listOf(fieldType)
    is Node.SumType -> cases
    is Node.SumTypeCase -> caseType?.let { listOf(it) } ?: emptyList()
    is Node.FunctionType -> buildList {
        addAll(parameters)
        add(result)
        addAll(effects)
        // Q-039: effectProjections may pin parameters to LiteralNode targets.
        // Those targets are NodeIds and are reachable through this node.
        for (proj in effectProjections) {
            for (src in proj.sources) {
                if (src is ProjectionSource.LiteralNode) add(src.target)
            }
        }
    }
    is Node.TypeParameter -> emptyList()  // intrinsic, no children
    is Node.ForallType -> listOf(body)    // typeParameters are intrinsic

    // ----- Layer 1: functions and binding -----
    is Node.Lambda -> buildList {
        addAll(parameters)
        add(body)
        addAll(effects)
    }
    is Node.TypeAbstraction -> listOf(body)  // typeParameters are intrinsic
    is Node.ParameterDecl -> listOf(paramType)
    is Node.Application -> buildList {
        add(function)
        addAll(arguments)
        addAll(typeArguments)
        addAll(effectInstances)
    }
    is Node.Let -> listOf(value, body)
    is Node.VarRef -> listOf(binder)  // binder is part of the subgraph

    // ----- Layer 2 step 2: NodeRef — BOUNDARY -----
    is Node.NodeRef -> emptyList()  // target is a Hash, not a NodeId; subgraph stops here

    // ----- Composition and distribution (N-046) -----
    // Each export's `target` is a Hash boundary (resolved lazily, like
    // NodeRef.target) and is NOT a NodeId child. Each export's declaredEffects
    // are EffectCategory NodeId edges, reachable through this manifest.
    is Node.ModuleManifest -> exports.flatMap { it.declaredEffects }

    // ----- Layer 3: effects and capabilities -----
    is Node.EffectCategory -> parameters
    is Node.EffectDecl -> buildList { add(effectType); addAll(parameters) }
    is Node.CapabilityScope -> buildList { addAll(capabilities); add(body) }
    is Node.Handler -> listOf(intercept, handle, body)

    // ----- Layer 4: foreign nodes -----
    is Node.ForeignNode -> buildList {
        add(foreignType)
        addAll(effects)
        for (proj in effectProjections) {
            for (src in proj.sources) {
                if (src is ProjectionSource.LiteralNode) add(src.target)
            }
        }
    }

    // ----- Layer 5: control flow -----
    is Node.Match -> buildList { add(scrutinee); addAll(cases) }
    is Node.MatchCase -> listOf(pattern, body)
    is Node.Pattern -> when (this) {
        is Node.Pattern.LiteralPattern -> listOf(patternType, literal)
        is Node.Pattern.VariablePattern -> listOf(patternType)
        is Node.Pattern.WildcardPattern -> listOf(patternType)
        is Node.Pattern.ConstructorPattern -> buildList {
            add(patternType)
            payloadPattern?.let { add(it) }
        }
    }
    is Node.Fixpoint -> listOf(recursionType, body)

    // ----- Layer 5: composite values -----
    is Node.ProductValue -> buildList { add(ofType); addAll(fields) }
    is Node.ProductFieldValue -> listOf(value)
    is Node.ProductFieldGet -> listOf(target)
    is Node.SumValue -> buildList {
        add(ofType)
        payload?.let { add(it) }
    }

    // ----- Recursive types -----
    is Node.RecursiveType -> listOf(body)
    is Node.RecursiveSelf -> emptyList()  // intrinsic, no NodeId children

    // ----- Layer 6: state machines -----
    is Node.StateMachine -> buildList {
        add(transitionFn)
        add(initialState)
        addAll(inputStreams)
        addAll(outputStreams)
        addAll(effects)
    }
    is Node.EventStream -> listOf(eventType)
    is Node.Transition -> buildList {
        guard?.let { add(it) }
        add(body)
    }

    // ----- Layer 7: Schema and Invariant -----
    is Node.Schema -> buildList { add(valueType); addAll(invariants) }
    is Node.Invariant -> listOf(body)  // targetSchema is metadata-excluded from encoding;
                                       // intentionally NOT walked here to avoid Schema↔Invariant cycle
                                       // (matches Hasher.walkChildren precedent)

    // ----- Agent-native capabilities -----
    is Node.ToolDef -> listOf(parameterSchema, implementation)
    is Node.ResponseSchemaSpec -> listOf(schema)
}
