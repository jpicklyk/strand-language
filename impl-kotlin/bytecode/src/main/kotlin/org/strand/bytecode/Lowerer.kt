package org.strand.bytecode

import org.strand.core.Hash
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.NodeStore

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
) {
    private val chunks = mutableListOf<MutableChunk>()

    /**
     * Lower the program rooted at [rootId] into a [ChunkTable]. The root
     * chunk is at index 0; sub-chunks (Lambda bodies, Fixpoint bodies)
     * follow in the order they're encountered.
     */
    fun lower(rootId: NodeId): ChunkTable {
        // Reserve the root chunk's slot so sub-chunks get index ≥ 1.
        val root = MutableChunk(name = "root($rootId)")
        chunks += root
        lowerExpr(rootId, root, scope = LocalScope())
        root.emit(Opcode.HALT)
        return ChunkTable(chunks.map { it.toChunk() })
    }

    /**
     * Lower an expression-position node into [chunk]'s instruction stream.
     * The expression's value ends up on top of the operand stack when the
     * emitted instructions finish.
     */
    private fun lowerExpr(
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

            // Application — push function, push args left-to-right, CALL.
            is Node.Application -> {
                lowerExpr(node.function, chunk, scope)
                for (argId in node.arguments) {
                    lowerExpr(argId, chunk, scope)
                }
                chunk.emit(Opcode.CALL, node.arguments.size)
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
            // the Builtins registry.
            is Node.ForeignNode -> {
                val targetIdx = chunk.constant(Constant.ForeignTargetC(node.target))
                val effectsIdx = chunk.constant(Constant.EffectsC(
                    IntArray(node.effects.size) { node.effects[it].value }
                ))
                chunk.emit(Opcode.MAKE_FOREIGN, targetIdx, effectsIdx)
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
                val capsIdx = chunk.constant(Constant.EffectsC(
                    IntArray(node.capabilities.size) { node.capabilities[it].value }
                ))
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
                val interceptConstIdx = chunk.constant(Constant.IntC(node.intercept.value.toLong()))
                chunk.emit(Opcode.HANDLER_PUSH, interceptConstIdx)
                lowerExpr(node.body, chunk, scope)
                chunk.emit(Opcode.HANDLER_POP)
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
        val effectsIdx = outerChunk.constant(Constant.EffectsC(
            IntArray(lambda.effects.size) { lambda.effects[it].value }
        ))
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
            }
            is Node.TypeAbstraction -> {
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            // N-047 Attempt — its body may reference outer binders (a TRY over
            // an expression using a captured variable), so walk it.
            is Node.Attempt -> {
                walkExpr(node.body, parameters, localLets, outerScope, captures, visited)
            }
            // Literals and NodeRef have no inner expressions that reference
            // outer binders (NodeRef's target is closed by verifier rule).
            is Node.IntLit, is Node.FloatLit, is Node.StringLit, is Node.BoolLit,
            is Node.UnitLit, is Node.BytesLit, is Node.NodeRef -> Unit
            // Out-of-slice nodes — collect free vars of their expression
            // children where they appear; the lowerer will fail with
            // LoweringNotImplemented at lowering time anyway.
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
        // Past all cases — no match.
        chunk.emit(Opcode.THROW_NO_MATCH)

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
        val effectsIdx = outerChunk.constant(Constant.EffectsC(
            IntArray(bodyLambda.effects.size) { bodyLambda.effects[it].value }
        ))
        outerChunk.emit(Opcode.MAKE_FIXPOINT, chunkRefIdx, captures.size, effectsIdx)
    }

    /**
     * Allocate a fresh sub-chunk slot, run [block] to populate it, and
     * return its index. The slot is reserved BEFORE running [block] so
     * nested sub-chunks get higher indices.
     */
    private fun lowerSubChunk(name: String, block: (MutableChunk) -> Unit): Int {
        val sub = MutableChunk(name = name)
        val index = chunks.size
        chunks += sub
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
