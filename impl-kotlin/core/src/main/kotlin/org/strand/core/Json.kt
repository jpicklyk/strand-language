package org.strand.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
 *   NodeRef       { "type": "NodeRef", "target": <id> }
 *
 * Agent-native capabilities:
 *   ToolDef             { "type": "ToolDef", "name": <string>, "description": <string>,
 *                         "parameterSchema": <Schema id>, "implementation": <Expression id> }
 *   ResponseSchemaSpec  { "type": "ResponseSchemaSpec", "schema": <Schema id> }
 *
 * State machines (excerpt — full schema in impl/CLAUDE.md):
 *   EventStream  { "type": "EventStream", "eventType": <id>,
 *                  "streamKind": "external|internal|output",
 *                  "bufferSize": <int>?  (optional; default 1024 at runtime),
 *                  "overflowPolicy": <policy>?  (optional; default BlockProducer) }
 *
 *   overflowPolicy may be either a shorthand string ("BlockProducer",
 *   "DropNewest", "DropOldest") or an object form for the parameterized
 *   variant: `{ "kind": "Sample", "intervalNanos": <long> }`. When omitted
 *   (or for any of the three nullary variants), the canonical encoder gates
 *   both `bufferSize` and `overflowPolicy` on non-default values so pre-step-3
 *   EventStream hashes are byte-identical to the new form (additive versioning).
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
        return parse(parser.parseToJsonElement(text), limits)
    }

    fun parse(element: JsonElement): IngestResult = parse(element, EvaluationLimits.DEFAULTS)

    fun parse(element: JsonElement, limits: EvaluationLimits): IngestResult {
        val obj = element.requireObject("root document")

        val version = obj["version"]?.jsonPrimitive?.intOrNull
        require(version == 1) { "Unsupported or missing schema version (expected 1, got $version)" }

        val rootName = obj["root"]?.jsonPrimitive?.contentOrNull
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
     * Internal slot type for the two-pass JSON ingest. A [Pending] slot is one
     * whose [NodeId] has been allocated but whose [StoredNode] has not yet
     * been materialized; pass 2 transitions every slot to [Resolved]. This
     * type is deliberately local to [JsonIngest].
     */
    private sealed class IngestSlot {
        object Pending : IngestSlot()
        data class Resolved(val stored: StoredNode) : IngestSlot()
    }

    private fun buildStored(
        name: String,
        obj: JsonObject,
        resolve: (String, String) -> NodeId
    ): StoredNode {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: throw IngestError.Malformed("Node '$name' is missing 'type'")
        // NodeRef is the one node category whose canonical form carries a
        // Hash rather than a NodeId; during ingest we don't yet have hashes,
        // so we record it as a StoredNode.RawNodeRef carrying the target's
        // in-document NodeId. Hasher.finalize replaces it with a canonical
        // Node.NodeRef once hashes are computed.
        if (type == "NodeRef") {
            val ctx = "node '$name'"
            val targetId = obj.requireRef("target", ctx, resolve)
            return StoredNode.RawNodeRef(targetId)
        }
        return StoredNode.Canonical(buildNode(name, obj, resolve))
    }

    private fun buildNode(
        name: String,
        obj: JsonObject,
        resolve: (String, String) -> NodeId
    ): Node {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
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
                bufferSize = obj.optionalInt("bufferSize", ctx),
                overflowPolicy = obj.optionalOverflowPolicy("overflowPolicy", ctx),
                consumerMode = obj.optionalConsumerMode("consumerMode", ctx),
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
                "Unknown node type '$type' in $ctx (current set: N-001..N-029, N-032..N-045)"
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
            val name = element.contentOrNull
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
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            ?: throw IngestError.Malformed("overflowPolicy object in $ctx missing 'kind' field")
        return when (kind) {
            "BlockProducer" -> OverflowPolicy.BlockProducer
            "DropNewest" -> OverflowPolicy.DropNewest
            "DropOldest" -> OverflowPolicy.DropOldest
            "Sample" -> {
                val interval = obj["intervalNanos"]?.jsonPrimitive?.longOrNull
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
    this[field]?.jsonPrimitive?.contentOrNull
        ?: throw IngestError.Malformed("Missing or non-string field '$field' in $ctx")

private fun JsonObject.requireLong(field: String, ctx: String): Long {
    val prim = this[field]?.jsonPrimitive
        ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return prim.longOrNull
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be an integer")
}

private fun JsonObject.requireDouble(field: String, ctx: String): Double {
    val prim = this[field]?.jsonPrimitive
        ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return prim.doubleOrNull
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be a number")
}

private fun JsonObject.requireBoolean(field: String, ctx: String): Boolean {
    val prim = this[field]?.jsonPrimitive
        ?: throw IngestError.Malformed("Missing field '$field' in $ctx")
    return prim.booleanOrNull
        ?: throw IngestError.Malformed("Field '$field' in $ctx must be a boolean")
}

private fun JsonObject.requireRef(
    field: String,
    ctx: String,
    resolve: (String, String) -> NodeId
): NodeId {
    val name = this[field]?.jsonPrimitive?.contentOrNull
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
    val name = v.jsonPrimitive.contentOrNull
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
        val s = e.jsonPrimitive.contentOrNull
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
        val s = e.jsonPrimitive.contentOrNull
            ?: throw IngestError.Malformed("Element $i of '$field' in $ctx must be a string id")
        resolve(s, "$ctx.$field[$i]")
    }
}

private fun JsonObject.optionalInt(field: String, ctx: String): Int? {
    val v = this[field] ?: return null
    if (v is JsonPrimitive && v.contentOrNull == null) return null
    val long = v.jsonPrimitive.longOrNull
        ?: throw IngestError.Malformed("Optional field '$field' in $ctx must be an integer if present")
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
    val name = v.jsonPrimitive.contentOrNull
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
        val categoryName = obj["category"]?.jsonPrimitive?.contentOrNull
            ?: throw IngestError.Malformed("Missing or non-string 'category' in $projCtx")
        val categoryId = resolve(categoryName, "$projCtx.category")
        val sourcesArr = obj["sources"] as? kotlinx.serialization.json.JsonArray
            ?: throw IngestError.Malformed("Missing or non-array 'sources' in $projCtx")
        val sources = sourcesArr.mapIndexed { j, sEl ->
            val sObj = sEl as? JsonObject
                ?: throw IngestError.Malformed("Element $j of 'sources' in $projCtx must be an object")
            val srcCtx = "$projCtx.sources[$j]"
            val kind = sObj["kind"]?.jsonPrimitive?.contentOrNull
                ?: throw IngestError.Malformed("Missing or non-string 'kind' in $srcCtx")
            when (kind) {
                "ArgRef" -> {
                    val idx = sObj["index"]?.jsonPrimitive?.longOrNull
                        ?: throw IngestError.Malformed("Missing or non-integer 'index' in $srcCtx")
                    if (idx < 0 || idx > Int.MAX_VALUE.toLong()) {
                        throw IngestError.Malformed(
                            "ArgRef.index out of range in $srcCtx: $idx"
                        )
                    }
                    ProjectionSource.ArgRef(idx.toInt())
                }
                "LiteralNode" -> {
                    val targetName = sObj["target"]?.jsonPrimitive?.contentOrNull
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

private val JsonPrimitive.intOrNull: Int?
    get() = this.contentOrNull?.toIntOrNull()
