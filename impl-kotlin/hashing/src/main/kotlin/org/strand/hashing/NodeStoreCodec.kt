package org.strand.hashing

import org.strand.core.EffectProjection
import org.strand.core.Hash
import org.strand.core.ManifestExport
import org.strand.core.Node
import org.strand.core.NodeId
import org.strand.core.OverflowPolicy
import org.strand.core.Primitive
import org.strand.core.ProjectionSource
import org.strand.core.StreamKind
import org.strand.core.ConsumerMode

/**
 * Q-058: the context-free node↔JSON codec for the [PersistentStore].
 *
 * The persistence unit is a **subgraph record**: a hashable root node plus
 * every node reachable from it through non-NodeRef edges (the same set the
 * Q-043 [LocalProgramResolver] gathers into a [SubgraphFetch], including the
 * bound nodes that have no standalone hash). A record serializes as
 *
 * ```json
 * {
 *   "rootHash": "1e…",
 *   "rootIndex": 0,
 *   "nodes": [
 *     { "index": 0, "hash": "1e…", "node": { "type": "Application", … } },
 *     { "index": 1, "node": { "type": "ParameterDecl", … } },   // bound node: no hash
 *     …
 *   ]
 * }
 * ```
 *
 * Every NodeId child is rewritten to its **local index** into the record's
 * `nodes` array (the within-record analogue of the foreign-NodeId keying a
 * [SubgraphFetch] uses), so the record is self-contained and position-stable.
 * A [Node.NodeRef.target] and each [ManifestExport.target] are written as the
 * hash hex directly — they are the existing cross-subgraph boundary. The
 * `hash` field is present only for hashable members; bound nodes
 * ([Node.ParameterDecl] / [Node.TypeParameter] / [Node.RecursiveSelf]) omit it.
 *
 * Reading reassembles a [SubgraphFetch] (re-keyed onto synthetic foreign
 * [NodeId]s = the record indices), which the store then hands to the existing
 * [FederatedProgram.fetchAndAdmit] admission path — so the read is bit-identical
 * to the federation admission already proven by the corpus federation tests,
 * and there is no second, divergent re-base implementation.
 *
 * This codec is the persistent twin of the canonical encoder. It is NOT the
 * canonical encoding itself — it never feeds a hash. The hash of every member
 * is recomputed from the admitted store (`Hasher`) on read, and that recompute
 * is what the integrity checks compare against. The codec only has to be a
 * faithful, reversible serialization of the in-memory [Node] ADT.
 */
internal object NodeStoreCodec {

    // ---- write: SubgraphFetch -> record Js ----

    /** Serialize a [SubgraphFetch] directly to the record JSON. */
    fun encodeFetch(fetch: SubgraphFetch): Js.Obj {
        val order = ArrayList<NodeId>(fetch.nodes.size)
        order.add(fetch.rootId)
        for (id in fetch.nodes.keys) if (id != fetch.rootId) order.add(id)
        val idToIndex = HashMap<NodeId, Int>()
        order.forEachIndexed { i, id -> idToIndex[id] = i }

        val nodesArr = Js.Arr()
        order.forEachIndexed { i, id ->
            val node = fetch.nodes.getValue(id)
            val m = Js.Obj()
            m.put("index", Js.of(i))
            fetch.nodeIdToHash[id]?.let { m.put("hash", Js.of(it.toString())) }
            m.put("node", encodeNode(node) { child -> idToIndex.getValue(child) })
            nodesArr.items.add(m)
        }
        return Js.objOf(
            "rootHash" to Js.of(fetch.rootHash.toString()),
            "rootIndex" to Js.of(0),
            "nodes" to nodesArr,
        )
    }

    private fun ref(index: Int): Js = Js.of(index)
    private fun refList(ids: List<NodeId>, idx: (NodeId) -> Int): Js.Arr =
        Js.arrOf(ids.map { Js.of(idx(it)) })

    private fun encodeNode(node: Node, idx: (NodeId) -> Int): Js.Obj {
        val o = Js.Obj()
        fun t(name: String) { o.put("type", Js.of(name)) }
        when (node) {
            is Node.IntLit -> { t("IntLit"); o.put("value", Js.of(node.value)) }
            is Node.FloatLit -> { t("FloatLit"); o.put("bits", Js.of(node.value.toRawBits())) }
            is Node.StringLit -> { t("StringLit"); o.put("value", Js.of(node.value)) }
            is Node.BoolLit -> { t("BoolLit"); o.put("value", Js.of(node.value)) }
            Node.UnitLit -> t("UnitLit")
            is Node.BytesLit -> { t("BytesLit"); o.put("value", Js.of(hexEncode(node.value))) }

            is Node.PrimitiveType -> { t("PrimitiveType"); o.put("kind", Js.of(node.kind.name)) }
            is Node.ProductType -> { t("ProductType"); o.put("fields", refList(node.fields, idx)) }
            is Node.ProductTypeField -> {
                t("ProductTypeField")
                o.put("name", Js.of(node.fieldName)); o.put("fieldType", ref(idx(node.fieldType)))
            }
            is Node.SumType -> { t("SumType"); o.put("cases", refList(node.cases, idx)) }
            is Node.SumTypeCase -> {
                t("SumTypeCase"); o.put("name", Js.of(node.caseName))
                node.caseType?.let { o.put("caseType", ref(idx(it))) }
            }
            is Node.FunctionType -> {
                t("FunctionType")
                o.put("parameters", refList(node.parameters, idx))
                o.put("result", ref(idx(node.result)))
                o.put("effects", refList(node.effects, idx))
                if (node.effectProjections.isNotEmpty()) {
                    o.put("effectProjections", encodeProjections(node.effectProjections, idx))
                }
            }
            is Node.TypeParameter -> {
                t("TypeParameter"); o.put("name", Js.of(node.name))
                node.bound?.let { o.put("bound", ref(idx(it))) }
            }
            is Node.ForallType -> {
                t("ForallType")
                o.put("typeParameters", refList(node.typeParameters, idx))
                o.put("body", ref(idx(node.body)))
            }
            is Node.RecursiveType -> { t("RecursiveType"); o.put("body", ref(idx(node.body))) }
            is Node.RecursiveSelf -> { t("RecursiveSelf"); o.put("depth", Js.of(node.depth)) }

            is Node.Lambda -> {
                t("Lambda")
                o.put("parameters", refList(node.parameters, idx))
                o.put("body", ref(idx(node.body)))
                o.put("effects", refList(node.effects, idx))
            }
            is Node.TypeAbstraction -> {
                t("TypeAbstraction")
                o.put("typeParameters", refList(node.typeParameters, idx))
                o.put("body", ref(idx(node.body)))
            }
            is Node.ParameterDecl -> {
                t("ParameterDecl"); o.put("name", Js.of(node.name))
                o.put("paramType", ref(idx(node.paramType)))
            }
            is Node.Application -> {
                t("Application")
                o.put("function", ref(idx(node.function)))
                o.put("arguments", refList(node.arguments, idx))
                o.put("typeArguments", refList(node.typeArguments, idx))
                o.put("effectInstances", refList(node.effectInstances, idx))
            }
            is Node.Let -> {
                t("Let"); o.put("name", Js.of(node.name))
                o.put("value", ref(idx(node.value))); o.put("body", ref(idx(node.body)))
            }
            is Node.VarRef -> { t("VarRef"); o.put("binder", ref(idx(node.binder))) }

            is Node.NodeRef -> { t("NodeRef"); o.put("targetHash", Js.of(node.target.toString())) }

            is Node.ModuleManifest -> {
                t("ModuleManifest")
                val exportsArr = Js.Arr()
                for (e in node.exports) {
                    val eo = Js.Obj()
                    eo.put("targetHash", Js.of(e.target.toString()))
                    eo.put("declaredEffects", refList(e.declaredEffects, idx))
                    eo.put("displayName", Js.of(e.displayName))
                    exportsArr.items.add(eo)
                }
                o.put("exports", exportsArr)
                node.manifestSignature?.let { o.put("manifestSignature", Js.of(hexEncode(it))) }
            }

            is Node.EffectCategory -> {
                t("EffectCategory"); o.put("categoryName", Js.of(node.categoryName))
                o.put("parameters", refList(node.parameters, idx))
            }
            is Node.EffectDecl -> {
                t("EffectDecl"); o.put("effectType", ref(idx(node.effectType)))
                o.put("parameters", refList(node.parameters, idx))
            }
            is Node.CapabilityScope -> {
                t("CapabilityScope")
                o.put("capabilities", refList(node.capabilities, idx))
                o.put("body", ref(idx(node.body)))
            }
            is Node.Handler -> {
                t("Handler")
                o.put("intercept", ref(idx(node.intercept)))
                o.put("handle", ref(idx(node.handle)))
                o.put("body", ref(idx(node.body)))
            }

            is Node.ForeignNode -> {
                t("ForeignNode")
                o.put("target", Js.of(node.target))
                o.put("foreignType", ref(idx(node.foreignType)))
                o.put("effects", refList(node.effects, idx))
                if (node.effectProjections.isNotEmpty()) {
                    o.put("effectProjections", encodeProjections(node.effectProjections, idx))
                }
            }

            is Node.Match -> {
                t("Match"); o.put("scrutinee", ref(idx(node.scrutinee)))
                o.put("cases", refList(node.cases, idx))
            }
            is Node.MatchCase -> {
                t("MatchCase"); o.put("pattern", ref(idx(node.pattern)))
                o.put("body", ref(idx(node.body)))
            }
            is Node.Pattern -> {
                t("Pattern")
                o.put("patternType", ref(idx(node.patternType)))
                when (node) {
                    is Node.Pattern.LiteralPattern -> {
                        o.put("kind", Js.of("literal")); o.put("literal", ref(idx(node.literal)))
                    }
                    is Node.Pattern.VariablePattern -> {
                        o.put("kind", Js.of("variable")); o.put("name", Js.of(node.name))
                    }
                    is Node.Pattern.WildcardPattern -> o.put("kind", Js.of("wildcard"))
                    is Node.Pattern.ConstructorPattern -> {
                        o.put("kind", Js.of("constructor")); o.put("caseName", Js.of(node.caseName))
                        node.payloadPattern?.let { o.put("payloadPattern", ref(idx(it))) }
                    }
                }
            }
            is Node.Fixpoint -> {
                t("Fixpoint"); o.put("recursionType", ref(idx(node.recursionType)))
                o.put("body", ref(idx(node.body)))
            }
            is Node.Attempt -> { t("Attempt"); o.put("body", ref(idx(node.body))) }

            is Node.ProductValue -> {
                t("ProductValue"); o.put("ofType", ref(idx(node.ofType)))
                o.put("fields", refList(node.fields, idx))
            }
            is Node.ProductFieldValue -> {
                t("ProductFieldValue"); o.put("fieldName", Js.of(node.fieldName))
                o.put("value", ref(idx(node.value)))
            }
            is Node.ProductFieldGet -> {
                t("ProductFieldGet"); o.put("target", ref(idx(node.target)))
                o.put("fieldName", Js.of(node.fieldName))
            }
            is Node.SumValue -> {
                t("SumValue"); o.put("ofType", ref(idx(node.ofType)))
                o.put("caseName", Js.of(node.caseName))
                node.payload?.let { o.put("payload", ref(idx(it))) }
            }

            is Node.StateMachine -> {
                t("StateMachine")
                o.put("transitionFn", ref(idx(node.transitionFn)))
                o.put("initialState", ref(idx(node.initialState)))
                o.put("inputStreams", refList(node.inputStreams, idx))
                o.put("outputStreams", refList(node.outputStreams, idx))
                o.put("effects", refList(node.effects, idx))
            }
            is Node.EventStream -> {
                t("EventStream"); o.put("eventType", ref(idx(node.eventType)))
                o.put("streamKind", Js.of(node.streamKind.name))
                node.bufferSize?.let { o.put("bufferSize", Js.of(it)) }
                node.overflowPolicy?.let { o.put("overflowPolicy", encodeOverflow(it)) }
                node.consumerMode?.let { o.put("consumerMode", Js.of(it.name)) }
                node.source?.let { o.put("source", ref(idx(it))) }
            }
            is Node.Transition -> {
                t("Transition")
                node.guard?.let { o.put("guard", ref(idx(it))) }
                o.put("body", ref(idx(node.body)))
            }

            is Node.Schema -> {
                t("Schema"); o.put("schemaName", Js.of(node.schemaName))
                o.put("valueType", ref(idx(node.valueType)))
                o.put("invariants", refList(node.invariants, idx))
            }
            is Node.Invariant -> {
                t("Invariant"); o.put("invariantName", Js.of(node.invariantName))
                o.put("targetSchema", ref(idx(node.targetSchema)))
                o.put("body", ref(idx(node.body)))
            }

            is Node.ToolDef -> {
                t("ToolDef"); o.put("name", Js.of(node.name))
                o.put("description", Js.of(node.description))
                o.put("parameterSchema", ref(idx(node.parameterSchema)))
                o.put("implementation", ref(idx(node.implementation)))
            }
            is Node.ResponseSchemaSpec -> {
                t("ResponseSchemaSpec"); o.put("schema", ref(idx(node.schema)))
            }
        }
        return o
    }

    private fun encodeProjections(projs: List<EffectProjection>, idx: (NodeId) -> Int): Js.Arr {
        val arr = Js.Arr()
        for (p in projs) {
            val po = Js.Obj()
            po.put("category", ref(idx(p.category)))
            val srcs = Js.Arr()
            for (s in p.sources) {
                val so = Js.Obj()
                when (s) {
                    is ProjectionSource.ArgRef -> { so.put("kind", Js.of("ArgRef")); so.put("index", Js.of(s.index)) }
                    is ProjectionSource.LiteralNode -> { so.put("kind", Js.of("LiteralNode")); so.put("target", ref(idx(s.target))) }
                }
                srcs.items.add(so)
            }
            po.put("sources", srcs)
            arr.items.add(po)
        }
        return arr
    }

    private fun encodeOverflow(p: OverflowPolicy): Js = when (p) {
        OverflowPolicy.BlockProducer -> Js.of("BlockProducer")
        OverflowPolicy.DropNewest -> Js.of("DropNewest")
        OverflowPolicy.DropOldest -> Js.of("DropOldest")
        is OverflowPolicy.Sample -> Js.objOf("kind" to Js.of("Sample"), "intervalNanos" to Js.of(p.intervalNanos))
    }

    // ---- read: Js -> SubgraphFetch ----

    /**
     * Parse a record JSON into a [SubgraphFetch]. The record's local indices
     * become synthetic foreign NodeIds (the fetch is then admitted by
     * [FederatedProgram.fetchAndAdmit], which re-bases them into the local
     * store). The requested [hash] is the file's key; the parsed `rootHash`
     * must equal it.
     */
    fun decodeFetch(json: Js.Obj, expectedHash: Hash): SubgraphFetch {
        val rootHashHex = json.str("rootHash")
            ?: throw StoreJsonParseException("record missing 'rootHash'")
        val rootHash = Hash.fromHex(rootHashHex)
        if (rootHash != expectedHash) {
            throw NodeResolverIntegrityViolation(expected = expectedHash, actual = rootHash)
        }
        val rootIndex = json.int("rootIndex")
            ?: throw StoreJsonParseException("record missing 'rootIndex'")
        val nodesArr = json.arr("nodes")
            ?: throw StoreJsonParseException("record missing 'nodes' array")

        val nodes = LinkedHashMap<NodeId, Node>()
        val nodeIdToHash = LinkedHashMap<NodeId, Hash>()
        for (item in nodesArr.items) {
            val m = item as? Js.Obj ?: throw StoreJsonParseException("record node entry must be an object")
            val index = m.int("index") ?: throw StoreJsonParseException("record node missing 'index'")
            val nodeObj = m.obj("node") ?: throw StoreJsonParseException("record node missing 'node'")
            val node = decodeNode(nodeObj)
            val id = NodeId(index)
            nodes[id] = node
            m.str("hash")?.let { nodeIdToHash[id] = Hash.fromHex(it) }
        }
        return SubgraphFetch(
            rootHash = rootHash,
            rootId = NodeId(rootIndex),
            nodes = nodes,
            nodeIdToHash = nodeIdToHash,
        )
    }

    private fun Js.Obj.refId(field: String): NodeId =
        NodeId(int(field) ?: throw StoreJsonParseException("node missing ref field '$field'"))

    private fun Js.Obj.optRefId(field: String): NodeId? =
        int(field)?.let { NodeId(it) }

    private fun Js.Obj.refIdList(field: String): List<NodeId> {
        val arr = arr(field) ?: throw StoreJsonParseException("node missing list field '$field'")
        return arr.items.map { NodeId((it as? Js.Num)?.value?.toInt() ?: throw StoreJsonParseException("'$field' entry must be an index")) }
    }

    private fun decodeNode(o: Js.Obj): Node {
        val type = o.str("type") ?: throw StoreJsonParseException("node missing 'type'")
        return when (type) {
            "IntLit" -> Node.IntLit(o.long("value") ?: throw StoreJsonParseException("IntLit missing value"))
            "FloatLit" -> Node.FloatLit(Double.fromBits(o.long("bits") ?: throw StoreJsonParseException("FloatLit missing bits")))
            "StringLit" -> Node.StringLit(o.str("value") ?: throw StoreJsonParseException("StringLit missing value"))
            "BoolLit" -> Node.BoolLit(o.bool("value") ?: throw StoreJsonParseException("BoolLit missing value"))
            "UnitLit" -> Node.UnitLit
            "BytesLit" -> Node.BytesLit(hexDecode(o.str("value") ?: throw StoreJsonParseException("BytesLit missing value")))

            "PrimitiveType" -> Node.PrimitiveType(Primitive.valueOf(o.str("kind") ?: throw StoreJsonParseException("PrimitiveType missing kind")))
            "ProductType" -> Node.ProductType(o.refIdList("fields"))
            "ProductTypeField" -> Node.ProductTypeField(
                fieldName = o.str("name") ?: throw StoreJsonParseException("ProductTypeField missing name"),
                fieldType = o.refId("fieldType"),
            )
            "SumType" -> Node.SumType(o.refIdList("cases"))
            "SumTypeCase" -> Node.SumTypeCase(
                caseName = o.str("name") ?: throw StoreJsonParseException("SumTypeCase missing name"),
                caseType = o.optRefId("caseType"),
            )
            "FunctionType" -> Node.FunctionType(
                parameters = o.refIdList("parameters"),
                result = o.refId("result"),
                effects = o.refIdList("effects"),
                effectProjections = decodeProjections(o.arr("effectProjections")),
            )
            "TypeParameter" -> Node.TypeParameter(
                name = o.str("name") ?: throw StoreJsonParseException("TypeParameter missing name"),
                bound = o.optRefId("bound"),
            )
            "ForallType" -> Node.ForallType(
                typeParameters = o.refIdList("typeParameters"),
                body = o.refId("body"),
            )
            "RecursiveType" -> Node.RecursiveType(body = o.refId("body"))
            "RecursiveSelf" -> Node.RecursiveSelf(depth = o.int("depth") ?: 0)

            "Lambda" -> Node.Lambda(
                parameters = o.refIdList("parameters"),
                body = o.refId("body"),
                effects = o.refIdList("effects"),
            )
            "TypeAbstraction" -> Node.TypeAbstraction(
                typeParameters = o.refIdList("typeParameters"),
                body = o.refId("body"),
            )
            "ParameterDecl" -> Node.ParameterDecl(
                name = o.str("name") ?: throw StoreJsonParseException("ParameterDecl missing name"),
                paramType = o.refId("paramType"),
            )
            "Application" -> Node.Application(
                function = o.refId("function"),
                arguments = o.refIdList("arguments"),
                typeArguments = o.refIdList("typeArguments"),
                effectInstances = o.refIdList("effectInstances"),
            )
            "Let" -> Node.Let(
                name = o.str("name") ?: throw StoreJsonParseException("Let missing name"),
                value = o.refId("value"),
                body = o.refId("body"),
            )
            "VarRef" -> Node.VarRef(binder = o.refId("binder"))

            "NodeRef" -> Node.NodeRef(target = Hash.fromHex(o.str("targetHash") ?: throw StoreJsonParseException("NodeRef missing targetHash")))

            "ModuleManifest" -> {
                val exportsArr = o.arr("exports") ?: throw StoreJsonParseException("ModuleManifest missing exports")
                val exports = exportsArr.items.map { e ->
                    val eo = e as? Js.Obj ?: throw StoreJsonParseException("export must be an object")
                    ManifestExport(
                        target = Hash.fromHex(eo.str("targetHash") ?: throw StoreJsonParseException("export missing targetHash")),
                        declaredEffects = eo.refIdList("declaredEffects"),
                        displayName = eo.str("displayName") ?: throw StoreJsonParseException("export missing displayName"),
                    )
                }
                Node.ModuleManifest(exports = exports, manifestSignature = o.str("manifestSignature")?.let { hexDecode(it) })
            }

            "EffectCategory" -> Node.EffectCategory(
                categoryName = o.str("categoryName") ?: throw StoreJsonParseException("EffectCategory missing categoryName"),
                parameters = o.refIdList("parameters"),
            )
            "EffectDecl" -> Node.EffectDecl(
                effectType = o.refId("effectType"),
                parameters = o.refIdList("parameters"),
            )
            "CapabilityScope" -> Node.CapabilityScope(
                capabilities = o.refIdList("capabilities"),
                body = o.refId("body"),
            )
            "Handler" -> Node.Handler(
                intercept = o.refId("intercept"),
                handle = o.refId("handle"),
                body = o.refId("body"),
            )

            "ForeignNode" -> Node.ForeignNode(
                target = o.str("target") ?: throw StoreJsonParseException("ForeignNode missing target"),
                foreignType = o.refId("foreignType"),
                effects = o.refIdList("effects"),
                effectProjections = decodeProjections(o.arr("effectProjections")),
            )

            "Match" -> Node.Match(scrutinee = o.refId("scrutinee"), cases = o.refIdList("cases"))
            "MatchCase" -> Node.MatchCase(pattern = o.refId("pattern"), body = o.refId("body"))
            "Pattern" -> {
                val kind = o.str("kind") ?: throw StoreJsonParseException("Pattern missing kind")
                val pt = o.refId("patternType")
                when (kind) {
                    "literal" -> Node.Pattern.LiteralPattern(patternType = pt, literal = o.refId("literal"))
                    "variable" -> Node.Pattern.VariablePattern(patternType = pt, name = o.str("name") ?: throw StoreJsonParseException("variable pattern missing name"))
                    "wildcard" -> Node.Pattern.WildcardPattern(patternType = pt)
                    "constructor" -> Node.Pattern.ConstructorPattern(
                        patternType = pt,
                        caseName = o.str("caseName") ?: throw StoreJsonParseException("constructor pattern missing caseName"),
                        payloadPattern = o.optRefId("payloadPattern"),
                    )
                    else -> throw StoreJsonParseException("Unknown Pattern kind '$kind'")
                }
            }
            "Fixpoint" -> Node.Fixpoint(recursionType = o.refId("recursionType"), body = o.refId("body"))
            "Attempt" -> Node.Attempt(body = o.refId("body"))

            "ProductValue" -> Node.ProductValue(ofType = o.refId("ofType"), fields = o.refIdList("fields"))
            "ProductFieldValue" -> Node.ProductFieldValue(
                fieldName = o.str("fieldName") ?: throw StoreJsonParseException("ProductFieldValue missing fieldName"),
                value = o.refId("value"),
            )
            "ProductFieldGet" -> Node.ProductFieldGet(
                target = o.refId("target"),
                fieldName = o.str("fieldName") ?: throw StoreJsonParseException("ProductFieldGet missing fieldName"),
            )
            "SumValue" -> Node.SumValue(
                ofType = o.refId("ofType"),
                caseName = o.str("caseName") ?: throw StoreJsonParseException("SumValue missing caseName"),
                payload = o.optRefId("payload"),
            )

            "StateMachine" -> Node.StateMachine(
                transitionFn = o.refId("transitionFn"),
                initialState = o.refId("initialState"),
                inputStreams = o.refIdList("inputStreams"),
                outputStreams = o.refIdList("outputStreams"),
                effects = o.refIdList("effects"),
            )
            "EventStream" -> Node.EventStream(
                eventType = o.refId("eventType"),
                streamKind = StreamKind.valueOf(o.str("streamKind") ?: throw StoreJsonParseException("EventStream missing streamKind")),
                bufferSize = o.int("bufferSize"),
                overflowPolicy = o.entries["overflowPolicy"]?.let { decodeOverflow(it) },
                consumerMode = o.str("consumerMode")?.let { ConsumerMode.valueOf(it) },
                source = o.optRefId("source"),
            )
            "Transition" -> Node.Transition(guard = o.optRefId("guard"), body = o.refId("body"))

            "Schema" -> Node.Schema(
                schemaName = o.str("schemaName") ?: throw StoreJsonParseException("Schema missing schemaName"),
                valueType = o.refId("valueType"),
                invariants = o.refIdList("invariants"),
            )
            "Invariant" -> Node.Invariant(
                invariantName = o.str("invariantName") ?: throw StoreJsonParseException("Invariant missing invariantName"),
                targetSchema = o.refId("targetSchema"),
                body = o.refId("body"),
            )

            "ToolDef" -> Node.ToolDef(
                name = o.str("name") ?: throw StoreJsonParseException("ToolDef missing name"),
                description = o.str("description") ?: throw StoreJsonParseException("ToolDef missing description"),
                parameterSchema = o.refId("parameterSchema"),
                implementation = o.refId("implementation"),
            )
            "ResponseSchemaSpec" -> Node.ResponseSchemaSpec(schema = o.refId("schema"))

            else -> throw StoreJsonParseException("Unknown node type '$type' in store record")
        }
    }

    private fun decodeProjections(arr: Js.Arr?): List<EffectProjection> {
        if (arr == null) return emptyList()
        return arr.items.map { p ->
            val po = p as? Js.Obj ?: throw StoreJsonParseException("projection must be an object")
            val category = po.refId("category")
            val srcsArr = po.arr("sources") ?: throw StoreJsonParseException("projection missing sources")
            val sources = srcsArr.items.map { s ->
                val so = s as? Js.Obj ?: throw StoreJsonParseException("projection source must be an object")
                when (val kind = so.str("kind")) {
                    "ArgRef" -> ProjectionSource.ArgRef(so.int("index") ?: throw StoreJsonParseException("ArgRef missing index"))
                    "LiteralNode" -> ProjectionSource.LiteralNode(so.refId("target"))
                    else -> throw StoreJsonParseException("Unknown ProjectionSource kind '$kind'")
                }
            }
            EffectProjection(category = category, sources = sources)
        }
    }

    private fun decodeOverflow(js: Js): OverflowPolicy = when (js) {
        is Js.Str -> when (js.value) {
            "BlockProducer" -> OverflowPolicy.BlockProducer
            "DropNewest" -> OverflowPolicy.DropNewest
            "DropOldest" -> OverflowPolicy.DropOldest
            else -> throw StoreJsonParseException("Unknown overflowPolicy '${js.value}'")
        }
        is Js.Obj -> {
            val kind = js.str("kind")
            if (kind == "Sample") OverflowPolicy.Sample(js.long("intervalNanos") ?: throw StoreJsonParseException("Sample missing intervalNanos"))
            else throw StoreJsonParseException("Unknown overflowPolicy object kind '$kind'")
        }
        else -> throw StoreJsonParseException("overflowPolicy must be a string or object")
    }

    private fun hexEncode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex char" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
