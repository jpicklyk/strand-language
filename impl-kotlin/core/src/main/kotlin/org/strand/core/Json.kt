package org.strand.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON ingest for Strand Layer 1 programs.
 *
 * Schema (flat form):
 *
 * ```
 * {
 *   "version": 1,
 *   "root": "<author id>",
 *   "nodes": {
 *     "<author id>": { "type": "<NodeType>", ...fields... },
 *     ...
 *   }
 * }
 * ```
 *
 * Author ids are arbitrary strings used only inside the document to wire
 * nodes together. The ingester rewrites them to opaque [NodeId]s in a single
 * pass: every field whose value is an author id string referring to another
 * node is resolved via a name-to-NodeId map. Order in the JSON does not
 * matter; forward references are allowed.
 *
 * Per-node field schema (see node-algebra.md for canonical definitions):
 *
 * Literals:
 *   IntLit       { "type": "IntLit",    "value": <number> }
 *   FloatLit     { "type": "FloatLit",  "value": <number> }
 *   StringLit    { "type": "StringLit", "value": <string> }
 *   BoolLit      { "type": "BoolLit",   "value": <boolean> }
 *   UnitLit      { "type": "UnitLit" }
 *   BytesLit     { "type": "BytesLit",  "value": <hex string> }
 *
 * Types:
 *   PrimitiveType     { "type": "PrimitiveType", "kind": "Int|Float|String|Bool|Unit|Bytes" }
 *   ProductType       { "type": "ProductType", "fields": [<id>, ...] }
 *   ProductTypeField  { "type": "ProductTypeField", "name": <string>, "fieldType": <id> }
 *   SumType           { "type": "SumType", "cases": [<id>, ...] }
 *   SumTypeCase       { "type": "SumTypeCase", "name": <string>, "caseType": <id?> }
 *   FunctionType      { "type": "FunctionType", "parameters": [<id>, ...], "result": <id> }
 *   TypeParameter     { "type": "TypeParameter", "name": <string>, "bound": <id?> }
 *   ForallType        { "type": "ForallType", "typeParameters": [<id>, ...], "body": <id> }
 *   RecursiveType     { "type": "RecursiveType", "body": <id> }
 *   RecursiveSelf     { "type": "RecursiveSelf", "depth": <int>?  (default 0) }
 *   RecursiveProjection { "type": "RecursiveProjection", "recursiveType": <RecursiveType id>,
 *                         "path": [ { "step": "Case", "caseName": <string> }
 *                                 | { "step": "Field", "fieldName": <string> }
 *                                 | { "step": "Unfold" }, ... ] }
 *
 * Functions and binding:
 *   Lambda           { "type": "Lambda", "parameters": [<id>, ...], "body": <id> }
 *   TypeAbstraction  { "type": "TypeAbstraction", "typeParameters": [<id>, ...], "body": <id> }
 *   ParameterDecl    { "type": "ParameterDecl", "name": <string>, "paramType": <id> }
 *   Application      { "type": "Application", "function": <id>, "arguments": [<id>, ...],
 *                      "typeArguments": [<id>, ...]?  (optional; defaults to []),
 *                      "effectInstances": [<id>, ...]?  (optional; defaults to []; each an EffectDecl) }
 *   Let              { "type": "Let", "name": <string>, "value": <id>, "body": <id> }
 *   VarRef           { "type": "VarRef", "binder": <id> }
 *
 * References:
 *   NodeRef       { "type": "NodeRef", "target": <id> }            (local ref)
 *              or { "type": "NodeRef", "targetHash": "<hex>" }     (cross-store ref, Q-043)
 *
 *   A NodeRef declares exactly one of `target` (an author id naming a node in
 *   this document) or `targetHash` (the content hash of a node held in a peer
 *   store, resolved through the federation resolver at verify/run time).
 *
 * Composition and distribution (N-046):
 *   ModuleManifest { "type": "ModuleManifest",
 *                    "exports": [ { "target": <id>,
 *                                   "declaredEffects": [<EffectCategory id>, ...]?,
 *                                   "displayName": <string> }, ... ],
 *                    "manifestSignature": <hex string>?  }
 *
 * Agent-native capabilities:
 *   ToolDef             { "type": "ToolDef", "name": <string>, "description": <string>,
 *                         "parameterSchema": <Schema id>, "implementation": <Expression id> }
 *   ResponseSchemaSpec  { "type": "ResponseSchemaSpec", "schema": <Schema id> }
 *
 * State machines:
 *   StateMachine { "type": "StateMachine", "transitionFn": <id>, "initialState": <id>,
 *                  "inputStreams": [<id>, ...], "outputStreams": [<id>, ...]?,
 *                  "effects": [<id>, ...]? }
 *   EventStream  { "type": "EventStream", "eventType": <id>,
 *                  "streamKind": "external|internal|output",
 *                  "bufferSize": <positive int>?  (optional; default 1024 at runtime),
 *                  "overflowPolicy": <policy>?  (optional; default BlockProducer),
 *                  "consumerMode": "Single|Broadcast"?  (optional; default Single),
 *                  "source": <id>?  (optional; Q-046 source edge) }
 *   Transition   { "type": "Transition", "guard": <id>?, "body": <id> }
 *
 *   overflowPolicy may be either a shorthand string ("BlockProducer",
 *   "DropNewest", "DropOldest") or an object form for the parameterized
 *   variant: `{ "kind": "Sample", "intervalNanos": <long> }`. The canonical
 *   encoder emits the bufferSize / overflowPolicy / consumerMode group only
 *   when at least one of them is non-default, and always emits the `source`
 *   field under the epoch-2 (Q-062) presence prefix; see
 *   design/canonical-encoding.md for the exact layout.
 *
 * Every scalar is type-checked strictly: a string field must be a JSON
 * string, a numeric or boolean field a JSON number or boolean (never a
 * quoted one), and every reference a string author id. Strings must be
 * well-formed UTF-16 (no unpaired surrogates).
 */
object JsonIngest {

    private val parser: Json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    data class IngestResult(
        /**
         * Pre-finalization store. [Node.NodeRef] slots are populated as
         * [StoredNode.RawNodeRef] carrying the target's in-document NodeId;
         * downstream consumers must call `Hasher.finalize` (in the `:hashing`
         * module) to produce a canonical [NodeStore] before passing to the
         * verifier or interpreter.
         */
        val rawStore: RawNodeStore,
        val root: NodeId,
        /** Mapping from author ids in the source JSON to assigned NodeIds. */
        val nameMap: Map<String, NodeId>
    )

    fun parse(text: String): IngestResult = parse(text, EvaluationLimits.DEFAULTS)

    /**
     * Q-040: ingest with resource limits. Three checks fire before any node
     * is materialized:
     *  1. **Byte cap.** `text.length > limits.maxIngestBytes` →
     *     [IngestError.ResourceExhaustion] with [ExhaustionKind.IngestBytes].
     *  2. **JSON depth cap.** A linear pre-scan of `{` / `[` nesting (per
     *     [validateJsonDepth]) runs before invoking kotlinx-serialization
     *     (which exposes no depth hook). Exceeding `limits.maxJsonDepth`
     *     raises [IngestError.ResourceExhaustion] with [ExhaustionKind.JsonDepth].
     *  3. **Node count cap.** After parsing the top-level object,
     *     `nodes.entries.size > limits.maxNodeCount` raises
     *     [IngestError.ResourceExhaustion] with [ExhaustionKind.NodeCount].
     *
     * A fourth cap fires after the nodes are materialized but before the
     * store is committed: **graph depth** (review H3). The longest reference
     * chain exceeding `limits.maxGraphDepth` raises
     * [IngestError.ResourceExhaustion] with [ExhaustionKind.GraphDepth].
     *
     * Existing malformed-input failure paths (missing fields, unknown node
     * types, etc.) raise [IngestError.Malformed] with the same string
     * messages they did pre-Q-040.
     */
    fun parse(text: String, limits: EvaluationLimits): IngestResult {
        if (text.length > limits.maxIngestBytes) {
            throw IngestError.ResourceExhaustion(
                kind = ExhaustionKind.IngestBytes,
                current = text.length.toLong(),
                limit = limits.maxIngestBytes,
            )
        }
        validateJsonDepth(text, limits.maxJsonDepth)
        // Q-066 ingest-boundary hardening: a syntactically invalid document
        // (truncated text, stray bytes, unbalanced quotes) must surface as a
        // structured [IngestError.Malformed], not as kotlinx-serialization's
        // raw SerializationException.
        val element = try {
            parser.parseToJsonElement(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IngestError.Malformed(
                "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
            )
        }
        return parse(element, limits)
    }

    fun parse(element: JsonElement): IngestResult = parse(element, EvaluationLimits.DEFAULTS)

    /**
     * Q-066 ingest-boundary hardening: kotlinx-serialization's tree
     * accessors (`jsonPrimitive`, `jsonArray`) raise raw
     * `IllegalArgumentException` when a field has the wrong JSON shape
     * (e.g. an object where a scalar is expected). At the ingest boundary
     * a wrong-shaped field is a malformed document, so every escaping
     * `IllegalArgumentException` is translated to [IngestError.Malformed].
     * Internal-invariant failures use `check`/`error`
     * (`IllegalStateException`) and deliberately stay loud.
     */
    fun parse(element: JsonElement, limits: EvaluationLimits): IngestResult = try {
        parseValidated(element, limits)
    } catch (e: IllegalArgumentException) {
        throw IngestError.Malformed(
            "Malformed document: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
        )
    }

    private fun parseValidated(element: JsonElement, limits: EvaluationLimits): IngestResult {
        validateWellFormedStrings(element)
        val obj = element.requireObject("root document")

        val version = obj["version"].strictLong()
        if (version != 1L) {
            throw IngestError.Malformed("Unsupported or missing schema version (expected 1, got $version)")
        }

        val rootName = obj["root"].strictString()
            ?: throw IngestError.Malformed("Missing 'root' field")

        val nodesObj = obj["nodes"]?.requireObject("nodes")
            ?: throw IngestError.Malformed("Missing 'nodes' object")

        val orderedEntries = nodesObj.entries.toList()

        // Q-040 ingest-time node-count cap. Fires before any NodeId is
        // allocated so a hostile million-node payload cannot tie up
        // memory inside `nameToId` / `slots` before being rejected.
        if (orderedEntries.size > limits.maxNodeCount) {
            throw IngestError.ResourceExhaustion(
                kind = ExhaustionKind.NodeCount,
                current = orderedEntries.size.toLong(),
                limit = limits.maxNodeCount.toLong(),
            )
        }

        // Pass 1: assign each author id a NodeId in declaration order. We pick
        // NodeIds eagerly so that pass 2 can resolve forward references; the
        // RawNodeStore is populated only at the end, with materialized
        // StoredNodes, never with a sentinel. Indices here are chosen to
        // match the sequential ids RawNodeStore.add() will assign in pass 3.
        val nameToId = linkedMapOf<String, NodeId>()
        val slots = linkedMapOf<NodeId, IngestSlot>()
        for ((index, entry) in orderedEntries.withIndex()) {
            val name = entry.key
            if (name in nameToId) throw IngestError.Malformed("Duplicate node id: '$name'")
            val id = NodeId(index)
            nameToId[name] = id
            slots[id] = IngestSlot.Pending
        }

        if (rootName !in nameToId) {
            throw IngestError.Malformed("Root '$rootName' is not declared in 'nodes'")
        }

        // Pass 2: materialize each node, resolving references through nameToId.
        val resolver: (String, String) -> NodeId = { name, ctx ->
            nameToId[name]
                ?: throw IngestError.Malformed("Unknown node id '$name' referenced from $ctx")
        }
        for ((name, raw) in orderedEntries) {
            val nodeObj = raw.requireObject("node '$name'")
            val stored = buildStored(name, nodeObj, resolver)
            slots[nameToId.getValue(name)] = IngestSlot.Resolved(stored)
        }

        // Pass 2.5 (Q-066): binder-position category invariants. The
        // canonical encoder's positional (de Bruijn) binder encoding
        // requires that Lambda.parameters reference ParameterDecl nodes and
        // that TypeAbstraction.typeParameters / ForallType.typeParameters
        // reference TypeParameter nodes. A document violating these cannot
        // be canonically encoded at all, so it is rejected here as
        // malformed input rather than crashing the hash walk downstream.
        // (The verifier's CategoryMismatch rule still guards the same
        // invariant for programmatically-built stores.)
        validateBinderCategories(orderedEntries.map { it.key }, nameToId, slots)

        // Pass 2.6 (Q-066): reject reference cycles. The canonical form is a
        // Merkle DAG — children appear by hash, and ADR-003 forbids
        // self-referential hashes — so a cyclic document can never be
        // content-addressed; without this check a cycle crashes the hash
        // walk with a raw StackOverflowError. The walk follows the same
        // hash-relevant edges as the encoder: [childNodeIds] for canonical
        // nodes minus VarRef.binder (binders are positionally encoded
        // back-edges by design — every Let-bound VarRef points back at its
        // Let), plus the raw NodeRef / ModuleManifest target ids that
        // finalize must hash first.
        //
        // The same pass enforces (2.7) that every local NodeRef's target is
        // reachable from the program root: a local NodeRef's canonical form
        // is its target's content hash, and the hash walk only computes
        // hashes for root-reachable nodes — an unreachable target leaves
        // the NodeRef with no canonical form at all.
        //
        // The same DFS enforces the graph-depth cap (review H3):
        // [EvaluationLimits.maxGraphDepth] bounds the longest reference
        // chain, which the flat JSON form (and so [validateJsonDepth]) does
        // not bound, and which every downstream recursive walk depends on.
        validateAcyclic(
            orderedEntries.map { it.key }, nameToId, slots, nameToId.getValue(rootName),
            limits.maxGraphDepth,
        )

        // Pass 3: commit to the raw store. Every slot must be Resolved; an
        // unresolved slot would indicate an ingest bug, not a malformed
        // document, because pass 2 visits every entry.
        val rawStore = RawNodeStore()
        for ((id, slot) in slots) {
            val stored = when (slot) {
                is IngestSlot.Resolved -> slot.stored
                IngestSlot.Pending -> error(
                    "Internal ingest error: slot $id was reserved but never materialized"
                )
            }
            val assigned = rawStore.add(stored)
            check(assigned == id) {
                "Ingest invariant violation: expected RawNodeStore to assign $id but got $assigned"
            }
        }

        return IngestResult(rawStore, nameToId.getValue(rootName), nameToId)
    }

    /**
     * Review H2: reject ill-formed UTF-16 (an unpaired surrogate, reachable
     * through a JSON `\ud800` escape) in every string of the document —
     * object keys and string values alike. The canonical encoding carries
     * string content fields as UTF-8, and an unpaired surrogate has no UTF-8
     * encoding: the JVM's lenient encoder substitutes `?`, which would make
     * two different values hash identically. Iterative, since the element
     * tree may be up to [EvaluationLimits.maxJsonDepth] deep.
     */
    private fun validateWellFormedStrings(root: JsonElement) {
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val e = pending.removeLast()) {
                is JsonObject -> for ((key, value) in e) {
                    requireWellFormedUtf16(key, "object key")
                    pending.addLast(value)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { pending.addLast(it) }
                is JsonPrimitive -> if (e.isString) requireWellFormedUtf16(e.content, "string value")
            }
        }
    }

    private fun requireWellFormedUtf16(s: String, what: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                throw IngestError.Malformed(
                    "Ill-formed UTF-16 in $what: unpaired high surrogate at offset $i " +
                        "(string content must be valid Unicode to have a canonical UTF-8 encoding)"
                )
            }
            if (Character.isLowSurrogate(c)) {
                throw IngestError.Malformed(
                    "Ill-formed UTF-16 in $what: unpaired low surrogate at offset $i " +
                        "(string content must be valid Unicode to have a canonical UTF-8 encoding)"
                )
            }
            i++
        }
    }

    /**
     * Internal slot type for the two-pass JSON ingest. A [Pending] slot is one
     * whose [NodeId] has been allocated but whose [StoredNode] has not yet
     * been materialized; pass 2 transitions every slot to [Resolved]. This
     * type is deliberately local to [JsonIngest].
     */
    private sealed class IngestSlot {
        object Pending : IngestSlot()
        data class Resolved(val stored: StoredNode) : IngestSlot()
    }

    /**
     * Q-066 pass 2.5: enforce the structural category invariants that the
     * canonical encoding depends on. Runs after pass 2 (so forward
     * references are resolved) and before pass 3. Violations raise
     * [IngestError.Malformed] naming the offending node, field, and actual
     * category.
     *
     * Two invariant groups, both load-bearing for the encoder rather than
     * mere type errors:
     *
     *  1. **Positional binder and name-keyed children.** Lambda.parameters
     *     must be ParameterDecl (positional binder encoding);
     *     TypeAbstraction / ForallType typeParameters must be TypeParameter
     *     (same); ProductType.fields must be ProductTypeField,
     *     SumType.cases must be SumTypeCase, ProductValue.fields must be
     *     ProductFieldValue (name-keyed child encodings read the child's
     *     name field); MatchCase.pattern must be a Pattern (the pattern's
     *     binder structure is encoded inline).
     *
     *  2. **Intrinsic nodes have no standalone encoding.** A ParameterDecl
     *     is encoded inline in its enclosing Lambda; the only legal edges
     *     to one are Lambda.parameters entries and VarRef.binder
     *     back-references. Any other edge would make the hash walk visit
     *     it standalone, which the encoding does not define.
     *
     * Name-keyed children must also be distinct: duplicate ProductType
     * field names, SumType case names, or ProductValue field names are
     * rejected (review verifier C1 — a duplicate let the verifier and the
     * interpreter pick different fields of the same name).
     *
     * The verifier's CategoryMismatch rule still guards the same shapes
     * for programmatically-built stores.
     */
    private fun validateBinderCategories(
        orderedNames: List<String>,
        nameToId: Map<String, NodeId>,
        slots: Map<NodeId, IngestSlot>,
    ) {
        val idToName = nameToId.entries.associate { (n, i) -> i to n }
        fun categoryOf(id: NodeId): String = when (val slot = slots.getValue(id)) {
            is IngestSlot.Resolved -> when (val stored = slot.stored) {
                is StoredNode.Canonical -> stored.node::class.simpleName ?: "?"
                is StoredNode.RawNodeRef -> "NodeRef"
                is StoredNode.RawModuleManifest -> "ModuleManifest"
            }
            IngestSlot.Pending -> "?"
        }
        fun nodeAt(id: NodeId): Node? =
            ((slots.getValue(id) as? IngestSlot.Resolved)?.stored as? StoredNode.Canonical)?.node

        fun requireCategory(owner: String, field: String, ids: List<NodeId>, expected: (Node?) -> Boolean, expectedName: String) {
            for ((i, childId) in ids.withIndex()) {
                if (!expected(nodeAt(childId))) {
                    throw IngestError.Malformed(
                        "Node '$owner' field $field[$i] references " +
                            "'${idToName[childId] ?: childId}' (${categoryOf(childId)}); " +
                            "$field entries must be $expectedName nodes"
                    )
                }
            }
        }

        for (name in orderedNames) {
            when (val node = nodeAt(nameToId.getValue(name))) {
                is Node.Lambda ->
                    requireCategory(name, "Lambda.parameters", node.parameters,
                        { it is Node.ParameterDecl }, "ParameterDecl")
                is Node.TypeAbstraction ->
                    requireCategory(name, "TypeAbstraction.typeParameters", node.typeParameters,
                        { it is Node.TypeParameter }, "TypeParameter")
                is Node.ForallType ->
                    requireCategory(name, "ForallType.typeParameters", node.typeParameters,
                        { it is Node.TypeParameter }, "TypeParameter")
                is Node.ProductType ->
                    requireCategory(name, "ProductType.fields", node.fields,
                        { it is Node.ProductTypeField }, "ProductTypeField")
                is Node.SumType ->
                    requireCategory(name, "SumType.cases", node.cases,
                        { it is Node.SumTypeCase }, "SumTypeCase")
                is Node.ProductValue ->
                    requireCategory(name, "ProductValue.fields", node.fields,
                        { it is Node.ProductFieldValue }, "ProductFieldValue")
                is Node.MatchCase ->
                    requireCategory(name, "MatchCase.pattern", listOf(node.pattern),
                        { it is Node.Pattern }, "Pattern")
                else -> {}
            }
        }

        // Group 1b (review verifier C1, ingest half): name-keyed children
        // must have distinct names. `ProductType{x: Int, x: String}` let the
        // verifier type a field read against one duplicate while the
        // interpreter read the other — a verified program reaching a runtime
        // type error. The same holds for SumType case names and
        // ProductValue field names. Group 1 has already established the
        // child categories, so the casts below are safe.
        fun requireDistinct(owner: String, what: String, children: List<NodeId>, nameOf: (Node) -> String) {
            val seen = HashSet<String>()
            for (childId in children) {
                val childName = nameOf(nodeAt(childId)!!)
                if (!seen.add(childName)) {
                    throw IngestError.Malformed(
                        "Node '$owner' declares $what '$childName' more than once; " +
                            "$what names must be distinct"
                    )
                }
            }
        }
        for (name in orderedNames) {
            when (val node = nodeAt(nameToId.getValue(name))) {
                is Node.ProductType -> requireDistinct(name, "ProductType field", node.fields) {
                    (it as Node.ProductTypeField).fieldName
                }
                is Node.SumType -> requireDistinct(name, "SumType case", node.cases) {
                    (it as Node.SumTypeCase).caseName
                }
                is Node.ProductValue -> requireDistinct(name, "ProductValue field", node.fields) {
                    (it as Node.ProductFieldValue).fieldName
                }
                else -> {}
            }
        }

        // Group 2: ParameterDecl may only be referenced from
        // Lambda.parameters or VarRef.binder. Walk every other
        // hash-relevant edge and reject ParameterDecl targets.
        for (name in orderedNames) {
            val id = nameToId.getValue(name)
            val stored = (slots.getValue(id) as? IngestSlot.Resolved)?.stored ?: continue
            val generalEdges: List<NodeId> = when (stored) {
                is StoredNode.Canonical -> when (val node = stored.node) {
                    is Node.VarRef -> emptyList()
                    is Node.Lambda -> node.body.let { listOf(it) } + node.effects
                    // Projection categories are hashed inline by the
                    // encoder (childNodeIds excludes them); they are
                    // general edges for this rule.
                    is Node.ForeignNode ->
                        node.childNodeIds() + node.effectProjections.map { it.category }
                    is Node.FunctionType ->
                        node.childNodeIds() + node.effectProjections.map { it.category }
                    else -> node.childNodeIds()
                }
                is StoredNode.RawNodeRef -> listOf(stored.targetId)
                is StoredNode.RawModuleManifest ->
                    stored.exports.map { it.target } + stored.exports.flatMap { it.declaredEffects }
            }
            for (target in generalEdges) {
                if (nodeAt(target) is Node.ParameterDecl) {
                    throw IngestError.Malformed(
                        "Node '$name' references ParameterDecl " +
                            "'${idToName[target] ?: target}' from a non-binder position; " +
                            "a ParameterDecl is intrinsic to its Lambda and may only be " +
                            "referenced from Lambda.parameters or VarRef.binder"
                    )
                }
            }
        }
    }

    /**
     * Q-066 pass 2.6: reject reference cycles with a structured
     * [IngestError.Malformed]. Iterative three-color DFS (no recursion — a
     * legitimate document may be tens of thousands of nodes deep) over the
     * hash-relevant edge set: [childNodeIds] for canonical nodes excluding
     * [Node.VarRef.binder] (binders are positionally encoded back-edges),
     * the raw target of a pre-finalization NodeRef, and the export targets
     * plus declared effects of a pre-finalization ModuleManifest.
     *
     * The same DFS computes each node's graph height (the number of nodes on
     * the longest path from it down that edge set, a leaf being 1) as it
     * finishes the node, and rejects a document in which any node's height
     * exceeds [maxGraphDepth] with [IngestError.ResourceExhaustion] /
     * [ExhaustionKind.GraphDepth]. The flat JSON form does not bound graph
     * depth — a 100k-node Let chain is shallow JSON — while the canonical
     * encoder, the hash walk, the verifier and the interpreter all recurse
     * per graph level (review H3 / verifier M4).
     */
    private fun validateAcyclic(
        orderedNames: List<String>,
        nameToId: Map<String, NodeId>,
        slots: Map<NodeId, IngestSlot>,
        rootId: NodeId,
        maxGraphDepth: Int,
    ) {
        fun edgesOf(id: NodeId): List<NodeId> {
            val stored = ((slots.getValue(id) as? IngestSlot.Resolved)?.stored) ?: return emptyList()
            return when (stored) {
                is StoredNode.Canonical -> when (val node = stored.node) {
                    is Node.VarRef -> emptyList()
                    // EffectProjection.category is excluded from
                    // childNodeIds (federation-walk concerns) but IS
                    // hashed inline by the canonical encoding, so it
                    // participates in cycles.
                    is Node.ForeignNode ->
                        node.childNodeIds() + node.effectProjections.map { it.category }
                    is Node.FunctionType ->
                        node.childNodeIds() + node.effectProjections.map { it.category }
                    else -> node.childNodeIds()
                }
                is StoredNode.RawNodeRef -> listOf(stored.targetId)
                is StoredNode.RawModuleManifest ->
                    stored.exports.map { it.target } + stored.exports.flatMap { it.declaredEffects }
            }
        }

        val idToName = nameToId.entries.associate { (n, i) -> i to n }
        val white = 0
        val gray = 1
        val black = 2
        val color = HashMap<NodeId, Int>()
        val height = HashMap<NodeId, Int>()
        for (startName in orderedNames) {
            val start = nameToId.getValue(startName)
            if ((color[start] ?: white) != white) continue
            val stack = ArrayDeque<Pair<NodeId, Iterator<NodeId>>>()
            color[start] = gray
            stack.addLast(start to edgesOf(start).iterator())
            while (stack.isNotEmpty()) {
                val (id, children) = stack.last()
                if (children.hasNext()) {
                    val child = children.next()
                    when (color[child] ?: white) {
                        white -> {
                            color[child] = gray
                            stack.addLast(child to edgesOf(child).iterator())
                        }
                        gray -> throw IngestError.Malformed(
                            "Reference cycle involving node '${idToName[child] ?: child}' " +
                                "(reached again from '${idToName[id] ?: id}'); " +
                                "a Strand program is a DAG — children appear by hash and " +
                                "cannot reference an ancestor"
                        )
                        else -> {}
                    }
                } else {
                    // Every child is black here (a gray child is a cycle and
                    // was thrown above), so its height is final.
                    var h = 1
                    for (child in edgesOf(id)) {
                        val ch = height.getValue(child) + 1
                        if (ch > h) h = ch
                    }
                    if (h > maxGraphDepth) {
                        throw IngestError.ResourceExhaustion(
                            kind = ExhaustionKind.GraphDepth,
                            current = h.toLong(),
                            limit = maxGraphDepth.toLong(),
                        )
                    }
                    height[id] = h
                    color[id] = black
                    stack.removeLast()
                }
            }
        }

        // Pass 2.7: every local NodeRef's target must be reachable from the
        // root over the same edge set (which itself flows through NodeRef
        // targets), because the NodeRef's canonical form is the target's
        // hash and the hash walk computes hashes only for root-reachable
        // nodes. Unreachable non-NodeRef nodes remain a warning-level
        // concern (the verifier's unreachable-node sweep), not an ingest
        // error — they have standalone encodings and crash nothing.
        val reachable = HashSet<NodeId>()
        val queue = ArrayDeque<NodeId>()
        reachable.add(rootId)
        queue.addLast(rootId)
        while (queue.isNotEmpty()) {
            for (child in edgesOf(queue.removeFirst())) {
                if (reachable.add(child)) queue.addLast(child)
            }
        }
        for (name in orderedNames) {
            val id = nameToId.getValue(name)
            val stored = (slots.getValue(id) as? IngestSlot.Resolved)?.stored
            if (stored is StoredNode.RawNodeRef) {
                if (stored.targetId !in reachable) {
                    throw IngestError.Malformed(
                        "NodeRef '$name' targets '${idToName[stored.targetId] ?: stored.targetId}', " +
                            "which is not reachable from the root; a local NodeRef target must be " +
                            "reachable so its content hash is computed"
                    )
                }
                // Intrinsic nodes (TypeParameter, RecursiveSelf; ParameterDecl
                // is covered by the pass 2.5 intrinsic rule) never receive a
                // standalone hash — the hash walk skips them — so a NodeRef
                // targeting one has no canonical form.
                val targetNode = ((slots.getValue(stored.targetId) as? IngestSlot.Resolved)
                    ?.stored as? StoredNode.Canonical)?.node
                if (targetNode is Node.TypeParameter || targetNode is Node.RecursiveSelf) {
                    throw IngestError.Malformed(
                        "NodeRef '$name' targets intrinsic node " +
                            "'${idToName[stored.targetId] ?: stored.targetId}' " +
                            "(${targetNode::class.simpleName}); intrinsic nodes have no " +
                            "standalone hash and cannot be NodeRef targets"
                    )
                }
            }
        }
    }

    private fun buildStored(
        name: String,
        obj: JsonObject,
        resolve: (String, String) -> NodeId
    ): StoredNode {
        val type = obj["type"].strictString()
            ?: throw IngestError.Malformed("Node '$name' is missing 'type'")
        // NodeRef is the one node category whose canonical form carries a
        // Hash rather than a NodeId. Two ingest forms:
        //   - Local ref: { "target": "<author-id>" } — the target is a node in
        //     this document, recorded as a StoredNode.RawNodeRef carrying its
        //     in-document NodeId; Hasher.finalize resolves it to a hash.
        //   - Cross-store ref (Q-043 step 3a): { "targetHash": "<hex>" } — the
        //     target is held in a peer store, so its content hash is given
        //     directly. Materialized as a canonical Node.NodeRef immediately;
        //     finalize keeps it as-is, and the verifier/interpreter resolve the
        //     hash through the federation resolver at use time.
        // Exactly one of `target` / `targetHash` must be present.
        if (type == "NodeRef") {
            val ctx = "node '$name'"
            val targetHashHex = obj["targetHash"].strictString()
            if (targetHashHex != null) {
                if (obj["target"] != null) {
                    throw IngestError.Malformed(
                        "NodeRef in $ctx must declare exactly one of 'target' (local author id) " +
                            "or 'targetHash' (cross-store content hash), not both"
                    )
                }
                return StoredNode.Canonical(Node.NodeRef(target = parseTargetHash(targetHashHex, "$ctx.targetHash")))
            }
            val targetId = obj.requireRef("target", ctx, resolve)
            return StoredNode.RawNodeRef(targetId)
        }
        // ModuleManifest (N-046) likewise carries content hashes — one per
        // export target — that ingest cannot yet compute, so it is recorded as
        // a raw entry whose export targets are in-document NodeIds. Hasher.
        // finalize resolves each to a hash and admits the canonical node.
        if (type == "ModuleManifest") {
            return buildRawModuleManifest(name, obj, resolve)
        }
        return StoredNode.Canonical(buildNode(name, obj, resolve))
    }

    /**
     * Build the pre-finalization [StoredNode.RawModuleManifest] for an N-046
     * ModuleManifest. Each export's `target` is recorded as an in-document
     * NodeId (resolved to a content hash by `Hasher.finalize`); `declaredEffects`
     * are EffectCategory NodeIds carried through unchanged; `displayName` and
     * the optional hex-encoded `manifestSignature` are metadata.
     *
     * JSON schema:
     * ```
     * { "type": "ModuleManifest",
     *   "exports": [
     *     { "target": <id>, "declaredEffects": [<id>, ...]?, "displayName": <string> },
     *     ...
     *   ],
     *   "manifestSignature": <hex string>?  }
     * ```
     */
    private fun buildRawModuleManifest(
        name: String,
        obj: JsonObject,
        resolve: (String, String) -> NodeId,
    ): StoredNode.RawModuleManifest {
        val ctx = "node '$name'"
        val exportsArr = obj["exports"]?.jsonArray
            ?: throw IngestError.Malformed("Missing or non-array 'exports' in $ctx")
        if (exportsArr.isEmpty()) {
            throw IngestError.Malformed(
                "ModuleManifest in $ctx must declare at least one export"
            )
        }
        val exports = exportsArr.mapIndexed { i, e ->
            val expObj = (e as? JsonObject)
                ?: throw IngestError.Malformed("Element $i of 'exports' in $ctx must be an object")
            val expCtx = "$ctx.exports[$i]"
            RawManifestExport(
                target = expObj.requireRef("target", expCtx, resolve),
                declaredEffects = expObj.optionalRefList("declaredEffects", expCtx, resolve),
                displayName = expObj.requireString("displayName", expCtx),
            )
        }
        val sigElement = obj["manifestSignature"]
        val signature = if (sigElement == null ||
            (sigElement is JsonPrimitive && sigElement.contentOrNull == null)
        ) {
            null
        } else {
            val hex = sigElement.strictString()
                ?: throw IngestError.Malformed("'manifestSignature' in $ctx must be a hex string")
            hexDecode(hex, "$ctx.manifestSignature")
        }
        return StoredNode.RawModuleManifest(exports = exports, manifestSignature = signature)
    }

    private fun buildNode(
        name: String,
        obj: JsonObject,
        resolve: (String, String) -> NodeId
    ): Node {
        val type = obj["type"].strictString()
            ?: throw IngestError.Malformed("Node '$name' is missing 'type'")
        val ctx = "node '$name'"
        return when (type) {
            "IntLit" -> Node.IntLit(obj.requireLong("value", ctx))
            "FloatLit" -> Node.FloatLit(obj.requireDouble("value", ctx))
            "StringLit" -> Node.StringLit(obj.requireString("value", ctx))
            "BoolLit" -> Node.BoolLit(obj.requireBoolean("value", ctx))
            "UnitLit" -> Node.UnitLit
            "BytesLit" -> Node.BytesLit(hexDecode(obj.requireString("value", ctx), ctx))

            "PrimitiveType" -> {
                val kind = obj.requireString("kind", ctx)
                Node.PrimitiveType(parsePrimitive(kind, ctx))
            }
            "ProductType" -> Node.ProductType(obj.requireRefList("fields", ctx, resolve))
            "ProductTypeField" -> Node.ProductTypeField(
                fieldName = obj.requireString("name", ctx),
                fieldType = obj.requireRef("fieldType", ctx, resolve)
            )
            "SumType" -> Node.SumType(obj.requireRefList("cases", ctx, resolve))
            "SumTypeCase" -> Node.SumTypeCase(
                caseName = obj.requireString("name", ctx),
                caseType = obj.optionalRef("caseType", ctx, resolve)
            )
            "FunctionType" -> Node.FunctionType(
                parameters = obj.requireRefList("parameters", ctx, resolve),
                result = obj.requireRef("result", ctx, resolve),
                effects = obj.optionalRefList("effects", ctx, resolve),
                effectProjections = obj.optionalEffectProjections("effectProjections", ctx, resolve)
            )
            "TypeParameter" -> Node.TypeParameter(
                name = obj.requireString("name", ctx),
                bound = obj.optionalRef("bound", ctx, resolve)
            )
            "ForallType" -> Node.ForallType(
                typeParameters = obj.requireRefList("typeParameters", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )
            "RecursiveType" -> Node.RecursiveType(
                body = obj.requireRef("body", ctx, resolve)
            )
            "RecursiveSelf" -> Node.RecursiveSelf(
                depth = obj.optionalInt("depth", ctx) ?: 0,
            )
            "RecursiveProjection" -> Node.RecursiveProjection(
                recursiveType = obj.requireRef("recursiveType", ctx, resolve),
                path = obj.requireProjectionPath("path", ctx)
            )

            "Lambda" -> Node.Lambda(
                parameters = obj.requireRefList("parameters", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve),
                effects = obj.optionalRefList("effects", ctx, resolve)
            )
            "TypeAbstraction" -> Node.TypeAbstraction(
                typeParameters = obj.requireRefList("typeParameters", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )
            "ParameterDecl" -> Node.ParameterDecl(
                name = obj.requireString("name", ctx),
                paramType = obj.requireRef("paramType", ctx, resolve)
            )
            "Application" -> Node.Application(
                function = obj.requireRef("function", ctx, resolve),
                arguments = obj.requireRefList("arguments", ctx, resolve),
                typeArguments = obj.optionalRefList("typeArguments", ctx, resolve),
                effectInstances = obj.optionalRefList("effectInstances", ctx, resolve)
            )
            "Let" -> Node.Let(
                name = obj.requireString("name", ctx),
                value = obj.requireRef("value", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )
            "VarRef" -> Node.VarRef(binder = obj.requireRef("binder", ctx, resolve))

            // NodeRef is handled in buildStored — it's the one node category
            // whose canonical form carries a Hash rather than a NodeId, and
            // ingest produces a StoredNode.RawNodeRef instead of a Node.

            "ForeignNode" -> Node.ForeignNode(
                target = obj.requireString("target", ctx),
                foreignType = obj.requireRef("foreignType", ctx, resolve),
                effects = obj.optionalRefList("effects", ctx, resolve),
                effectProjections = obj.optionalEffectProjections("effectProjections", ctx, resolve)
            )

            "Match" -> Node.Match(
                scrutinee = obj.requireRef("scrutinee", ctx, resolve),
                cases = obj.requireRefList("cases", ctx, resolve)
            )
            "Fixpoint" -> Node.Fixpoint(
                recursionType = obj.requireRef("recursionType", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )
            "Attempt" -> Node.Attempt(
                body = obj.requireRef("body", ctx, resolve)
            )

            "ProductValue" -> Node.ProductValue(
                ofType = obj.requireRef("ofType", ctx, resolve),
                fields = obj.requireRefList("fields", ctx, resolve)
            )
            "ProductFieldValue" -> Node.ProductFieldValue(
                fieldName = obj.requireString("fieldName", ctx),
                value = obj.requireRef("value", ctx, resolve)
            )
            "ProductFieldGet" -> Node.ProductFieldGet(
                target = obj.requireRef("target", ctx, resolve),
                fieldName = obj.requireString("fieldName", ctx)
            )
            "SumValue" -> Node.SumValue(
                ofType = obj.requireRef("ofType", ctx, resolve),
                caseName = obj.requireString("caseName", ctx),
                payload = obj.optionalRef("payload", ctx, resolve)
            )
            "MatchCase" -> Node.MatchCase(
                pattern = obj.requireRef("pattern", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )
            "Pattern" -> {
                val kind = obj.requireString("kind", ctx)
                val patternType = obj.requireRef("patternType", ctx, resolve)
                when (kind) {
                    "literal" -> Node.Pattern.LiteralPattern(
                        patternType = patternType,
                        literal = obj.requireRef("literal", ctx, resolve)
                    )
                    "variable" -> Node.Pattern.VariablePattern(
                        patternType = patternType,
                        name = obj.requireString("name", ctx)
                    )
                    "wildcard" -> Node.Pattern.WildcardPattern(
                        patternType = patternType
                    )
                    "constructor" -> Node.Pattern.ConstructorPattern(
                        patternType = patternType,
                        caseName = obj.requireString("caseName", ctx),
                        payloadPattern = obj.optionalRef("payloadPattern", ctx, resolve)
                    )
                    else -> throw IngestError.Malformed(
                        "Unknown Pattern kind '$kind' in $ctx (expected literal, variable, wildcard, or constructor)"
                    )
                }
            }

            "EffectCategory" -> Node.EffectCategory(
                categoryName = obj.requireString("categoryName", ctx),
                parameters = obj.optionalRefList("parameters", ctx, resolve)
            )
            "EffectDecl" -> Node.EffectDecl(
                effectType = obj.requireRef("effectType", ctx, resolve),
                parameters = obj.optionalRefList("parameters", ctx, resolve)
            )
            "CapabilityScope" -> Node.CapabilityScope(
                capabilities = obj.requireRefList("capabilities", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )

            "Handler" -> Node.Handler(
                intercept = obj.requireRef("intercept", ctx, resolve),
                handle = obj.requireRef("handle", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )

            "StateMachine" -> Node.StateMachine(
                transitionFn = obj.requireRef("transitionFn", ctx, resolve),
                initialState = obj.requireRef("initialState", ctx, resolve),
                inputStreams = obj.requireRefList("inputStreams", ctx, resolve),
                outputStreams = obj.optionalRefList("outputStreams", ctx, resolve),
                effects = obj.optionalRefList("effects", ctx, resolve)
            )
            "EventStream" -> Node.EventStream(
                eventType = obj.requireRef("eventType", ctx, resolve),
                streamKind = parseStreamKind(obj.requireString("streamKind", ctx), ctx),
                bufferSize = obj.optionalInt("bufferSize", ctx)?.also {
                    // Q-066: the canonical encoding carries bufferSize as a
                    // CBOR uint, so a negative value is unencodable; it is
                    // also meaningless as a channel capacity. Zero is
                    // rejected too (review hashing Low): the encoder uses 0
                    // as the "unset" sentinel, so an explicit 0 would hash
                    // identically to an absent bufferSize, and the verifier
                    // rejects bufferSize <= 0 anyway.
                    if (it <= 0) throw IngestError.Malformed(
                        "EventStream bufferSize in $ctx must be positive, got $it"
                    )
                },
                overflowPolicy = obj.optionalOverflowPolicy("overflowPolicy", ctx),
                consumerMode = obj.optionalConsumerMode("consumerMode", ctx),
                source = obj.optionalRef("source", ctx, resolve),
            )
            "Transition" -> Node.Transition(
                guard = obj.optionalRef("guard", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )

            "Schema" -> Node.Schema(
                schemaName = obj.requireString("schemaName", ctx),
                valueType = obj.requireRef("valueType", ctx, resolve),
                invariants = obj.optionalRefList("invariants", ctx, resolve)
            )
            "Invariant" -> Node.Invariant(
                invariantName = obj.requireString("invariantName", ctx),
                targetSchema = obj.requireRef("targetSchema", ctx, resolve),
                body = obj.requireRef("body", ctx, resolve)
            )

            "ToolDef" -> Node.ToolDef(
                name = obj.requireString("name", ctx),
                description = obj.requireString("description", ctx),
                parameterSchema = obj.requireRef("parameterSchema", ctx, resolve),
                implementation = obj.requireRef("implementation", ctx, resolve)
            )

            "ResponseSchemaSpec" -> Node.ResponseSchemaSpec(
                schema = obj.requireRef("schema", ctx, resolve)
            )

            else -> throw IngestError.Malformed(
                "Unknown node type '$type' in $ctx (current set: N-001..N-029, N-032..N-048)"
            )
        }
    }

    private fun parsePrimitive(kind: String, ctx: String): Primitive =
        when (kind) {
            "Int" -> Primitive.Int
            "Float" -> Primitive.Float
            "String" -> Primitive.String
            "Bool" -> Primitive.Bool
            "Unit" -> Primitive.Unit
            "Bytes" -> Primitive.Bytes
            else -> throw IngestError.Malformed("Unknown primitive '$kind' in $ctx")
        }

    private fun parseStreamKind(kind: String, ctx: String): StreamKind =
        when (kind) {
            "external", "External" -> StreamKind.External
            "internal", "Internal" -> StreamKind.Internal
            "output", "Output" -> StreamKind.Output
            else -> throw IngestError.Malformed(
                "Unknown EventStream streamKind '$kind' in $ctx " +
                    "(expected external, internal, or output)"
            )
        }

    /**
     * Parse an EventStream's optional `consumerMode` content field
     * (Layer 6 step 3 slice 3.6). Accepts the two enum names
     * `Single` / `Broadcast` (case-insensitive) as JSON strings.
     */
    internal fun parseConsumerMode(name: String, ctx: String): ConsumerMode =
        when (name) {
            "Single", "single" -> ConsumerMode.Single
            "Broadcast", "broadcast" -> ConsumerMode.Broadcast
            else -> throw IngestError.Malformed(
                "Unknown consumerMode '$name' in $ctx " +
                    "(expected Single or Broadcast)"
            )
        }

    /**
     * Internal entry point used by the [optionalOverflowPolicy] file-private
     * helper. Delegates to [parseOverflowPolicy] which is private to this
     * object.
     */
    internal fun parseOverflowPolicyInternal(element: JsonElement, ctx: String): OverflowPolicy =
        parseOverflowPolicy(element, ctx)

    /**
     * Parse an EventStream's optional `overflowPolicy` content field. Accepts
     * either a string shorthand (one of `BlockProducer`, `DropNewest`,
     * `DropOldest` — the three nullary variants) or an object
     * `{ "kind": "Sample", "intervalNanos": <long> }` for the parameterized
     * variant. Case-insensitive comparison on the shorthand names.
     */
    private fun parseOverflowPolicy(element: JsonElement, ctx: String): OverflowPolicy {
        if (element is JsonPrimitive) {
            val name = element.strictString()
                ?: throw IngestError.Malformed("overflowPolicy in $ctx must be a string or an object")
            return when (name) {
                "BlockProducer", "blockProducer", "block_producer", "block" -> OverflowPolicy.BlockProducer
                "DropNewest", "dropNewest", "drop_newest", "dropNew" -> OverflowPolicy.DropNewest
                "DropOldest", "dropOldest", "drop_oldest", "dropOld" -> OverflowPolicy.DropOldest
                else -> throw IngestError.Malformed(
                    "Unknown overflowPolicy '$name' in $ctx " +
                        "(expected BlockProducer, DropNewest, DropOldest, " +
                        "or an object {kind:Sample, intervalNanos:...})"
                )
            }
        }
        val obj = element.requireObject("$ctx.overflowPolicy")
        val kind = obj["kind"].strictString()
            ?: throw IngestError.Malformed("overflowPolicy object in $ctx missing 'kind' field")
        return when (kind) {
            "BlockProducer" -> OverflowPolicy.BlockProducer
            "DropNewest" -> OverflowPolicy.DropNewest
            "DropOldest" -> OverflowPolicy.DropOldest
            "Sample" -> {
                val interval = obj["intervalNanos"].strictLong()
                    ?: throw IngestError.Malformed(
                        "overflowPolicy Sample in $ctx missing 'intervalNanos' Long"
                    )
                if (interval <= 0L) {
                    throw IngestError.Malformed(
                        "overflowPolicy Sample in $ctx requires intervalNanos > 0, got $interval"
                    )
                }
                OverflowPolicy.Sample(interval)
            }
            else -> throw IngestError.Malformed(
                "Unknown overflowPolicy.kind '$kind' in $ctx " +
                    "(expected BlockProducer, DropNewest, DropOldest, or Sample)"
            )
        }
    }

    /**
     * Decode a cross-store `targetHash` into a [Hash], rejecting an empty
     * value, an unassigned multi-hash prefix, or a digest of the wrong
     * length with [IngestError.Malformed] (review hashing M3: an unknown
     * prefix used to escape as a raw `IllegalStateException`).
     */
    private fun parseTargetHash(hex: String, ctx: String): Hash {
        val bytes = hexDecode(hex, ctx)
        if (bytes.isEmpty()) throw IngestError.Malformed("Empty content hash in $ctx")
        val fn = HashFunction.fromPrefixOrNull(bytes[0]) ?: throw IngestError.Malformed(
            "Unknown hash function prefix 0x%02x in $ctx (known: %s)".format(
                bytes[0], HashFunction.entries.joinToString { "0x%02x %s".format(it.prefix, it.name) }
            )
        )
        if (bytes.size != 1 + fn.digestSize) throw IngestError.Malformed(
            "Content hash in $ctx with prefix 0x%02x must carry %d digest bytes; got %d".format(
                bytes[0], fn.digestSize, bytes.size - 1
            )
        )
        return Hash(bytes)
    }

    private fun hexDecode(s: String, ctx: String): ByteArray {
        val clean = s.removePrefix("0x").removePrefix("0X")
        if (clean.length % 2 != 0) throw IngestError.Malformed("Invalid hex length in $ctx")
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw IngestError.Malformed("Invalid hex character in $ctx")
            ((hi shl 4) or lo).toByte()
        }
    }

    /**
     * Q-040 JSON depth pre-scan: count `{` / `[` openings minus `}` / `]`
     * closings, raising [IngestError.ResourceExhaustion] with
     * [ExhaustionKind.JsonDepth] if the nesting ever exceeds [maxDepth].
     *
     * The scan is linear, char-by-char, and ignores brace / bracket
     * characters that appear inside string literals (so a JSON document
     * with a deeply-nested embedded string does not falsely trip the
     * limit). Escape handling is single-character: `\X` skips the next
     * char unconditionally. This is sufficient for the threat model
     * (well-formed JSON; the real parser still runs after this check
     * and will reject malformed escapes downstream).
     *
     * Runs before `kotlinx.serialization.json.Json.parseToJsonElement`,
     * which is itself recursive and has no exposed depth cap — without
     * this pre-scan, a deeply-nested document crashes the JVM with
     * `StackOverflowError` inside kotlinx's parser before any
     * Strand-side code runs.
     */
    fun validateJsonDepth(text: String, maxDepth: Int) {
        var depth = 0
        var inString = false
        var escape = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > maxDepth) {
                        throw IngestError.ResourceExhaustion(
                            kind = ExhaustionKind.JsonDepth,
                            current = depth.toLong(),
                            limit = maxDepth.toLong(),
                        )
                    }
                }
                '}', ']' -> if (depth > 0) depth--
            }
            i++
        }
    }

}

/**
 * Structured ingest errors.
 *
 * Pre-Q-040 every ingest failure raised the original `IngestError(message)`
 * constructor; Q-040 promotes this to a sealed hierarchy so a host that
 * catches an ingest failure can distinguish a malformed input ("the JSON
 * is wrong") from a resource-exhaustion ("the JSON is too big / too deep /
 * too many nodes") without parsing strings.
 *
 * [Malformed] carries every pre-Q-040 string-based failure shape; its
 * `message` is identical to what the legacy constructor produced.
 * [ResourceExhaustion] is the new shape, carrying the kind, the actual
 * count, and the configured limit.
 */
sealed class IngestError(message: String) : RuntimeException(message) {
    /**
     * Pre-Q-040 ingest failure shape — missing fields, unknown node types,
     * type mismatches, invalid hex, etc. The `message` argument is the
     * same string the legacy `IngestError(message)` constructor produced.
     */
    class Malformed(message: String) : IngestError(message)

    /**
     * Q-040: an ingest-time resource cap was exceeded. [kind] identifies
     * which dimension; [current] is the observed count at the moment of
     * rejection; [limit] is the configured cap from
     * [EvaluationLimits].
     */
    class ResourceExhaustion(
        val kind: ExhaustionKind,
        val current: Long,
        val limit: Long,
    ) : IngestError(
        "ingest resource exhausted: kind=$kind current=$current limit=$limit"
    )
}

// ----- Internal helpers -----

private fun JsonElement.requireObject(ctx: String): JsonObject =
    this as? JsonObject ?: throw IngestError.Malformed("Expected object at $ctx")

private fun JsonObject.requireString(field: String, ctx: String): String =
    this[field].strictString()
        ?: throw IngestError.Malformed("Missing or non-string field '$field' in $ctx")

private fun JsonObject.requireLong(field: String, ctx: String): Long {
    val v = this[field] ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return v.strictLong()
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be an integer (a JSON number, not a string)")
}

private fun JsonObject.requireDouble(field: String, ctx: String): Double {
    val v = this[field] ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return v.nonStringScalar()?.doubleOrNull
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be a number (a JSON number, not a string)")
}

private fun JsonObject.requireBoolean(field: String, ctx: String): Boolean {
    val v = this[field] ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return v.nonStringScalar()?.booleanOrNull
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be a boolean (true/false, not a string)")
}

/*
 * Review M4 (core): strict scalar accessors. kotlinx-serialization's
 * `JsonPrimitive.content` / `longOrNull` / `booleanOrNull` read the literal
 * text whether or not it was quoted, so `"name": 7` ingested as "7",
 * `"value": "42"` as the Int 42, `"value": "true"` as a Bool, and a
 * numeric reference as the author id "7". Ingest now checks
 * [JsonPrimitive.isString] explicitly: a string field must be a JSON
 * string, a numeric or boolean field must not be one.
 */

/** The content of a JSON string; null for anything else (absent, null, number, boolean, object, array). */
private fun JsonElement?.strictString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A non-string, non-null JSON scalar (a number or boolean literal); null otherwise. */
private fun JsonElement?.nonStringScalar(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }

/** An integral JSON number; null for a string (even "42"), a non-integral number, or anything else. */
private fun JsonElement?.strictLong(): Long? = nonStringScalar()?.longOrNull

private fun JsonObject.requireRef(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId
): NodeId {
    val name = this[field].strictString()
        ?: throw IngestError.Malformed("Missing or non-string ref field '$field' in $ctx")
    return resolve(name, "$ctx.$field")
}

private fun JsonObject.optionalRef(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId
): NodeId? {
    val v = this[field] ?: return null
    if (v is JsonPrimitive && v.contentOrNull == null) return null
    val name = v.strictString()
        ?: throw IngestError.Malformed("Ref field '$field' in $ctx must be a string id or absent")
    return resolve(name, "$ctx.$field")
}

private fun JsonObject.requireRefList(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId
): List<NodeId> {
    val arr = this[field]?.jsonArray
        ?: throw IngestError.Malformed("Missing or non-array field '$field' in $ctx")
    return arr.mapIndexed { i, e ->
        val s = e.strictString()
            ?: throw IngestError.Malformed("Element $i of '$field' in $ctx must be a string id")
        resolve(s, "$ctx.$field[$i]")
    }
}

private fun JsonObject.optionalRefList(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId
): List<NodeId> {
    val arr = this[field]?.jsonArray ?: return emptyList()
    return arr.mapIndexed { i, e ->
        val s = e.strictString()
            ?: throw IngestError.Malformed("Element $i of '$field' in $ctx must be a string id")
        resolve(s, "$ctx.$field[$i]")
    }
}

private fun JsonObject.optionalInt(field: String, ctx: String): Int? {
    val v = this[field] ?: return null
    if (v is JsonPrimitive && v.contentOrNull == null) return null
    val long = v.strictLong()
        ?: throw IngestError.Malformed("Optional field '$field' in $ctx must be an integer (a JSON number, not a string) if present")
    if (long < Int.MIN_VALUE.toLong() || long > Int.MAX_VALUE.toLong()) {
        throw IngestError.Malformed("Field '$field' in $ctx must fit in 32 bits, got $long")
    }
    return long.toInt()
}

private fun JsonObject.optionalOverflowPolicy(field: String, ctx: String): OverflowPolicy? {
    val v = this[field] ?: return null
    if (v is JsonPrimitive && v.contentOrNull == null) return null
    return JsonIngest.parseOverflowPolicyInternal(v, "$ctx.$field")
}

private fun JsonObject.optionalConsumerMode(field: String, ctx: String): ConsumerMode? {
    val v = this[field] ?: return null
    if (v is JsonPrimitive && v.contentOrNull == null) return null
    val name = v.strictString()
        ?: throw IngestError.Malformed("Optional field '$field' in $ctx must be a string if present")
    return JsonIngest.parseConsumerMode(name, "$ctx.$field")
}

/**
 * Parse a [Node.ForeignNode.effectProjections] or
 * [Node.FunctionType.effectProjections] field (Q-039). Absent or empty
 * yields an empty list. Each entry is:
 *
 * ```
 * {
 *   "category": "<author-id of EffectCategory>",
 *   "sources": [ <source>, ... ]
 * }
 * ```
 *
 * where each source is either
 * `{"kind": "ArgRef", "index": N}` or `{"kind": "LiteralNode", "target": "<author-id>"}`.
 */
private fun JsonObject.optionalEffectProjections(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId,
): List<EffectProjection> {
    val v = this[field] ?: return emptyList()
    val arr = (v as? kotlinx.serialization.json.JsonArray)
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be an array if present")
    return arr.mapIndexed { i, e ->
        val obj = e as? JsonObject
            ?: throw IngestError.Malformed("Element $i of '$field' in $ctx must be an object")
        val projCtx = "$ctx.$field[$i]"
        val categoryName = obj["category"].strictString()
            ?: throw IngestError.Malformed("Missing or non-string 'category' in $projCtx")
        val categoryId = resolve(categoryName, "$projCtx.category")
        val sourcesArr = obj["sources"] as? kotlinx.serialization.json.JsonArray
            ?: throw IngestError.Malformed("Missing or non-array 'sources' in $projCtx")
        val sources = sourcesArr.mapIndexed { j, sEl ->
            val sObj = sEl as? JsonObject
                ?: throw IngestError.Malformed("Element $j of 'sources' in $projCtx must be an object")
            val srcCtx = "$projCtx.sources[$j]"
            val kind = sObj["kind"].strictString()
                ?: throw IngestError.Malformed("Missing or non-string 'kind' in $srcCtx")
            when (kind) {
                "ArgRef" -> {
                    val idx = sObj["index"].strictLong()
                        ?: throw IngestError.Malformed("Missing or non-integer 'index' in $srcCtx")
                    if (idx < 0 || idx > Int.MAX_VALUE.toLong()) {
                        throw IngestError.Malformed(
                            "ArgRef.index out of range in $srcCtx: $idx"
                        )
                    }
                    ProjectionSource.ArgRef(idx.toInt())
                }
                "LiteralNode" -> {
                    val targetName = sObj["target"].strictString()
                        ?: throw IngestError.Malformed("Missing or non-string 'target' in $srcCtx")
                    val targetId = resolve(targetName, "$srcCtx.target")
                    ProjectionSource.LiteralNode(targetId)
                }
                else -> throw IngestError.Malformed(
                    "Unknown ProjectionSource.kind '$kind' in $srcCtx " +
                        "(expected ArgRef or LiteralNode)"
                )
            }
        }
        EffectProjection(category = categoryId, sources = sources)
    }
}

/**
 * Parse a [Node.RecursiveProjection.path] field (N-048). The path is a
 * non-empty JSON array of selector-step objects, each one of:
 *
 * ```
 * { "step": "Case",   "caseName":  "<string>" }
 * { "step": "Field",  "fieldName": "<string>" }
 * { "step": "Unfold" }
 * ```
 *
 * The path is positional — order is semantically load-bearing — so it is
 * preserved in declaration order and never sorted. An empty or absent path
 * is rejected: a RecursiveProjection must select at least one position.
 */
private fun JsonObject.requireProjectionPath(field: String, ctx: String): List<ProjectionStep> {
    val arr = this[field]?.jsonArray
        ?: throw IngestError.Malformed("Missing or non-array field '$field' in $ctx")
    if (arr.isEmpty()) {
        throw IngestError.Malformed(
            "RecursiveProjection '$field' in $ctx must select at least one position " +
                "(an empty path is ill-formed)"
        )
    }
    return arr.mapIndexed { i, e ->
        val stepObj = e as? JsonObject
            ?: throw IngestError.Malformed("Element $i of '$field' in $ctx must be an object")
        val stepCtx = "$ctx.$field[$i]"
        val step = stepObj["step"].strictString()
            ?: throw IngestError.Malformed("Missing or non-string 'step' in $stepCtx")
        when (step) {
            "Case" -> ProjectionStep.Case(
                caseName = stepObj["caseName"].strictString()
                    ?: throw IngestError.Malformed("Missing or non-string 'caseName' in $stepCtx")
            )
            "Field" -> ProjectionStep.Field(
                fieldName = stepObj["fieldName"].strictString()
                    ?: throw IngestError.Malformed("Missing or non-string 'fieldName' in $stepCtx")
            )
            "Unfold" -> ProjectionStep.Unfold
            else -> throw IngestError.Malformed(
                "Unknown RecursiveProjection step '$step' in $stepCtx " +
                    "(expected Case, Field, or Unfold)"
            )
        }
    }
}

