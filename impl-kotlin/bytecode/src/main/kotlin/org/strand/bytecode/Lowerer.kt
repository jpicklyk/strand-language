package org.strand.bytecode

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore
import org.strand.verifier.TypeExpr

/**
 * Lowering pass: canonical [NodeStore] → [ChunkTable] (Q-017 step 1 § 4.1).
 *
 * The Lowerer walks the verified store starting from `root` and emits one
 * bytecode chunk for the root expression plus one sub-chunk per Lambda /
 * Fixpoint body encountered. Types and erased categories (ProductType,
 * SumType, TypeParameter, ForallType, ParameterDecl, EffectCategory,
 * EffectDecl, ProductTypeField, SumTypeCase, RecursiveType, RecursiveSelf,
 * Match-Case, Pattern, Schema, Invariant) are skipped at lowering time —
 * the verifier has already consumed them.
 *
 * **Slice 1 scope.** Layer 1 nodes plus TypeAbstraction (erased).
 * Specifically:
 *  * `IntLit`, `FloatLit`, `StringLit`, `BoolLit`, `UnitLit`, `BytesLit`
 *  * `Lambda`, `Application`, `Let`, `VarRef`
 *  * `TypeAbstraction` (body emitted, abstraction itself erased)
 *  * `NodeRef` (target hash resolved through the hashToNodeId reverse map
 *    at lowering time; the resolved sub-chunk index is embedded in the
 *    constant pool)
 *
 * Out of scope for this slice (will extend the lowering rules below as
 * their layers reach the VM):
 *  * Layer 3 (Capability scope, Handler, effect-bearing Application)
 *  * Layer 4 (ForeignNode dispatch)
 *  * Layer 5 (Match, Pattern, Fixpoint, Product/Sum values)
 *  * Layer 6 (StateMachine, EventStream — separately handled by the
 *    runtime, not by expression bytecode)
 *  * Layer 7 (Schema, Invariant — verifier-consumed)
 *
 * A node encountered at lowering time that isn't in slice 1's scope
 * throws [LoweringNotImplemented] with the node's category. This is the
 * "tests will tell us what's missing" mechanism — the corpus equivalence
 * test runs Layer 1 programs and reports anything that hits an unimplemented
 * node category.
 */
class Lowerer(
    private val store: NodeStore,
    private val hashToNodeId: Map<Hash, NodeId> = emptyMap(),
    /**
     * Q-043 step 3a cross-store resolution callback, consulted when a NodeRef
     * target hash is not in [hashToNodeId]. Lowering precedes execution, so a
     * cross-store target must be fetched and admitted into the shared [store]
     * *before* it can be lowered into its own sub-chunk. In a federated run the
     * caller wires this to `FederatedProgram::fetchAndAdmit`, which fetches the
     * target subgraph from a peer store, re-bases it into the shared [store],
     * extends the shared [hashToNodeId], and returns its local NodeId — which
     * the Lowerer then walks like any other local node. Default null: a NodeRef
     * miss is the original hard error (single-store behaviour preserved). A
     * federated caller must pass the same mutable [store] / [hashToNodeId] the
     * callback extends so the admitted target is visible to [lowerSubChunk].
     */
    private val resolveTarget: ((Hash) -> NodeId?)? = null,
    /**
     * Q-047: runtime schema obligations, as the verifier records them
     * (`VerifyResult.Ok.schemaObligations`) and the interpreter takes them.
     * For every lowered expression node that carries obligations, the
     * Lowerer emits `CHECK_SCHEMA` right after the node's own code, so the
     * VM checks the node's value each time the interpreter's `eval` of that
     * node returns — including every evaluation of a shared node and every
     * call of a body evaluated repeatedly. Each invariant's body expression
     * is lowered once into its own sub-chunk.
     *
     * Default empty: no `CHECK_SCHEMA` is emitted and the table is exactly
     * the one lowered without obligations.
     */
    private val schemaObligations: Map<NodeId, List<TypeExpr.SchemaType>> = emptyMap(),
) {
    private val chunks = mutableListOf<MutableChunk>()
    private val categoryNames = LinkedHashMap<Int, String>()
    private val categoryParamCounts = LinkedHashMap<Int, Int>()

    /**
     * Sub-chunk index per sub-chunk name (`lambda(#N)`, `fixpoint(#N)`,
     * `noderef(#N)`), so a Lambda / Fixpoint / NodeRef target shared by
     * several parents in the DAG is lowered once and its chunk reused
     * (review, Low). A closure's capture layout is a function of its own
     * body, so one sub-chunk serves every site that references the node.
     */
    private val subChunkByName = HashMap<String, Int>()

    /**
     * Lower the program rooted at [rootId] into a [ChunkTable]. The root
     * chunk is at index 0; sub-chunks (Lambda bodies, Fixpoint bodies)
     * follow in the order they're encountered. Reentrant: each call starts
     * from empty state, so lowering twice yields two equal tables.
     */
    fun lower(rootId: NodeId): ChunkTable {
        chunks.clear()
        categoryNames.clear()
        categoryParamCounts.clear()
        subChunkByName.clear()
        // Reserve the root chunk's slot so sub-chunks get index ≥ 1.
        val root = MutableChunk(name = "root($rootId)")
        chunks += root
        lowerExpr(rootId, root, scope = LocalScope())
        root.emit(Opcode.HALT)
        return ChunkTable(chunks.map { it.toChunk() }, categoryNames.toMap(), categoryParamCounts.toMap())
    }

    /**
     * Record [id]'s EffectCategory name for VM denial reports, and its
     * parameter count for the VM's unrefined-grant rule, and return its
     * integer id (review H2). Falls back to the `#N` rendering the
     * interpreter uses for a non-category id.
     */
    private fun category(id: NodeId): Int {
        if (id.value !in categoryNames) {
            val node = store.getOrNull(id) as? Node.EffectCategory
            categoryNames[id.value] = node?.categoryName ?: id.toString()
            if (node != null) categoryParamCounts[id.value] = node.parameters.size
        }
        return id.value
    }

    /**
     * The effect row of a [Node.ForeignNode]: its `foreignType` FunctionType's
     * effects unioned with its own `effects`, in the interpreter's
     * `foreignEffectRow` order. This is the row the verifier types the node
     * with, so an effect declared only on the FunctionType is still seen by
     * the VM's handler interception and capability check.
     */
    private fun foreignEffectRow(node: Node.ForeignNode): List<NodeId> {
        val typeEffects = (store.getOrNull(node.foreignType) as? Node.FunctionType)?.effects.orEmpty()
        if (typeEffects.isEmpty()) return node.effects
        return (typeEffects + node.effects).distinct()
    }

    private fun effectsConstant(ids: List<NodeId>): Constant.EffectsC =
        Constant.EffectsC(IntArray(ids.size) { category(ids[it]) })

    /** A Q-039 LiteralNode projection source lowered to its constant. */
    private fun literalConstant(id: NodeId): Constant = when (val n = store.get(id)) {
        is Node.IntLit -> Constant.IntC(n.value)
        is Node.FloatLit -> Constant.FloatC(n.value)
        is Node.StringLit -> Constant.StringC(n.value)
        is Node.BoolLit -> Constant.BoolC(n.value)
        is Node.UnitLit -> Constant.UnitC
        is Node.BytesLit -> Constant.BytesC(n.value)
        else -> throw LoweringNotImplemented(nodeId = id, category = "projection-literal:${n::class.simpleName}")
    }

    /**
     * Q-047: the [Constant.SchemaCheckC] for [nodeId]'s runtime schema
     * obligations, or null when it has none. Invariants are listed in the
     * interpreter's `checkSchemaObligations` order (obligations as recorded,
     * then each schema's invariants in declaration order), skipping an id
     * that is not an Invariant node as the interpreter does. Each invariant
     * body is lowered into a closed sub-chunk (the interpreter evaluates it
     * under an empty environment) that leaves the predicate callable on the
     * stack; the sub-chunk is shared by every site the invariant guards.
     */
    private fun schemaCheck(nodeId: NodeId): Constant.SchemaCheckC? {
        val obligations = schemaObligations[nodeId]
        if (obligations.isNullOrEmpty()) return null
        val checks = ArrayList<Constant.InvariantCheckC>()
        for (obligation in obligations) for (invariantId in obligation.invariants) {
            val invariant = store.getOrNull(invariantId) as? Node.Invariant ?: continue
            val chunkIndex = lowerSubChunk("invariant(${invariant.body})") { sub ->
                lowerExpr(invariant.body, sub, LocalScope())
                sub.emit(Opcode.RET)
            }
            checks += Constant.InvariantCheckC(obligation.schemaId.value, invariantId.value, chunkIndex)
        }
        return Constant.SchemaCheckC(nodeId.value, checks)
    }

    /**
     * Lower an expression-position node into [chunk]'s instruction stream.
     * The expression's value ends up on top of the operand stack when the
     * emitted instructions finish, followed by the node's `CHECK_SCHEMA`
     * when it carries runtime schema obligations ([schemaCheck]).
     */
    private fun lowerExpr(
        nodeId: NodeId,
        chunk: MutableChunk,
        scope: LocalScope,
    ) {
        lowerNode(nodeId, chunk, scope)
        val check = schemaCheck(nodeId) ?: return
        chunk.emit(Opcode.CHECK_SCHEMA, chunk.constant(check))
    }

    private fun lowerNode(
        nodeId: NodeId,
        chunk: MutableChunk,
        scope: LocalScope,
    ) {
        val node = store.get(nodeId)
        when (node) {
            // Literals.
            is Node.IntLit -> {
                val idx = chunk.constant(Constant.IntC(node.value))
                chunk.emit(Opcode.PUSH_INT, idx)
            }
            is Node.FloatLit -> {
                val idx = chunk.constant(Constant.FloatC(node.value))
                chunk.emit(Opcode.PUSH_FLOAT, idx)
            }
            is Node.StringLit -> {
                val idx = chunk.constant(Constant.StringC(node.value))
                chunk.emit(Opcode.PUSH_STRING, idx)
            }
            is Node.BoolLit -> {
                val idx = chunk.constant(Constant.BoolC(node.value))
                chunk.emit(Opcode.PUSH_BOOL, idx)
            }
            is Node.UnitLit -> {
                chunk.emit(Opcode.PUSH_UNIT)
            }
            is Node.BytesLit -> {
                val idx = chunk.constant(Constant.BytesC(node.value))
                chunk.emit(Opcode.PUSH_BYTES, idx)
            }

            // Variable references.
            is Node.VarRef -> {
                val slot = scope.lookup(node.binder)
                    ?: error("Lowerer: VarRef to unbound binder $nodeId.binder=${node.binder}")
                when (slot) {
                    is LocalSlot.Local -> chunk.emit(Opcode.LOAD_LOCAL, slot.index)
                    is LocalSlot.Capture -> chunk.emit(Opcode.LOAD_CAPTURE, slot.index)
                }
            }

            // Let — value emitted, stored in a fresh local slot, body emitted.
            is Node.Let -> {
                lowerExpr(node.value, chunk, scope)
                val slotIndex = chunk.allocLocal()
                chunk.emit(Opcode.STORE_LOCAL, slotIndex)
                val nestedScope = scope.bind(nodeId, LocalSlot.Local(slotIndex))
                lowerExpr(node.body, chunk, nestedScope)
            }

            // Lambda — emit body as a sub-chunk; emit MAKE_CLOSURE with the
            // sub-chunk's index. Captured locals from the surrounding scope
            // become the closure's captures.
            is Node.Lambda -> {
                lowerLambda(nodeId, node, chunk, scope)
            }

            // Application — push function, push args left-to-right, push
            // each EffectDecl's parameter values (review H2), CALL with the
            // arity and a CallSiteC describing the site and its instances.
            is Node.Application -> {
                lowerExpr(node.function, chunk, scope)
                for (argId in node.arguments) {
                    lowerExpr(argId, chunk, scope)
                }
                val categories = IntArray(node.effectInstances.size)
                val paramCounts = IntArray(node.effectInstances.size)
                for ((i, declId) in node.effectInstances.withIndex()) {
                    val decl = store.get(declId) as? Node.EffectDecl
                        ?: error("Lowerer: Application $nodeId effectInstance $declId is not an EffectDecl")
                    categories[i] = category(decl.effectType)
                    paramCounts[i] = decl.parameters.size
                    for (paramId in decl.parameters) lowerExpr(paramId, chunk, scope)
                }
                val siteIdx = chunk.constant(Constant.CallSiteC(nodeId.value, categories, paramCounts))
                chunk.emit(Opcode.CALL, node.arguments.size, siteIdx)
            }

            // NodeRef — resolved to its target chunk via hashToNodeId. The
            // target must itself be a closed expression (verifier rule
            // NodeRefTargetMustBeClosed guarantees this), so it's lowered
            // as its own sub-chunk and we emit LOAD_HASH with that chunk's
            // index.
            is Node.NodeRef -> {
                // hashToNodeId resolves local targets; resolveTarget (when wired)
                // fetches + admits a cross-store target into `store` first, so the
                // subsequent lowerSubChunk walk sees it as an ordinary local node.
                val targetId = hashToNodeId[node.target]
                    ?: resolveTarget?.invoke(node.target)
                    ?: error("Lowerer: NodeRef target hash ${node.target} not in hashToNodeId map (and no resolver held it)")
                // Lazy: lower target into a new chunk. We use LOAD_HASH
                // with the sub-chunk index; at runtime the VM allocates a
                // closure around it (zero captures since closed).
                val subChunkIndex = lowerSubChunk("noderef($targetId)") { sub ->
                    lowerExpr(targetId, sub, LocalScope())
                    sub.emit(Opcode.RET)
                }
                val idx = chunk.constant(Constant.ChunkRefC(subChunkIndex))
                chunk.emit(Opcode.LOAD_HASH, idx)
            }

            // TypeAbstraction — erased; the body's bytecode is what runs.
            is Node.TypeAbstraction -> {
                lowerExpr(node.body, chunk, scope)
            }

            // Layer 4 (ForeignNode dispatch): emit MAKE_FOREIGN with the
            // target string + effects list in the constant pool. The VM's
            // CALL site uses the effects list for handler-intercept and
            // capability-coverage checks (Layer 3), then dispatches via
            // the Builtins registry. The effects list is the ForeignNode's
            // full row ([foreignEffectRow]), not `effects` alone.
            is Node.ForeignNode -> {
                val targetIdx = chunk.constant(Constant.ForeignTargetC(node.target))
                val effectsIdx = chunk.constant(effectsConstant(foreignEffectRow(node)))
                val projectionsIdx = chunk.constant(Constant.ProjectionsC(node.effectProjections.map { p ->
                    Constant.ProjectionC(
                        category = category(p.category),
                        sources = p.sources.map { src ->
                            when (src) {
                                is org.strand.core.ProjectionSource.ArgRef ->
                                    Constant.ProjectionSourceC.ArgRef(src.index)
                                is org.strand.core.ProjectionSource.LiteralNode ->
                                    Constant.ProjectionSourceC.Literal(
                                        literalConstant(src.target), schemaCheck(src.target),
                                    )
                            }
                        },
                    )
                }))
                chunk.emit(Opcode.MAKE_FOREIGN, targetIdx, effectsIdx, projectionsIdx)
            }

            // Layer 5 (partial — Fixpoint only): emit body as a sub-chunk
            // analogous to Lambda, with MAKE_FIXPOINT in place of
            // MAKE_CLOSURE. The body Lambda's FIRST parameter is the
            // recursive-call slot (the interpreter convention preserved).
            // CALL semantics differ: when the callee is VmFixpoint, the
            // call prepends the VmFixpoint itself as the new frame's
            // first local before binding the user's arguments.
            is Node.Fixpoint -> {
                lowerFixpoint(nodeId, node, chunk, scope)
            }

            // N-047 Attempt (Q-048). Lowering (reusing SUM_NEW for the Ok/Err
            // wrapping per the orchestrator's opcode-economy decision):
            //
            //   ATTEMPT_PUSH @errLabel   ; push marker (depths + saved caps)
            //   <body opcodes>
            //   ATTEMPT_POP              ; success path: drop the marker
            //   SUM_NEW "Ok" hasPayload  ; wrap the body value as Ok(v)
            //   JUMP @endLabel
            //   errLabel:                ; unwinder has pushed the ErrorPayload
            //   SUM_NEW "Err" hasPayload ; wrap the payload as Err({kind,detail})
            //   endLabel:
            //
            // ATTEMPT_POP precedes the Ok SUM_NEW so the marker is gone before
            // the wrap; the err-path skips ATTEMPT_POP entirely (the unwinder
            // pops the marker when it consumes it).
            is Node.Attempt -> {
                val errOffset = chunk.emitJumpWithPlaceholder(Opcode.ATTEMPT_PUSH)
                lowerExpr(node.body, chunk, scope)
                chunk.emit(Opcode.ATTEMPT_POP)
                val okIdx = chunk.constant(Constant.SumCaseC("Ok", hasPayload = true))
                chunk.emit(Opcode.SUM_NEW, okIdx)
                val endOffset = chunk.emitJumpWithPlaceholder(Opcode.JUMP)
                // err-label: the unwinder resumes here with the ErrorPayload
                // ProductV on the operand stack.
                chunk.patchJump(errOffset)
                val errIdx = chunk.constant(Constant.SumCaseC("Err", hasPayload = true))
                chunk.emit(Opcode.SUM_NEW, errIdx)
                // end-label.
                chunk.patchJump(endOffset)
            }

            // Layer 5 step 3a: ProductValue. Emit each field's value in
            // declaration order, then PRODUCT_NEW with a constant
            // recording the field names in the same order. The VM pops
            // the N values and assembles a [Value.ProductV].
            is Node.ProductValue -> {
                val fieldNames = mutableListOf<String>()
                for (fieldRefId in node.fields) {
                    val fieldRef = store.get(fieldRefId) as? Node.ProductFieldValue
                        ?: error("Lowerer: ProductValue field $fieldRefId is not a ProductFieldValue")
                    fieldNames += fieldRef.fieldName
                    lowerExpr(fieldRef.value, chunk, scope)
                }
                val idx = chunk.constant(Constant.ProductFieldsC(fieldNames.toList()))
                chunk.emit(Opcode.PRODUCT_NEW, idx, fieldNames.size)
            }

            // Layer 5 step 3a: ProductFieldGet. Emit target, then
            // PRODUCT_GET with the field name as a constant.
            is Node.ProductFieldGet -> {
                lowerExpr(node.target, chunk, scope)
                val idx = chunk.constant(Constant.StringC(node.fieldName))
                chunk.emit(Opcode.PRODUCT_GET, idx)
            }

            // Layer 5 step 3b: SumValue. Emit the payload (if non-null),
            // then SUM_NEW with case name + has-payload flag.
            is Node.SumValue -> {
                val payloadId = node.payload
                if (payloadId != null) {
                    lowerExpr(payloadId, chunk, scope)
                }
                val idx = chunk.constant(Constant.SumCaseC(node.caseName, payloadId != null))
                chunk.emit(Opcode.SUM_NEW, idx)
            }

            // Layer 5 step 1: Match. Stash scrutinee in a local; for each
            // case in declaration order emit pattern-test → JUMP_IF_FALSE
            // next-case → body → JUMP end. After the final case, emit
            // THROW_NO_MATCH so runtime mismatches surface as
            // NoMatchingCase (the same error the interpreter raises).
            is Node.Match -> {
                lowerMatch(nodeId, node, chunk, scope)
            }

            // Layer 3 — CapabilityScope: emit CAP_PUSH with the narrowed
            // effect-categories constant, lower body, emit CAP_POP. The
            // VM's CALL site reads the current capability set to check
            // each call's declared effects against the granted set.
            is Node.CapabilityScope -> {
                val capsIdx = chunk.constant(effectsConstant(node.capabilities))
                chunk.emit(Opcode.CAP_PUSH, capsIdx)
                lowerExpr(node.body, chunk, scope)
                chunk.emit(Opcode.CAP_POP)
            }
            // Layer 3 — Handler: evaluate the handle expression, then
            // HANDLER_PUSH (consuming the handle value as the active
            // handler), lower body, HANDLER_POP. The VM's CALL site
            // walks the handler stack and intercepts when the callee's
            // declared effects include the handler's intercept category.
            is Node.Handler -> {
                lowerExpr(node.handle, chunk, scope)
                val interceptConstIdx = chunk.constant(Constant.IntC(category(node.intercept).toLong()))
                // Second operand: the Handler's NodeId, for the VM's
                // verified-interception guard.
                chunk.emit(Opcode.HANDLER_PUSH, interceptConstIdx, nodeId.value)
                lowerExpr(node.body, chunk, scope)
                chunk.emit(Opcode.HANDLER_POP)
            }

            // N-044 ToolDef: evaluate the implementation expression (as the
            // interpreter does, eagerly), then MAKE_TOOLDEF wraps it in a
            // Value.ToolDefV. The implementation is a VM callable, carried
            // boxed, so a builtin that runs the tool (a provider's tool-use
            // loop, a higher-order stand-in) calls back into the VM through
            // Builtins.ApplyFn and gets the callback checks every
            // higher-order builtin's callback gets.
            is Node.ToolDef -> {
                lowerExpr(node.implementation, chunk, scope)
                val idx = chunk.constant(Constant.ToolDefC(
                    self = nodeId.value,
                    name = node.name,
                    description = node.description,
                    parameterSchema = node.parameterSchema.value,
                ))
                chunk.emit(Opcode.MAKE_TOOLDEF, idx)
            }

            // Slice-1 out-of-scope: anything else throws so the test
            // surfaces what we haven't implemented yet.
            else -> throw LoweringNotImplemented(
                nodeId = nodeId,
                category = node::class.simpleName ?: "Unknown",
            )
        }
    }

    /**
     * Lower a Lambda into its own sub-chunk and emit a MAKE_CLOSURE in the
     * surrounding chunk that references it. Closure-captured locals from
     * the enclosing scope are passed via the closure's captures array;
     * the sub-chunk treats its parameters as locals 0..N-1 and captures
     * as a parallel namespace.
     */
    private fun lowerLambda(
        lambdaId: NodeId,
        lambda: Node.Lambda,
        outerChunk: MutableChunk,
        outerScope: LocalScope,
    ) {
        // Determine captures: VarRefs inside the lambda's body that
        // resolve to binders in the outer scope. The simple-but-correct
        // approach: pre-walk the body and collect referenced outer-scope
        // binders.
        val captures = collectFreeBinders(lambda.body, lambda.parameters.toSet(), outerScope)

        // Build the sub-chunk's scope: parameters at local slots 0..N-1,
        // captures at capture slots 0..M-1.
        val subChunkIndex = lowerSubChunk("lambda($lambdaId)") { sub ->
            val subScope = LocalScope().withCaptures(captures)
            for ((i, paramId) in lambda.parameters.withIndex()) {
                val slot = sub.allocLocalAt(i)
                subScope.bindInPlace(paramId, LocalSlot.Local(slot))
            }
            lowerExpr(lambda.body, sub, subScope)
            sub.emit(Opcode.RET)
        }

        // Emit captures onto the operand stack (one LOAD_* per capture),
        // then MAKE_CLOSURE with sub-chunk index + capture count.
        for (captureBinderId in captures) {
            val outerSlot = outerScope.lookup(captureBinderId)
                ?: error("Lowerer: free binder $captureBinderId not in outer scope")
            when (outerSlot) {
                is LocalSlot.Local -> outerChunk.emit(Opcode.LOAD_LOCAL, outerSlot.index)
                is LocalSlot.Capture -> outerChunk.emit(Opcode.LOAD_CAPTURE, outerSlot.index)
            }
        }
        val chunkRefIdx = outerChunk.constant(Constant.ChunkRefC(subChunkIndex))
        val effectsIdx = outerChunk.constant(effectsConstant(lambda.effects))
        outerChunk.emit(Opcode.MAKE_CLOSURE, chunkRefIdx, captures.size, effectsIdx)
    }

    /**
     * Collect the set of binders that [exprId] references from outside its
     * own [parameters]. Walks the expression tree following VarRef binders
     * back to their declaration; if the declaration is OUTSIDE [parameters]
     * AND present in the [outerScope], it's a capture.
     */
    private fun collectFreeBinders(
        exprId: NodeId,
        parameters: Set<NodeId>,
        outerScope: LocalScope,
    ): List<NodeId> {
        val out = LinkedHashSet<NodeId>()
        val visited = HashSet<NodeId>()
        // Local binders introduced by Let nodes encountered during the walk;
        // these are NOT captures even though they're not in parameters.
        val localLets = HashSet<NodeId>()
        walkExpr(exprId, parameters, localLets, outerScope, out, visited)
        return out.toList()
    }

    private fun walkExpr(
        nodeId: NodeId,
        parameters: Set<NodeId>,
        localLets: MutableSet<NodeId>,
        outerScope: LocalScope,
        captures: MutableSet<NodeId>,
        visited: MutableSet<NodeId>,
    ) {
        if (!visited.add(nodeId)) return
        when (val node = store.get(nodeId)) {
            is Node.VarRef -> {
                val binder = node.binder
                if (binder !in parameters && binder !in localLets) {
                    // Check if it's something the outer scope knows about.
                    if (outerScope.lookup(binder) != null) {
                        captures += binder
                    }
                }
            }
            is Node.Let -> {
                walkExpr(node.value, parameters, localLets, outerScope, captures, visited)
                localLets += nodeId
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            is Node.Lambda -> {
                // Nested lambda introduces its own parameter scope; for THIS
                // walk we only care about free vars FROM the OUTER perspective.
                // Free vars of the nested lambda that come from the surrounding
                // scope still flow up. Recurse with the nested params added to
                // the "introduced" set so they're not flagged as captures.
                val combinedParams = parameters + node.parameters.toSet()
                walkExpr(node.body, combinedParams, localLets, outerScope, captures, visited)
            }
            is Node.Application -> {
                walkExpr(node.function, parameters, localLets, outerScope, captures, visited)
                for (argId in node.arguments) {
                    walkExpr(argId, parameters, localLets, outerScope, captures, visited)
                }
                // EffectDecl parameters are lowered at the call site too.
                for (declId in node.effectInstances) {
                    val decl = store.getOrNull(declId) as? Node.EffectDecl ?: continue
                    for (p in decl.parameters) walkExpr(p, parameters, localLets, outerScope, captures, visited)
                }
            }
            is Node.TypeAbstraction -> {
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            // N-047 Attempt — its body may reference outer binders (a TRY over
            // an expression using a captured variable), so walk it.
            is Node.Attempt -> {
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            // Review M5: every expression-bearing category the lowerer
            // handles is walked, so a closure over an outer binder used only
            // inside a Match / Product / Sum / CapabilityScope / Handler /
            // Fixpoint captures it. Pattern-bound binders are never in the
            // outer scope, so they are not mistaken for captures.
            is Node.Match -> {
                walkExpr(node.scrutinee, parameters, localLets, outerScope, captures, visited)
                for (caseId in node.cases) {
                    val case = store.getOrNull(caseId) as? Node.MatchCase ?: continue
                    walkExpr(case.body, parameters, localLets, outerScope, captures, visited)
                }
            }
            is Node.ProductValue -> for (fieldId in node.fields) {
                val field = store.getOrNull(fieldId) as? Node.ProductFieldValue ?: continue
                walkExpr(field.value, parameters, localLets, outerScope, captures, visited)
            }
            is Node.ProductFieldGet -> walkExpr(node.target, parameters, localLets, outerScope, captures, visited)
            is Node.SumValue -> node.payload?.let {
                walkExpr(it, parameters, localLets, outerScope, captures, visited)
            }
            is Node.CapabilityScope -> walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            is Node.Handler -> {
                walkExpr(node.handle, parameters, localLets, outerScope, captures, visited)
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            is Node.Fixpoint -> walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            is Node.ToolDef -> walkExpr(node.implementation, parameters, localLets, outerScope, captures, visited)
            // Literals and NodeRef have no inner expressions that reference
            // outer binders (NodeRef's target is closed by verifier rule);
            // ForeignNode is closed.
            is Node.IntLit, is Node.FloatLit, is Node.StringLit, is Node.BoolLit,
            is Node.UnitLit, is Node.BytesLit, is Node.NodeRef, is Node.ForeignNode -> Unit
            // Remaining categories are rejected by lowerExpr with
            // LoweringNotImplemented, so they cannot hide a capture.
            else -> Unit
        }
    }

    /**
     * Lower a Match into per-case test/branch chains (Layer 5 step 1).
     * Algorithm:
     *
     *  1. Evaluate scrutinee, store into a local.
     *  2. For each case in declaration order:
     *     a. Emit the pattern's test bytecode (uses scrutSlot, leaves
     *        Bool on stack).
     *     b. JUMP_IF_FALSE → next-case-label (skip body when test fails).
     *     c. Bind any pattern-introduced locals (VariablePattern,
     *        ConstructorPattern's variable sub-patterns).
     *     d. Emit the body bytecode.
     *     e. JUMP → end-label.
     *  3. After all cases: THROW_NO_MATCH.
     *  4. end-label: the matching body's result is on top of stack.
     *
     * Pattern tests are emitted by [emitPatternTest], which recurses for
     * ConstructorPattern's payload patterns.
     */
    private fun lowerMatch(
        matchId: NodeId,
        node: Node.Match,
        chunk: MutableChunk,
        scope: LocalScope,
    ) {
        lowerExpr(node.scrutinee, chunk, scope)
        val scrutSlot = chunk.allocLocal()
        chunk.emit(Opcode.STORE_LOCAL, scrutSlot)

        val endJumpOffsets = mutableListOf<Int>()
        for ((caseIndex, caseId) in node.cases.withIndex()) {
            val case = store.get(caseId) as? Node.MatchCase
                ?: error("Lowerer: Match $matchId case[$caseIndex] $caseId is not a MatchCase")
            val pattern = store.get(case.pattern) as? Node.Pattern
                ?: error("Lowerer: Match $matchId case[$caseIndex] pattern is not a Pattern")

            // Per-case: scope extended with any binders introduced by the
            // pattern (we accumulate them into `caseScope` during the
            // pattern's emit + bind walk).
            val caseScope = scope.copyForCase()
            val patternBindings = mutableMapOf<NodeId, Int>()
            emitPatternTest(pattern, case.pattern, scrutSlot, chunk, patternBindings)

            // Jump past this case's body if the test failed.
            val skipBodyOffset = chunk.emitJumpWithPlaceholder(Opcode.JUMP_IF_FALSE)

            // Apply the bindings to the caseScope so the body's VarRefs
            // resolve them as locals.
            for ((binderId, slot) in patternBindings) {
                caseScope.bindInPlace(binderId, LocalSlot.Local(slot))
            }

            // Body. The result is left on the operand stack.
            lowerExpr(case.body, chunk, caseScope)

            // After the body, jump to end (skip remaining cases).
            val endOffset = chunk.emitJumpWithPlaceholder(Opcode.JUMP)
            endJumpOffsets += endOffset

            // Patch JUMP_IF_FALSE to here — start of next case's test.
            chunk.patchJump(skipBodyOffset)
        }
        // Past all cases — no match. The operand is the Match NodeId so the
        // VM raises the interpreter's InterpretError.NoMatchingCase(at).
        chunk.emit(Opcode.THROW_NO_MATCH, matchId.value)

        // Patch all end-jumps to point here.
        for (offset in endJumpOffsets) {
            chunk.patchJump(offset)
        }
    }

    /**
     * Emit bytecode that tests [pattern] against the scrutinee at
     * [scrutSlot]; leaves a Bool on top of the stack. Records any
     * variable-binding patterns' slot allocations into [bindings].
     *
     *  * LiteralPattern: LOAD_LOCAL scrut + push literal + EQ.
     *  * VariablePattern: LOAD_LOCAL scrut + STORE_LOCAL var + PUSH_BOOL true.
     *  * WildcardPattern: PUSH_BOOL true.
     *  * ConstructorPattern (no payload pattern): LOAD scrut + SUM_CASE_IS.
     *  * ConstructorPattern (with payload pattern): LOAD scrut + SUM_CASE_IS;
     *    if true, extract payload to a local and recurse on the payload
     *    pattern; AND the two results.
     */
    private fun emitPatternTest(
        pattern: Node.Pattern,
        patternId: NodeId,
        scrutSlot: Int,
        chunk: MutableChunk,
        bindings: MutableMap<NodeId, Int>,
    ) {
        when (pattern) {
            is Node.Pattern.LiteralPattern -> {
                chunk.emit(Opcode.LOAD_LOCAL, scrutSlot)
                lowerExpr(pattern.literal, chunk, LocalScope())  // literal is closed
                chunk.emit(Opcode.EQ)
            }
            is Node.Pattern.VariablePattern -> {
                val varSlot = chunk.allocLocal()
                chunk.emit(Opcode.LOAD_LOCAL, scrutSlot)
                chunk.emit(Opcode.STORE_LOCAL, varSlot)
                bindings[patternId] = varSlot
                // Test always true.
                val trueIdx = chunk.constant(Constant.BoolC(true))
                chunk.emit(Opcode.PUSH_BOOL, trueIdx)
            }
            is Node.Pattern.WildcardPattern -> {
                val trueIdx = chunk.constant(Constant.BoolC(true))
                chunk.emit(Opcode.PUSH_BOOL, trueIdx)
            }
            is Node.Pattern.ConstructorPattern -> {
                val caseIdx = chunk.constant(Constant.StringC(pattern.caseName))
                chunk.emit(Opcode.LOAD_LOCAL, scrutSlot)
                chunk.emit(Opcode.SUM_CASE_IS, caseIdx)
                val payloadPatternId = pattern.payloadPattern
                if (payloadPatternId == null) {
                    // No payload pattern — the SUM_CASE_IS Bool IS the test.
                    return
                }
                // Has payload pattern. Branch on the case test: if false,
                // skip the payload extraction and leave false on the
                // stack; if true, extract payload and recurse.
                val skipPayloadOffset = chunk.emitJumpWithPlaceholder(Opcode.JUMP_IF_FALSE)

                // Extract payload to a fresh local; the nested pattern
                // test uses it as its scrutSlot.
                val payloadSlot = chunk.allocLocal()
                chunk.emit(Opcode.LOAD_LOCAL, scrutSlot)
                chunk.emit(Opcode.SUM_PAYLOAD)
                chunk.emit(Opcode.STORE_LOCAL, payloadSlot)

                val payloadPattern = store.get(payloadPatternId) as? Node.Pattern
                    ?: error("Lowerer: ConstructorPattern $patternId payload is not a Pattern")
                emitPatternTest(payloadPattern, payloadPatternId, payloadSlot, chunk, bindings)

                // After nested test, jump past the "false" branch.
                val endOffset = chunk.emitJumpWithPlaceholder(Opcode.JUMP)

                // False branch: SUM_CASE_IS pushed false; we need to put
                // false back on the stack (the JUMP_IF_FALSE consumed it).
                chunk.patchJump(skipPayloadOffset)
                val falseIdx = chunk.constant(Constant.BoolC(false))
                chunk.emit(Opcode.PUSH_BOOL, falseIdx)

                chunk.patchJump(endOffset)
            }
        }
    }

    /**
     * Lower a Fixpoint into its own sub-chunk and emit a MAKE_FIXPOINT in
     * the surrounding chunk. The Fixpoint's body is a Lambda whose first
     * parameter is the recursive-self slot — at runtime the VM prepends
     * the FixpointV itself as the new frame's local 0 before binding the
     * remaining arguments to locals 1..N (matching the tree-walking
     * interpreter's `applyCallable` Fixpoint branch).
     */
    private fun lowerFixpoint(
        fixId: NodeId,
        fix: Node.Fixpoint,
        outerChunk: MutableChunk,
        outerScope: LocalScope,
    ) {
        val bodyLambda = store.get(fix.body) as? Node.Lambda
            ?: error("Lowerer: Fixpoint body at $fixId is not a Lambda (verifier should have rejected)")
        // Captures from the outer scope — same walk as Lambda, but the
        // body's parameters include the self slot (parameter 0) which is
        // bound at call time by the VM, not by the captures.
        val captures = collectFreeBinders(bodyLambda.body, bodyLambda.parameters.toSet(), outerScope)

        val subChunkIndex = lowerSubChunk("fixpoint($fixId)") { sub ->
            val subScope = LocalScope().withCaptures(captures)
            for ((i, paramId) in bodyLambda.parameters.withIndex()) {
                val slot = sub.allocLocalAt(i)
                subScope.bindInPlace(paramId, LocalSlot.Local(slot))
            }
            lowerExpr(bodyLambda.body, sub, subScope)
            sub.emit(Opcode.RET)
        }

        for (captureBinderId in captures) {
            val outerSlot = outerScope.lookup(captureBinderId)
                ?: error("Lowerer: free binder $captureBinderId not in outer scope")
            when (outerSlot) {
                is LocalSlot.Local -> outerChunk.emit(Opcode.LOAD_LOCAL, outerSlot.index)
                is LocalSlot.Capture -> outerChunk.emit(Opcode.LOAD_CAPTURE, outerSlot.index)
            }
        }
        val chunkRefIdx = outerChunk.constant(Constant.ChunkRefC(subChunkIndex))
        val effectsIdx = outerChunk.constant(effectsConstant(bodyLambda.effects))
        outerChunk.emit(Opcode.MAKE_FIXPOINT, chunkRefIdx, captures.size, effectsIdx)
    }

    /**
     * Allocate a fresh sub-chunk slot, run [block] to populate it, and
     * return its index. The slot is reserved BEFORE running [block] so
     * nested sub-chunks get higher indices.
     */
    private fun lowerSubChunk(name: String, block: (MutableChunk) -> Unit): Int {
        subChunkByName[name]?.let { return it }
        val sub = MutableChunk(name = name)
        val index = chunks.size
        chunks += sub
        subChunkByName[name] = index
        block(sub)
        return index
    }
}

/**
 * Thrown when the Lowerer encounters a node category it doesn't yet
 * handle (Q-017 step 1 ships Layer 1; Layers 3-7 extend this).
 */
class LoweringNotImplemented(
    val nodeId: NodeId,
    val category: String,
) : RuntimeException(
    "Lowerer slice 1 does not handle node category '$category' at $nodeId — " +
        "extend [org.strand.bytecode.Lowerer.lowerExpr] when this layer reaches the VM"
)

/**
 * Per-binder slot location: either a local in the current frame or a
 * capture inherited from the enclosing closure. Used by [Lowerer] to
 * decide between LOAD_LOCAL and LOAD_CAPTURE when emitting a VarRef.
 */
sealed class LocalSlot {
    data class Local(val index: Int) : LocalSlot()
    data class Capture(val index: Int) : LocalSlot()
}

/**
 * Mutable lexical scope used during lowering. Maps binder NodeIds to
 * their slot location in the current chunk's frame. Lambda lowering
 * builds a fresh scope with the lambda's parameters as locals and any
 * outer-scope captures as captures.
 */
internal class LocalScope private constructor(
    private val table: MutableMap<NodeId, LocalSlot>,
) {
    constructor() : this(mutableMapOf())

    fun lookup(binder: NodeId): LocalSlot? = table[binder]

    /**
     * Return a fresh scope that extends [this] with [binder] → [slot].
     * Used at expression entry points where a copy-on-write extension
     * matches Strand's lexical-scoping semantics.
     */
    fun bind(binder: NodeId, slot: LocalSlot): LocalScope =
        LocalScope(LinkedHashMap(table).apply { put(binder, slot) })

    /** In-place bind used by lambda parameter setup. */
    fun bindInPlace(binder: NodeId, slot: LocalSlot) {
        table[binder] = slot
    }

    /**
     * Shallow copy of this scope for per-case Match scoping — pattern-
     * introduced bindings live only inside the case body and must not
     * leak into sibling cases.
     */
    fun copyForCase(): LocalScope = LocalScope(LinkedHashMap(table))

    /**
     * Populate this scope's captures from [captureBinders] in declaration
     * order; each becomes a [LocalSlot.Capture] with the corresponding
     * capture-array index.
     */
    fun withCaptures(captureBinders: List<NodeId>): LocalScope {
        val fresh = LocalScope()
        for ((i, binder) in captureBinders.withIndex()) {
            fresh.table[binder] = LocalSlot.Capture(i)
        }
        return fresh
    }
}
