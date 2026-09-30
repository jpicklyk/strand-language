package org.strand.corpus.soundness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Generator-side types. Every generated expression carries one. */
sealed class Ty {
    object IntT : Ty() { override fun toString() = "Int" }
    object StrT : Ty() { override fun toString() = "String" }
    object BoolT : Ty() { override fun toString() = "Bool" }
    object OptInt : Ty() { override fun toString() = "Option<Int>" }
    object ListInt : Ty() { override fun toString() = "List<Int>" }

    /** A ToolDef handle (the verifier types it `Bytes`). */
    object Tool : Ty() { override fun toString() = "Tool" }

    data class Fn(val params: List<Ty>, val result: Ty, val effects: Set<Cat>) : Ty() {
        override fun toString() = "(${params.joinToString(", ")}) -> $result ! ${effects.sorted()}"
    }

    /** The record `{a: Int, f: (Int) -> Int ! effects}`. */
    data class Rec(val effects: Set<Cat>) : Ty() {
        override fun toString() = "Rec${effects.sorted()}"
    }

    /** `Schema<Int>` number [k] (see [Schemas]). */
    data class SchemaInt(val k: Int) : Ty() {
        override fun toString() = "Schema$k<Int>"
    }
}

/** One slot of a generated grant pattern. */
sealed class Slot {
    object Wild : Slot() { override fun toString() = "*" }
    data class IntC(val v: Long) : Slot() { override fun toString() = v.toString() }
    data class StrC(val v: String) : Slot() { override fun toString() = "\"$v\"" }
}

/**
 * One generated grant pattern. [sentinel] is the arity-free unrefined pattern
 * `CapabilitySet.ofCategories` builds; otherwise [slots] has one entry per
 * category parameter.
 */
data class PatternSpec(val sentinel: Boolean, val slots: List<Slot>) {
    override fun toString() = if (sentinel) "{*}" else slots.joinToString(", ", "{", "}")
}

/** A generated grant: the granted categories and their patterns. */
data class GrantSpec(val label: String, val patterns: Map<Cat, List<PatternSpec>>) {
    override fun toString(): String =
        "$label " + patterns.entries.joinToString(", ", "[", "]") { (c, ps) -> c.categoryName + ps.joinToString("|") }
}

enum class Mode { Expression, Machine }

/** A generated test case: the program as dag-json plus what the oracle needs to know about it. */
class GenCase(
    val json: String,
    val mode: Mode,
    val rootTy: Ty,
    /** CapabilityScope marker tag to the categories that scope retains. */
    val scopeCaps: Map<Int, Set<Cat>>,
    val grants: List<GrantSpec>,
    /** Construct names the generator used, for the coverage assertion. */
    val features: Set<String>,
    val nodeCount: Int,
)

/**
 * Seeded, type-directed generator of well-typed Strand graphs.
 *
 * Generation is goal-directed: `gen(type, context)` builds an expression of
 * the requested type from the binders in scope, and every construct keeps
 * the verifier's admission rules satisfied by construction (a Lambda
 * declares at least its body's closure, a CapabilityScope retains at least
 * its body's closure, intercepted calls agree with their Handler's
 * signature). The result is emitted as dag-json, so a failing case is
 * directly a corpus entry.
 *
 * Shapes reached deliberately, because the 2026-09-29 review found defects
 * in each: shared DAG nodes (a pool of earlier expressions is re-referenced
 * wherever their free binders are in scope, and literal and type nodes are
 * shared by id), Handlers (over stand-ins whose row is declared on the
 * ForeignNode, on its FunctionType only, or split across both, and over
 * polymorphic callees), CapabilityScope nesting with Handlers, higher-order
 * builtins with effectful callbacks (`List.Map`, `List.Fold`), ToolDef
 * implementations, NodeRefs in term and type position, Q-039 projections
 * with and without EffectDecl instances, Schema positions on shared nodes,
 * and a StateMachine's initialState.
 *
 * A small fraction of constructs are generated *under-declared* on purpose
 * (see [cheat]): the verifier is expected to reject them, and if it admits
 * one the properties observe the consequence.
 */
class ProgramGen(private val ch: Choices) {

    private val nodes = LinkedHashMap<String, JsonObject>()
    private val memo = HashMap<String, String>()
    private var counter = 0
    private var scopeTag = 100
    private val scopeCaps = LinkedHashMap<Int, Set<Cat>>()
    private val features = LinkedHashSet<String>()
    private val pool = ArrayList<E>()
    private val polys = ArrayList<Poly>()

    /** A generated expression: its node id, type, and the generator's model of it. */
    private class E(
        val id: String,
        val ty: Ty,
        /** Direct closure: the categories evaluating this may perform at its own call sites. */
        val fx: Set<Cat>,
        /** Latent reach: rows of callbacks and tool implementations inside. */
        val lat: Set<Cat>,
        /** Binders this expression references freely. */
        val free: Set<String>,
        /** Set when this expression is statically a projected stand-in. */
        val proj: StandIn? = null,
    )

    private class Var(val id: String, val ty: Ty, val proj: StandIn? = null)

    private class Poly(val id: String, val effects: Set<Cat>, val lat: Set<Cat>)

    private data class Ctx(
        val vars: List<Var>,
        /** Categories an expression generated here may perform. */
        val allowed: Set<Cat>,
        val fuel: Int,
        /** Signature the lexically enclosing Handler for each category expects. */
        val handled: Map<Cat, Sig>,
    )

    private class Inst(val ids: List<String>, val fx: Set<Cat>, val lat: Set<Cat>, val free: Set<String>)

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * This case's weights for the [genInt] alternatives. Each non-leaf
     * alternative is switched off for the whole program about one time in
     * four, so programs concentrate on different subsets of constructs
     * rather than all resembling the average.
     */
    private var intWeights: IntArray = INT_WEIGHTS

    /** Whether this program declares `Filesystem.Write` with its path parameter. */
    private var writeHasPath = true

    fun generate(): GenCase {
        val machine = ch.oneIn(8)
        val fuel = 3 + ch.int(3)
        writeHasPath = !ch.oneIn(4)
        intWeights = IntArray(INT_WEIGHTS.size) { i -> if (i > 0 && ch.oneIn(4)) 0 else INT_WEIGHTS[i] }
        val rootTy: Ty
        val root: String
        if (machine) {
            features += "machine"
            rootTy = Ty.IntT
            root = genMachine(fuel)
        } else {
            rootTy = when (ch.weighted(intArrayOf(8, 1, 1, 1, 1))) {
                0 -> Ty.IntT
                1 -> Ty.StrT
                2 -> Ty.BoolT
                3 -> Ty.OptInt
                else -> Ty.ListInt
            }
            root = gen(rootTy, Ctx(emptyList(), ALL, fuel, emptyMap())).id
        }
        val grants = listOf(
            GrantSpec("category-only", ch.subset(Cat.entries).associateWith { listOf(SENTINEL) }),
            refinedGrant("refined-1"),
            refinedGrant("refined-2"),
        )
        val kept = reachable(root)
        return GenCase(
            json = render(root, kept),
            mode = if (machine) Mode.Machine else Mode.Expression,
            rootTy = rootTy,
            scopeCaps = scopeCaps.toMap(),
            grants = grants,
            features = features.toSet(),
            nodeCount = kept.size,
        )
    }

    private fun refinedGrant(label: String): GrantSpec {
        val out = LinkedHashMap<Cat, List<PatternSpec>>()
        for (c in Cat.entries) {
            when (ch.weighted(intArrayOf(2, 2, 2, 4))) {
                0 -> Unit // absent
                1 -> out[c] = listOf(SENTINEL)
                2 -> out[c] = listOf(PatternSpec(false, c.params.map { Slot.Wild }))
                else -> out[c] = List(1 + ch.int(2)) {
                    PatternSpec(false, c.params.map { p ->
                        when {
                            ch.oneIn(4) -> Slot.Wild
                            p == Ty.IntT -> Slot.IntC(ch.int(10).toLong())
                            else -> Slot.StrC(ch.pick(STRINGS))
                        }
                    })
                }
            }
        }
        return GrantSpec(label, out)
    }

    /** True rarely: generate the construct at hand under-declared. */
    private fun cheat(): Boolean = ch.oneIn(40)

    // ------------------------------------------------------------------
    // Node emission
    // ------------------------------------------------------------------

    private fun fresh(prefix: String): String = "$prefix${counter++}"

    private fun node(id: String, type: String, vararg fields: Pair<String, Any?>): String {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = JsonPrimitive(type)
        for ((k, v) in fields) if (v != null) m[k] = toJson(v)
        nodes[id] = JsonObject(m)
        return id
    }

    private fun toJson(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { toJson(it!!) })
        else -> error("unsupported field value ${v::class}")
    }

    private inline fun once(key: String, build: () -> String): String {
        memo[key]?.let { return it }
        val id = build()
        memo[key] = id
        return id
    }

    /** Node ids reachable from [root] through reference-valued fields. */
    private fun reachable(root: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        fun visit(id: String) { if (id in nodes && seen.add(id)) queue.add(id) }
        fun walk(nodeType: String, key: String, e: JsonElement, nested: Boolean) {
            when (e) {
                is JsonObject -> for ((k, v) in e) walk(nodeType, k, v, nested = true)
                is JsonArray -> for (v in e) walk(nodeType, key, v, nested)
                is JsonPrimitive -> if (e.isString && isReference(nodeType, key, nested)) visit(e.content)
            }
        }
        visit(root)
        while (queue.isNotEmpty()) {
            val obj = nodes.getValue(queue.removeFirst())
            val type = (obj["type"] as JsonPrimitive).content
            for ((k, v) in obj) walk(type, k, v, nested = false)
        }
        return seen
    }

    /**
     * Whether string field [key] of a [nodeType] node names another node.
     * A ForeignNode's own `target` is a binding id; the `target` of a
     * projection source nested inside it is a node reference.
     */
    private fun isReference(nodeType: String, key: String, nested: Boolean): Boolean = when (key) {
        in NON_REFERENCE_KEYS -> false
        "value" -> nodeType != "StringLit" && nodeType != "BytesLit"
        "target" -> nodeType != "ForeignNode" || nested
        else -> true
    }

    private fun render(root: String, kept: Set<String>): String = buildString {
        append("{\n  \"version\": 1,\n  \"root\": \"").append(root).append("\",\n  \"nodes\": {\n")
        val ids = nodes.keys.filter { it in kept }
        for ((i, id) in ids.withIndex()) {
            append("    \"").append(id).append("\": ").append(nodes.getValue(id).toString())
            append(if (i == ids.size - 1) "\n" else ",\n")
        }
        append("  }\n}\n")
    }

    // ------------------------------------------------------------------
    // Types and prelude
    // ------------------------------------------------------------------

    private fun prim(kind: String): String =
        once("prim:$kind") { node(kind.lowercase() + "T", "PrimitiveType", "kind" to kind) }

    private fun cat(c: Cat): String = once("cat:$c") {
        node(c.nodeId, "EffectCategory", "categoryName" to c.categoryName, "parameters" to declared(c).map { tyId(it) })
    }

    /**
     * The parameter shape this program declares for [c]. EffectCategory
     * nodes are per-program declarations, so a program is free to declare
     * the registry's `Filesystem.Write` without its path parameter; the
     * host's policy for the category is still over paths.
     */
    private fun declared(c: Cat): List<Ty> = if (c == Cat.W && !writeHasPath) emptyList() else c.params

    private fun cats(cs: Collection<Cat>): List<String> = cs.sorted().map { cat(it) }

    /** The node id of [ty] in type position. */
    private fun tyId(ty: Ty): String = when (ty) {
        Ty.IntT -> prim("Int")
        Ty.StrT -> prim("String")
        Ty.BoolT -> prim("Bool")
        Ty.Tool -> prim("Bytes")
        Ty.OptInt -> once("opt") {
            val some = node("optSome", "SumTypeCase", "name" to "Some", "caseType" to prim("Int"))
            val none = node("optNone", "SumTypeCase", "name" to "None")
            node("optT", "SumType", "cases" to listOf(some, none))
        }
        Ty.ListInt -> once("list") {
            // The inner/outer ProductType split of corpus 31/32: the inner
            // product closes the μ-body, the outer one types construction sites.
            val self = node("listSelf", "RecursiveSelf")
            val headIn = node("listHeadIn", "ProductTypeField", "name" to "head", "fieldType" to prim("Int"))
            val tailIn = node("listTailIn", "ProductTypeField", "name" to "tail", "fieldType" to self)
            val consIn = node("listConsIn", "ProductType", "fields" to listOf(headIn, tailIn))
            val consCase = node("listCons", "SumTypeCase", "name" to "Cons", "caseType" to consIn)
            val nilCase = node("listNil", "SumTypeCase", "name" to "Nil")
            val body = node("listBody", "SumType", "cases" to listOf(consCase, nilCase))
            node("listT", "RecursiveType", "body" to body)
        }
        is Ty.Fn -> once("fn:$ty") {
            node(
                fresh("fnT"), "FunctionType",
                "parameters" to ty.params.map { tyUse(it) },
                "result" to tyUse(ty.result),
                "effects" to cats(ty.effects),
            )
        }
        is Ty.Rec -> once("rec:$ty") {
            val a = node(fresh("recA"), "ProductTypeField", "name" to "a", "fieldType" to prim("Int"))
            val f = node(
                fresh("recF"), "ProductTypeField", "name" to "f",
                "fieldType" to tyId(Ty.Fn(listOf(Ty.IntT), Ty.IntT, ty.effects)),
            )
            node(fresh("recT"), "ProductType", "fields" to listOf(a, f))
        }
        is Ty.SchemaInt -> once("schema:${ty.k}") {
            val schemaId = "schema${ty.k}"
            val invId = "inv${ty.k}"
            val x = node("invX${ty.k}", "ParameterDecl", "name" to "x", "paramType" to prim("Int"))
            val xRef = node("invXRef${ty.k}", "VarRef", "binder" to x)
            val bound = node("invBound${ty.k}", "IntLit", "value" to if (ty.k == 0) 3L else 7L)
            val cmp = pure(if (ty.k == 0) "Int.Gt" else "Int.Lt", listOf(Ty.IntT, Ty.IntT), Ty.BoolT)
            val body = node("invBody${ty.k}", "Application", "function" to cmp, "arguments" to listOf(xRef, bound))
            val lam = node("invLam${ty.k}", "Lambda", "parameters" to listOf(x), "body" to body)
            node(invId, "Invariant", "invariantName" to "bound${ty.k}", "targetSchema" to schemaId, "body" to lam)
            node(schemaId, "Schema", "schemaName" to "S${ty.k}", "valueType" to prim("Int"), "invariants" to listOf(invId))
        }
    }

    private fun consOuter(): String = once("consOuter") {
        val head = node("listHeadOut", "ProductTypeField", "name" to "head", "fieldType" to prim("Int"))
        val tail = node("listTailOut", "ProductTypeField", "name" to "tail", "fieldType" to tyId(Ty.ListInt))
        node("listConsOut", "ProductType", "fields" to listOf(head, tail))
    }

    /** [tyId], or now and then a type-position NodeRef to it. */
    private fun tyUse(ty: Ty): String {
        val id = tyId(ty)
        val refable = ty == Ty.IntT || ty == Ty.StrT || ty == Ty.BoolT || ty == Ty.OptInt || ty == Ty.ListInt
        if (!refable || !ch.oneIn(6)) return id
        features += "noderef-type"
        return once("tyref:$id") { node(fresh("tref"), "NodeRef", "target" to id) }
    }

    /** An effect-free registry builtin bound at its canonical signature. */
    private fun pure(name: String, params: List<Ty>, result: Ty): String = once("pure:$name") {
        val fnT = node(
            fresh("pureT"), "FunctionType",
            "parameters" to params.map { tyId(it) }, "result" to tyId(result),
        )
        node(fresh("pure"), "ForeignNode", "target" to "strand-builtin:$name", "foreignType" to fnT)
    }

    private fun add(): String = pure("Int.Add", listOf(Ty.IntT, Ty.IntT), Ty.IntT)
    private fun sub(): String = pure("Int.Sub", listOf(Ty.IntT, Ty.IntT), Ty.IntT)

    /** A test-namespace ForeignNode with no effect row (markers, schema consumers, combinators). */
    private fun plainForeign(key: String, target: String, fnT: Ty.Fn): String = once("plain:$key") {
        node(fresh("ext"), "ForeignNode", "target" to target, "foreignType" to tyId(fnT))
    }

    private fun sigTy(sig: Sig, effects: Set<Cat>): Ty.Fn = when (sig) {
        Sig.II -> Ty.Fn(listOf(Ty.IntT), Ty.IntT, effects)
        Sig.SS -> Ty.Fn(listOf(Ty.StrT), Ty.StrT, effects)
        Sig.NI -> Ty.Fn(emptyList(), Ty.IntT, effects)
    }

    private fun sigOf(fn: Ty.Fn): Sig? = when {
        fn.params == listOf(Ty.IntT) && fn.result == Ty.IntT -> Sig.II
        fn.params == listOf(Ty.StrT) && fn.result == Ty.StrT -> Sig.SS
        fn.params.isEmpty() && fn.result == Ty.IntT -> Sig.NI
        else -> null
    }

    /**
     * The registry's `Time.Now`, bound with its row on the node, on the
     * FunctionType, or on both (the declared row must equal the registry's).
     */
    private fun timeNow(): E {
        val variant = ch.int(3)
        val id = once("now:$variant") {
            val onType = if (variant == 0) emptySet() else setOf(Cat.T)
            val onNode = if (variant == 1) emptyList() else listOf(cat(Cat.T))
            node(
                fresh("now"), "ForeignNode",
                "target" to RealBuiltin.TIME_NOW.target,
                "foreignType" to tyId(sigTy(Sig.NI, onType)),
                "effects" to onNode,
            )
        }
        return leaf(id, sigTy(Sig.NI, setOf(Cat.T)))
    }

    /**
     * The registry's `Fs.Write`, bound with the canonical projection of its
     * path argument or without one. Returns the node and whether it is
     * projected.
     */
    private fun fsWrite(): Pair<String, Boolean> {
        val projected = ch.bool()
        val id = once("fsWrite:$projected") {
            val fnT = node(
                fresh("writeT"), "FunctionType",
                "parameters" to listOf(prim("String"), prim("Bytes")), "result" to prim("Int"),
            )
            val sources = if (writeHasPath) {
                listOf(JsonObject(mapOf("kind" to JsonPrimitive("ArgRef"), "index" to JsonPrimitive(0))))
            } else {
                emptyList()
            }
            val projections = if (!projected) null else listOf(
                JsonObject(mapOf("category" to JsonPrimitive(cat(Cat.W)), "sources" to JsonArray(sources)))
            )
            node(
                fresh("write"), "ForeignNode",
                "target" to RealBuiltin.FS_WRITE.target,
                "foreignType" to fnT,
                "effects" to listOf(cat(Cat.W)),
                "effectProjections" to projections,
            )
        }
        return id to projected
    }

    /** `Time.Now()`: an Int, performing the registry's clock read. */
    private fun timeNowCall(c: Ctx): E? {
        if (Cat.T !in c.allowed || !handledOk(setOf(Cat.T), Sig.NI, c)) return null
        features += "time-now"
        val now = timeNow()
        val inst = instances(listOf(Cat.T), c, null, emptyList())
        return E(app(now.id, emptyList(), inst.ids), Ty.IntT, setOf(Cat.T), emptySet(), emptySet())
    }

    /**
     * `Fs.Write(path, bytes)`: an Int, writing a real file in the throwaway
     * workspace. The instance, when present, restates the path argument for
     * a projected binding and is any pure String otherwise, so for an
     * unprojected binding the declared path and the written path can differ.
     */
    private fun fsWriteCall(c: Ctx): E? {
        if (Cat.W !in c.allowed || Cat.W in c.handled) return null
        features += "fs-write"
        val (write, projected) = fsWrite()
        val path = if (ch.bool()) leafStr(c) else genStr(c)
        val bytes = once("bytes") { node("payload", "BytesLit", "value" to "00") }
        var declIds = emptyList<String>()
        var declFree = emptySet<String>()
        if (ch.chance(2, 5) && (!projected || path.fx.isEmpty())) {
            features += "effect-instance"
            val params: List<E> = when {
                !writeHasPath -> emptyList()
                projected -> listOf(path)
                else -> listOf(leafStr(c.copy(allowed = emptySet())))
            }
            declFree = params.flatMapTo(LinkedHashSet()) { it.free }
            declIds = listOf(node(fresh("decl"), "EffectDecl", "effectType" to cat(Cat.W), "parameters" to params.map { it.id }))
        }
        return E(
            app(write, listOf(path.id, bytes), declIds), Ty.IntT,
            path.fx + Cat.W, path.lat, path.free + declFree,
        )
    }

    /**
     * The ForeignNode binding stand-in [s]. The row is declared on the node,
     * on the FunctionType only, on both, or (two-category rows) split
     * between them; the runtime row is the union in every case. A projected
     * stand-in always carries its row on the node, where projections attach.
     */
    private fun standIn(s: StandIn): E {
        val row = s.row.toSet()
        val variants = when {
            s.projected -> listOf(0, 2)
            s.row.size > 1 -> listOf(0, 1, 2, 3)
            else -> listOf(0, 1, 2)
        }
        val variant = ch.pick(variants)
        val id = once("standin:$s:$variant") {
            val (typeRow, nodeRow) = when (variant) {
                0 -> emptyList<Cat>() to s.row
                1 -> s.row to emptyList()
                2 -> s.row to s.row
                else -> s.row.take(1) to s.row.drop(1)
            }
            if (variant == 1 || variant == 3) features += "foreign-type-row"
            val projections = if (!s.projected) null else nodeRow.map { c ->
                JsonObject(mapOf(
                    "category" to JsonPrimitive(cat(c)),
                    "sources" to JsonArray(projectionSources(c)),
                ))
            }
            if (projections != null) features += "projection"
            node(
                fresh("std"), "ForeignNode",
                "target" to s.target,
                "foreignType" to tyId(sigTy(s.sig, typeRow.toSet())),
                "effects" to nodeRow.map { cat(it) },
                "effectProjections" to projections,
            )
        }
        return E(id, sigTy(s.sig, row), emptySet(), emptySet(), emptySet(), proj = if (s.projected) s else null)
    }

    private fun projectionSources(c: Cat): List<JsonElement> {
        val argRef = JsonObject(mapOf("kind" to JsonPrimitive("ArgRef"), "index" to JsonPrimitive(0)))
        return when (c) {
            Cat.A, Cat.B, Cat.T, Cat.W -> emptyList() // T and W are never in a stand-in's row
            Cat.P -> listOf(argRef)
            Cat.R -> listOf(
                JsonObject(mapOf(
                    "kind" to JsonPrimitive("LiteralNode"),
                    "target" to JsonPrimitive(strLit(StandIn.R_RESOURCE).id),
                )),
                argRef,
            )
        }
    }

    // ------------------------------------------------------------------
    // Leaves and small helpers
    // ------------------------------------------------------------------

    private fun leaf(id: String, ty: Ty): E = E(id, ty, emptySet(), emptySet(), emptySet())

    private fun intLit(n: Int): E = leaf(once("int:$n") { node("i$n", "IntLit", "value" to n.toLong()) }, Ty.IntT)
    private fun strLit(s: String): E = leaf(once("str:$s") { node("s_$s", "StringLit", "value" to s) }, Ty.StrT)
    private fun boolLit(b: Boolean): E = leaf(once("bool:$b") { node("b_$b", "BoolLit", "value" to b) }, Ty.BoolT)

    private fun varRef(v: Var): E {
        val id = if (ch.bool()) once("vr:${v.id}") { node(fresh("v"), "VarRef", "binder" to v.id) }
        else node(fresh("v"), "VarRef", "binder" to v.id)
        return E(id, v.ty, emptySet(), emptySet(), setOf(v.id), v.proj)
    }

    private fun remember(e: E): E {
        if (pool.size < 400) pool += e
        return e
    }

    private fun usable(e: E, c: Ctx): Boolean {
        if (!c.allowed.containsAll(e.fx) || !c.allowed.containsAll(e.lat)) return false
        if (e.free.isEmpty()) return true
        val inScope = c.vars.mapTo(HashSet()) { it.id }
        return inScope.containsAll(e.free)
    }

    private fun pooled(c: Ctx, accept: (Ty) -> Boolean): E? {
        val candidates = pool.filter { accept(it.ty) && usable(it, c) }
        if (candidates.isEmpty()) return null
        features += "shared-node"
        return ch.pick(candidates)
    }

    private fun app(
        fn: String,
        args: List<String>,
        instances: List<String> = emptyList(),
        typeArgs: List<String> = emptyList(),
    ): String = node(
        fresh("app"), "Application",
        "function" to fn,
        "arguments" to args,
        "typeArguments" to typeArgs.ifEmpty { null },
        "effectInstances" to instances.ifEmpty { null },
    )

    private fun union(vararg es: E): Triple<Set<Cat>, Set<Cat>, Set<String>> {
        val fx = LinkedHashSet<Cat>()
        val lat = LinkedHashSet<Cat>()
        val free = LinkedHashSet<String>()
        for (e in es) { fx += e.fx; lat += e.lat; free += e.free }
        return Triple(fx, lat, free)
    }

    /** A random subset of [from], usually most of it. */
    private fun row(from: Set<Cat>): Set<Cat> {
        val out = LinkedHashSet<Cat>()
        for (c in from.sorted()) if (ch.chance(2, 3)) out += c
        return out
    }

    /**
     * Whether a call through a callee with row [effects] and signature [sig]
     * agrees with every lexically enclosing Handler that would intercept it.
     */
    private fun handledOk(effects: Set<Cat>, sig: Sig?, c: Ctx): Boolean =
        effects.all { cat -> c.handled[cat]?.let { it == sig } ?: true }

    /** The categories of [c].allowed a callee of signature [sig] may carry. */
    private fun rowFor(sig: Sig?, c: Ctx): Set<Cat> =
        c.allowed.filterTo(LinkedHashSet()) { cat -> c.handled[cat]?.let { it == sig } ?: true }

    // ------------------------------------------------------------------
    // Expression generation
    // ------------------------------------------------------------------

    private fun gen(ty: Ty, c: Ctx): E = remember(
        ch.span {
            when (ty) {
                Ty.IntT -> genInt(c)
                Ty.StrT -> genStr(c)
                Ty.BoolT -> genBool(c)
                Ty.OptInt -> genOpt(c)
                Ty.ListInt -> genList(c)
                Ty.Tool -> toolDef(row(c.allowed), c)
                is Ty.Fn -> genFn(ty.params, ty.result, ty.effects, c)
                is Ty.Rec -> genRec(ty.effects, c)
                // A plain Int flows into a Schema<Int> position; the verifier
                // records the obligation on the argument node.
                is Ty.SchemaInt -> genInt(c)
            }
        }
    )

    private fun leafInt(c: Ctx): E {
        val vars = c.vars.filter { it.ty == Ty.IntT }
        return when (ch.weighted(intArrayOf(3, if (vars.isEmpty()) 0 else 3, 2))) {
            0 -> intLit(ch.int(10))
            1 -> varRef(ch.pick(vars))
            else -> pooled(c) { it == Ty.IntT } ?: intLit(ch.int(10))
        }
    }

    private fun genInt(c: Ctx): E = ch.span { genIntAt(c) }

    private fun genIntAt(c: Ctx): E {
        if (c.fuel <= 0) return leafInt(c)
        val d = c.copy(fuel = c.fuel - 1)
        return when (ch.weighted(intWeights)) {
            0 -> leafInt(c)
            1 -> arith(d)
            2 -> standInCall(Sig.II, d) ?: leafInt(c)
            3 -> callVar(Ty.IntT, d) ?: leafInt(c)
            4 -> applyLambda(Ty.IntT, d)
            5 -> let(Ty.IntT, d)
            6 -> matchOn(Ty.IntT, d)
            7 -> handler(Ty.IntT, d)
            8 -> scope(Ty.IntT, d)
            9 -> nodeRef(Ty.IntT, d)
            10 -> fold(d)
            11 -> callTool(d)
            12 -> recUse(d)
            13 -> fixpoint(d)
            14 -> poly(Ty.IntT, d)
            15 -> consume(d)
            16 -> curried(d)
            17 -> unwrapSchemaVar(d) ?: leafInt(c)
            18 -> timeNowCall(d) ?: leafInt(c)
            19 -> fsWriteCall(d) ?: leafInt(c)
            else -> higherOrderOwn(d) ?: leafInt(c)
        }
    }

    private fun leafStr(c: Ctx): E {
        val vars = c.vars.filter { it.ty == Ty.StrT }
        return when (ch.weighted(intArrayOf(3, if (vars.isEmpty()) 0 else 3, 1))) {
            0 -> strLit(ch.pick(STRINGS))
            1 -> varRef(ch.pick(vars))
            else -> pooled(c) { it == Ty.StrT } ?: strLit(ch.pick(STRINGS))
        }
    }

    private fun genStr(c: Ctx): E {
        if (c.fuel <= 0) return leafStr(c)
        val d = c.copy(fuel = c.fuel - 1)
        return when (ch.weighted(intArrayOf(4, 2, 5, 2, 2, 2, 3, 2, 2, 1))) {
            0 -> leafStr(c)
            1 -> {
                val l = genStr(d)
                val r = leafStr(d)
                val (fx, lat, free) = union(l, r)
                val concat = pure("String.Concat", listOf(Ty.StrT, Ty.StrT), Ty.StrT)
                E(app(concat, listOf(l.id, r.id)), Ty.StrT, fx, lat, free)
            }
            2 -> standInCall(Sig.SS, d) ?: leafStr(c)
            3 -> callVar(Ty.StrT, d) ?: leafStr(c)
            4 -> applyLambda(Ty.StrT, d)
            5 -> let(Ty.StrT, d)
            6 -> handler(Ty.StrT, d)
            7 -> scope(Ty.StrT, d)
            8 -> matchOn(Ty.StrT, d)
            else -> poly(Ty.StrT, d)
        }
    }

    private fun genBool(c: Ctx): E {
        val vars = c.vars.filter { it.ty == Ty.BoolT }
        if (c.fuel <= 0) return if (vars.isNotEmpty() && ch.bool()) varRef(ch.pick(vars)) else boolLit(ch.bool())
        val d = c.copy(fuel = c.fuel - 1)
        return when (ch.weighted(intArrayOf(3, 4, 1, 1))) {
            0 -> if (vars.isNotEmpty() && ch.bool()) varRef(ch.pick(vars)) else boolLit(ch.bool())
            1 -> {
                val l = genInt(d)
                val r = genInt(d)
                val (fx, lat, free) = union(l, r)
                val lt = pure("Int.Lt", listOf(Ty.IntT, Ty.IntT), Ty.BoolT)
                E(app(lt, listOf(l.id, r.id)), Ty.BoolT, fx, lat, free)
            }
            2 -> let(Ty.BoolT, d)
            else -> matchOn(Ty.BoolT, d)
        }
    }

    private fun genOpt(c: Ctx): E {
        val optT = tyId(Ty.OptInt)
        fun none(): E = leaf(once("optNoneV") { node("optNoneV", "SumValue", "ofType" to optT, "caseName" to "None") }, Ty.OptInt)
        fun some(payload: E): E = E(
            node(fresh("some"), "SumValue", "ofType" to optT, "caseName" to "Some", "payload" to payload.id),
            Ty.OptInt, payload.fx, payload.lat, payload.free,
        )
        if (c.fuel <= 0) return if (ch.bool()) some(leafInt(c)) else none()
        val d = c.copy(fuel = c.fuel - 1)
        return when (ch.weighted(intArrayOf(2, 4, 1, 1, 1))) {
            0 -> none()
            1 -> some(genInt(d))
            2 -> let(Ty.OptInt, d)
            3 -> matchOn(Ty.OptInt, d)
            else -> scope(Ty.OptInt, d)
        }
    }

    private fun listLiteral(elems: List<E>): E {
        val listT = tyId(Ty.ListInt)
        var cur = leaf(once("nilV") { node("nilV", "SumValue", "ofType" to listT, "caseName" to "Nil") }, Ty.ListInt)
        for (e in elems.reversed()) {
            val head = node(fresh("hd"), "ProductFieldValue", "fieldName" to "head", "value" to e.id)
            val tail = node(fresh("tl"), "ProductFieldValue", "fieldName" to "tail", "value" to cur.id)
            val payload = node(fresh("cons"), "ProductValue", "ofType" to consOuter(), "fields" to listOf(head, tail))
            val (fx, lat, free) = union(e, cur)
            cur = E(
                node(fresh("lst"), "SumValue", "ofType" to listT, "caseName" to "Cons", "payload" to payload),
                Ty.ListInt, fx, lat, free,
            )
        }
        return cur
    }

    private fun genList(c: Ctx): E {
        if (c.fuel <= 0) return listLiteral(List(ch.int(3)) { leafInt(c) })
        val d = c.copy(fuel = c.fuel - 1)
        return when (ch.weighted(intArrayOf(4, 4, 1, 1))) {
            0 -> listLiteral(List(ch.int(4)) { genInt(d.copy(fuel = minOf(d.fuel, 1))) })
            1 -> {
                features += "list-map"
                val r = row(c.allowed)
                val list = genList(d)
                val cb = genFn(listOf(Ty.IntT), Ty.IntT, r, d, callback = true)
                val mapT = Ty.Fn(listOf(Ty.ListInt, Ty.Fn(listOf(Ty.IntT), Ty.IntT, r)), Ty.ListInt, emptySet())
                val map = plainForeign("map:${r.sorted()}", "strand-builtin:List.Map", mapT)
                val (fx, lat, free) = union(list, cb)
                E(app(map, listOf(list.id, cb.id)), Ty.ListInt, fx, lat + (cb.ty as Ty.Fn).effects, free)
            }
            2 -> let(Ty.ListInt, d)
            else -> pooled(c) { it == Ty.ListInt } ?: listLiteral(emptyList())
        }
    }

    private fun genRec(effects: Set<Cat>, c: Ctx): E {
        val ty = Ty.Rec(effects)
        val d = c.copy(fuel = c.fuel - 1)
        val a = if (c.fuel <= 0) leafInt(c) else genInt(d)
        val f = genFn(listOf(Ty.IntT), Ty.IntT, effects, d)
        val aField = node(fresh("fa"), "ProductFieldValue", "fieldName" to "a", "value" to a.id)
        val fField = node(fresh("ff"), "ProductFieldValue", "fieldName" to "f", "value" to f.id)
        val (fx, lat, free) = union(a, f)
        features += "record"
        return E(
            node(fresh("rec"), "ProductValue", "ofType" to tyId(ty), "fields" to listOf(aField, fField)),
            ty, fx, lat, free,
        )
    }

    private fun arith(c: Ctx): E {
        val l = genInt(c)
        val r = leafInt(c)
        val (fx, lat, free) = union(l, r)
        return E(app(if (ch.bool()) add() else sub(), listOf(l.id, r.id)), Ty.IntT, fx, lat, free)
    }

    /**
     * EffectDecl instances for a call through a callee with row [effects],
     * or none. For a projected stand-in the parameters restate the
     * projection (the same argument node, an equal literal); otherwise they
     * are arbitrary expressions of the category's parameter types, which
     * the runtime evaluates at the call site.
     */
    private fun instances(effects: Collection<Cat>, c: Ctx, proj: StandIn?, args: List<E>): Inst {
        val none = Inst(emptyList(), emptySet(), emptySet(), emptySet())
        if (effects.isEmpty() || !ch.chance(2, 5)) return none
        // A projected binding's instance restates the argument node itself,
        // so an effectful argument would make the parameter effectful.
        if (proj != null && args.any { it.fx.isNotEmpty() } && !cheat()) return none
        features += "effect-instance"
        val small = c.copy(fuel = minOf(c.fuel, 1))
        val ids = ArrayList<String>()
        val fx = LinkedHashSet<Cat>()
        val lat = LinkedHashSet<Cat>()
        val free = LinkedHashSet<String>()
        for (cat in effects.sorted()) {
            val params: List<E> = when {
                declared(cat).isEmpty() -> emptyList()
                proj != null && cat == Cat.P -> listOf(args[0])
                proj != null && cat == Cat.R -> listOf(strLit(StandIn.R_RESOURCE), args[0])
                else -> {
                    // A refinement parameter must be effect-free
                    // (EffectDeclParameterNotPure); an effectful one is an
                    // under-declaration the verifier is expected to reject.
                    val pure = if (cheat()) small else small.copy(allowed = emptySet())
                    declared(cat).map { p -> if (p == Ty.IntT) genInt(pure) else leafStr(pure) }
                }
            }
            for (p in params) { fx += p.fx; lat += p.lat; free += p.free }
            ids += node(fresh("decl"), "EffectDecl", "effectType" to cat(cat), "parameters" to params.map { it.id })
        }
        return Inst(ids, fx, lat, free)
    }

    private fun standInCall(sig: Sig, c: Ctx): E? {
        val candidates = StandIn.entries.filter { s ->
            s.sig == sig && c.allowed.containsAll(s.row) && (handledOk(s.row.toSet(), sig, c) || cheat())
        }
        if (candidates.isEmpty()) return null
        val s = ch.pick(candidates)
        var fn = standIn(s)
        // Reach the stand-in through a NodeRef now and then: the ForeignNode is closed.
        if (ch.oneIn(6)) {
            features += "noderef-term"
            fn = E(node(fresh("ref"), "NodeRef", "target" to fn.id), fn.ty, fn.fx, fn.lat, fn.free, fn.proj)
        }
        val arg = if (sig == Sig.II) genInt(c) else genStr(c)
        val inst = instances(s.row, c, fn.proj, listOf(arg))
        return E(
            app(fn.id, listOf(arg.id), inst.ids),
            if (sig == Sig.II) Ty.IntT else Ty.StrT,
            arg.fx + s.row + inst.fx, arg.lat + inst.lat, arg.free + inst.free,
        )
    }

    /** Call a function-typed binder in scope whose result is [ty]. */
    private fun callVar(ty: Ty, c: Ctx): E? {
        val candidates = c.vars.filter { v ->
            val fn = v.ty as? Ty.Fn ?: return@filter false
            fn.result == ty && c.allowed.containsAll(fn.effects) && handledOk(fn.effects, sigOf(fn), c)
        }
        if (candidates.isEmpty()) return null
        val v = ch.pick(candidates)
        val fn = v.ty as Ty.Fn
        val callee = varRef(v)
        val args = fn.params.map { gen(it, c) }
        val inst = instances(fn.effects, c, v.proj, args)
        val (fx, lat, free) = union(callee, *args.toTypedArray())
        return E(app(callee.id, args.map { it.id }, inst.ids), ty, fx + fn.effects + inst.fx, lat + inst.lat, free + inst.free)
    }

    /**
     * A Lambda of [params] whose body has type [result] (for a function
     * result, some function type with that signature) and declares [declared].
     */
    private fun lambda(params: List<Ty>, result: Ty, declared: Set<Cat>, c: Ctx): E {
        val pids = params.map { node(fresh("p"), "ParameterDecl", "name" to "x", "paramType" to tyUse(it)) }
        val inner = Ctx(
            vars = c.vars + pids.zip(params).map { (id, ty) -> Var(id, ty) },
            allowed = declared,
            fuel = c.fuel - 1,
            handled = c.handled,
        )
        val body = gen(result, inner)
        val emitted = if (body.fx.isNotEmpty() && cheat()) declared - body.fx.first() else declared
        val id = node(fresh("lam"), "Lambda", "parameters" to pids, "body" to body.id, "effects" to cats(emitted))
        return E(id, Ty.Fn(params, body.ty, emitted), emptySet(), body.lat, body.free - pids.toSet())
    }

    /**
     * A callable of signature ([params]) -> [result] whose row is within
     * [maxFx]. [callback] marks a callable handed to a higher-order builtin:
     * the verifier decides there whether an enclosing Handler agrees with
     * it, so no signature filter applies.
     */
    private fun genFn(params: List<Ty>, result: Ty, maxFx: Set<Cat>, c: Ctx, callback: Boolean = false): E {
        val sig = sigOf(Ty.Fn(params, result, emptySet()))
        val within = maxFx intersect c.allowed
        // The clock itself is the simplest `() -> Int` callable carrying Time.Now.
        if (sig == Sig.NI && Cat.T in within && ch.oneIn(3)) return timeNow()
        fun fits(t: Ty): Boolean =
            t is Ty.Fn && t.params == params && t.result == result && within.containsAll(t.effects)
        val fuelLeft = c.fuel > 0
        val weights = intArrayOf(4, if (sig != null) 4 else 0, 3, 2, if (fuelLeft) 1 else 0, if (fuelLeft) 1 else 0)
        when (ch.weighted(weights)) {
            1 -> {
                val candidates = StandIn.entries.filter { it.sig == sig && within.containsAll(it.row) }
                if (candidates.isNotEmpty()) return standIn(ch.pick(candidates))
                // A schema consumer where (Int) -> Int is expected: admitted
                // only if the verifier treats Schema<Int> and Int parameters
                // as interchangeable under an arrow.
                if (sig == Sig.II && cheat()) {
                    val k = ch.int(Schemas.COUNT)
                    features += "schema-callback"
                    val fnT = Ty.Fn(listOf(Ty.SchemaInt(k)), Ty.IntT, emptySet())
                    return leaf(plainForeign("consume:$k", FuzzHost.consume(k), fnT), Ty.Fn(params, result, emptySet()))
                }
            }
            2 -> {
                val vars = c.vars.filter { fits(it.ty) }
                if (vars.isNotEmpty()) return varRef(ch.pick(vars))
            }
            3 -> pooled(c, ::fits)?.let { return it }
            4 -> {
                // Bind the callable in a Let and return the binder.
                val d = c.copy(fuel = c.fuel - 1)
                val value = genFn(params, result, maxFx, d, callback)
                val letId = fresh("let")
                val ref = varRef(Var(letId, value.ty, value.proj))
                node(letId, "Let", "name" to "f", "value" to value.id, "body" to ref.id)
                features += "let-bound-callable"
                return E(letId, value.ty, value.fx, value.lat, value.free, value.proj)
            }
            5 -> if (sig == Sig.II) {
                val d = c.copy(fuel = c.fuel - 1)
                val r = row(within)
                val rec = genRec(r, d)
                val id = node(fresh("get"), "ProductFieldGet", "target" to rec.id, "fieldName" to "f")
                return E(id, Ty.Fn(params, result, r), rec.fx, rec.lat, rec.free)
            }
            else -> Unit
        }
        val allowedRow = if (callback) within else within.filterTo(LinkedHashSet()) { cat ->
            c.handled[cat]?.let { it == sig } ?: true
        }
        return lambda(params, result, row(allowedRow), c.copy(allowed = c.allowed + maxFx))
    }

    private fun applyLambda(ty: Ty, c: Ctx): E {
        val paramTy: Ty = when (ch.weighted(intArrayOf(6, 1, 2, 1))) {
            0 -> Ty.IntT
            1 -> Ty.StrT
            2 -> { features += "schema-param"; Ty.SchemaInt(ch.int(Schemas.COUNT)) }
            else -> Ty.Fn(listOf(Ty.IntT), Ty.IntT, row(c.allowed))
        }
        val sig = sigOf(Ty.Fn(listOf(paramTy), ty, emptySet()))
        val lam = lambda(listOf(paramTy), ty, row(rowFor(sig, c)), c)
        val arg = gen(paramTy, c)
        val fn = lam.ty as Ty.Fn
        val inst = instances(fn.effects, c, null, listOf(arg))
        val (fx, lat, free) = union(lam, arg)
        return E(app(lam.id, listOf(arg.id), inst.ids), ty, fx + fn.effects + inst.fx, lat + inst.lat, free + inst.free)
    }

    private fun let(ty: Ty, c: Ctx): E {
        val value: E = when (ch.weighted(intArrayOf(5, 1, 3, 2, 1, 1, 1, 1))) {
            0 -> genInt(c)
            1 -> genStr(c)
            2 -> genFn(listOf(Ty.IntT), Ty.IntT, c.allowed, c)
            3 -> {
                val candidates = StandIn.entries.filter { c.allowed.containsAll(it.row) }
                if (candidates.isEmpty()) genInt(c) else standIn(ch.pick(candidates))
            }
            4 -> genRec(row(c.allowed), c)
            5 -> genOpt(c)
            6 -> genList(c)
            else -> toolDef(row(c.allowed), c)
        }
        val letId = fresh("let")
        val body = gen(ty, c.copy(vars = c.vars + Var(letId, value.ty, value.proj)))
        node(letId, "Let", "name" to "x", "value" to value.id, "body" to body.id)
        return E(letId, body.ty, value.fx + body.fx, value.lat + body.lat, value.free + (body.free - letId))
    }

    private fun matchOn(ty: Ty, c: Ctx): E {
        val cases = ArrayList<String>()
        val parts = ArrayList<E>()
        fun case(pattern: String, body: E, bound: String? = null) {
            cases += node(fresh("case"), "MatchCase", "pattern" to pattern, "body" to body.id)
            parts += if (bound == null) body else E(body.id, body.ty, body.fx, body.lat, body.free - bound)
        }
        val scrutinee: E
        when (ch.weighted(intArrayOf(4, 2, 2))) {
            0 -> {
                scrutinee = genInt(c)
                val intT = tyId(Ty.IntT)
                for (n in listOf(ch.int(10), ch.int(10)).distinct()) {
                    // A pattern literal is a literal node; an expression
                    // there would run at each match attempt.
                    val literal = if (cheat()) genInt(c.copy(vars = emptyList(), fuel = 1)).id else intLit(n).id
                    val pat = node(fresh("pat"), "Pattern", "kind" to "literal", "patternType" to intT, "literal" to literal)
                    case(pat, gen(ty, c))
                }
                if (ch.bool()) {
                    val pat = node(fresh("pat"), "Pattern", "kind" to "variable", "patternType" to intT, "name" to "m")
                    case(pat, gen(ty, c.copy(vars = c.vars + Var(pat, Ty.IntT))), bound = pat)
                } else {
                    case(node(fresh("pat"), "Pattern", "kind" to "wildcard", "patternType" to intT), gen(ty, c))
                }
            }
            1 -> {
                scrutinee = genBool(c)
                val boolT = tyId(Ty.BoolT)
                val t = node(fresh("pat"), "Pattern", "kind" to "literal", "patternType" to boolT, "literal" to boolLit(true).id)
                case(t, gen(ty, c))
                if (ch.bool()) {
                    val f = node(fresh("pat"), "Pattern", "kind" to "literal", "patternType" to boolT, "literal" to boolLit(false).id)
                    case(f, gen(ty, c))
                } else {
                    case(node(fresh("pat"), "Pattern", "kind" to "wildcard", "patternType" to boolT), gen(ty, c))
                }
            }
            else -> {
                scrutinee = genOpt(c)
                val optT = tyId(Ty.OptInt)
                val v = node(fresh("pat"), "Pattern", "kind" to "variable", "patternType" to tyId(Ty.IntT), "name" to "v")
                val some = node(
                    fresh("pat"), "Pattern", "kind" to "constructor", "patternType" to optT,
                    "caseName" to "Some", "payloadPattern" to v,
                )
                case(some, gen(ty, c.copy(vars = c.vars + Var(v, Ty.IntT))), bound = v)
                val none = node(fresh("pat"), "Pattern", "kind" to "constructor", "patternType" to optT, "caseName" to "None")
                case(none, gen(ty, c))
            }
        }
        features += "match"
        val (fx, lat, free) = union(scrutinee, *parts.toTypedArray())
        return E(node(fresh("match"), "Match", "scrutinee" to scrutinee.id, "cases" to cases), ty, fx, lat, free)
    }

    private fun handler(ty: Ty, c: Ctx): E {
        features += "handler"
        // Filesystem.Write is never intercepted here: its calls take (path,
        // bytes), a signature no other generated callable shares.
        val intercept = ch.pick(Cat.entries - Cat.W)
        val sig = when {
            intercept == Cat.T -> Sig.NI
            ch.oneIn(6) -> Sig.SS
            else -> Sig.II
        }
        // A handle whose own row contains the intercept re-enters itself at
        // its first such call and runs out of stack; keep that rare.
        val handleMax = if (ch.oneIn(8)) c.allowed else c.allowed - intercept
        val sigT = sigTy(sig, emptySet())
        val handle = genFn(sigT.params, sigT.result, handleMax, c.copy(allowed = handleMax))
        val handleRow = (handle.ty as Ty.Fn).effects
        if (c.handled.isNotEmpty()) features += "handler-nested"
        val body = gen(ty, c.copy(allowed = c.allowed + intercept, handled = c.handled + (intercept to sig)))
        val id = node(fresh("hdl"), "Handler", "intercept" to cat(intercept), "handle" to handle.id, "body" to body.id)
        return E(
            id, body.ty,
            (body.fx - intercept) + handle.fx + handleRow,
            body.lat + handle.lat,
            body.free + handle.free,
        )
    }

    /**
     * A CapabilityScope. The body is bracketed by effect-free marker calls so
     * the ground-truth log knows, for every performed effect, which scopes
     * dynamically enclose it.
     */
    private fun scope(ty: Ty, c: Ctx): E {
        features += "scope"
        if (c.handled.isNotEmpty()) features += "scope-in-handler"
        val caps = LinkedHashSet<Cat>()
        for (cat in Cat.entries) if (ch.chance(2, 3)) caps += cat
        val tag = scopeTag++
        val body = gen(ty, c.copy(allowed = c.allowed intersect caps))
        val retained = if (body.fx.isNotEmpty() && cheat()) caps - body.fx.first() else caps
        scopeCaps[tag] = retained
        val markerT = Ty.Fn(listOf(Ty.IntT), Ty.IntT, emptySet())
        val tagLit = node(fresh("tag"), "IntLit", "value" to tag.toLong())
        val enter = app(plainForeign("enter", FuzzHost.ENTER, markerT), listOf(tagLit))
        val exit = app(plainForeign("exit", FuzzHost.EXIT, markerT), listOf(tagLit))
        val result = fresh("let")
        val resultRef = node(fresh("v"), "VarRef", "binder" to result)
        val afterExit = node(fresh("let"), "Let", "name" to "_", "value" to exit, "body" to resultRef)
        node(result, "Let", "name" to "r", "value" to body.id, "body" to afterExit)
        val bracketed = node(fresh("let"), "Let", "name" to "_", "value" to enter, "body" to result)
        val id = node(fresh("scope"), "CapabilityScope", "capabilities" to cats(retained), "body" to bracketed)
        return E(id, body.ty, body.fx, body.lat, body.free)
    }

    /** A NodeRef to a closed expression of type [ty]. */
    private fun nodeRef(ty: Ty, c: Ctx): E {
        features += "noderef-term"
        val target = gen(ty, c.copy(vars = emptyList()))
        return E(node(fresh("ref"), "NodeRef", "target" to target.id), target.ty, target.fx, target.lat, emptySet(), target.proj)
    }

    private fun fold(c: Ctx): E {
        features += "list-fold"
        val r = row(c.allowed)
        val list = genList(c)
        val init = leafInt(c)
        val cb = genFn(listOf(Ty.IntT, Ty.IntT), Ty.IntT, r, c, callback = true)
        val cbT = Ty.Fn(listOf(Ty.IntT, Ty.IntT), Ty.IntT, r)
        val foldT = Ty.Fn(listOf(Ty.ListInt, Ty.IntT, cbT), Ty.IntT, emptySet())
        val foldFn = plainForeign("fold:${r.sorted()}", "strand-builtin:List.Fold", foldT)
        val (fx, lat, free) = union(list, init, cb)
        return E(app(foldFn, listOf(list.id, init.id, cb.id)), Ty.IntT, fx, lat + (cb.ty as Ty.Fn).effects, free)
    }

    /**
     * A call of [HoStandIn]: a higher-order builtin that performs `Fuzz.P`
     * itself and runs an effectful callback. The ForeignNode declares the
     * builtin's own effect, and half the time the callback's row as well,
     * so one dispatch has both a performed and a propagated part.
     */
    private fun higherOrderOwn(c: Ctx): E? {
        // The dispatch is an Application whose callee carries the row, so a
        // Handler over any of it would intercept a callee of a signature it
        // was not checked for; keep to categories no enclosing Handler takes.
        val free = rowFor(null, c)
        if (Cat.P !in free) return null
        features += "higher-order-own-effect"
        val r = row(c.allowed)
        val arg = leafInt(c)
        val cb = genFn(listOf(Ty.IntT), Ty.IntT, r, c, callback = true)
        val cbT = Ty.Fn(listOf(Ty.IntT), Ty.IntT, r)
        val nodeRow: Set<Cat> = if (ch.bool()) setOf(Cat.P) else setOf(Cat.P) + r.filter { it in free }
        val fnT = Ty.Fn(listOf(Ty.IntT, cbT), Ty.IntT, nodeRow)
        val fn = once("hop:${r.sorted()}:${nodeRow.sorted()}") {
            node(
                fresh("hop"), "ForeignNode",
                "target" to HoStandIn.TARGET, "foreignType" to tyId(fnT), "effects" to cats(nodeRow),
            )
        }
        val inst = instances(nodeRow, c, null, listOf(arg))
        val (fx, lat, freeVars) = union(arg, cb)
        return E(
            app(fn, listOf(arg.id, cb.id), inst.ids), Ty.IntT,
            fx + nodeRow + inst.fx, lat + (cb.ty as Ty.Fn).effects + inst.lat, freeVars + inst.free,
        )
    }

    private fun toolDef(r: Set<Cat>, c: Ctx): E {
        features += "tooldef"
        val schema = once("toolSchema") {
            node("toolSchema", "Schema", "schemaName" to "ToolParam", "valueType" to prim("Int"), "invariants" to emptyList<String>())
        }
        val impl = genFn(listOf(Ty.IntT), Ty.IntT, r, c.copy(fuel = c.fuel - 1), callback = true)
        val id = node(
            fresh("tool"), "ToolDef",
            "name" to "t", "description" to "d", "parameterSchema" to schema, "implementation" to impl.id,
        )
        return E(id, Ty.Tool, impl.fx, impl.lat + (impl.ty as Ty.Fn).effects, impl.free)
    }

    private fun callTool(c: Ctx): E {
        val vars = c.vars.filter { it.ty == Ty.Tool }
        val tool = if (vars.isNotEmpty() && ch.bool()) varRef(ch.pick(vars)) else toolDef(row(c.allowed), c)
        val arg = leafInt(c)
        val callT = Ty.Fn(listOf(Ty.Tool, Ty.IntT), Ty.IntT, emptySet())
        val (fx, lat, free) = union(tool, arg)
        return E(app(plainForeign("callTool", FuzzHost.CALL_TOOL, callT), listOf(tool.id, arg.id)), Ty.IntT, fx, lat, free)
    }

    private fun recUse(c: Ctx): E {
        val r = row(rowFor(Sig.II, c))
        val vars = c.vars.filter { v -> v.ty is Ty.Rec && rowFor(Sig.II, c).containsAll((v.ty as Ty.Rec).effects) }
        val rec = if (vars.isNotEmpty() && ch.bool()) varRef(ch.pick(vars)) else genRec(r, c)
        val effects = (rec.ty as Ty.Rec).effects
        if (ch.bool()) {
            return E(node(fresh("get"), "ProductFieldGet", "target" to rec.id, "fieldName" to "a"), Ty.IntT, rec.fx, rec.lat, rec.free)
        }
        val f = node(fresh("get"), "ProductFieldGet", "target" to rec.id, "fieldName" to "f")
        val arg = leafInt(c)
        val inst = instances(effects, c, null, listOf(arg))
        return E(
            app(f, listOf(arg.id), inst.ids), Ty.IntT,
            rec.fx + arg.fx + effects + inst.fx, rec.lat + arg.lat + inst.lat, rec.free + arg.free + inst.free,
        )
    }

    /** A bounded recursion: `fix f. λn. match n { 0 -> base; _ -> step; f(n - 1) }` applied to 0..2. */
    private fun fixpoint(c: Ctx): E {
        features += "fixpoint"
        val r = row(rowFor(Sig.II, c))
        val recT = Ty.Fn(listOf(Ty.IntT), Ty.IntT, r)
        val self = node(fresh("p"), "ParameterDecl", "name" to "self", "paramType" to tyId(recT))
        val n = node(fresh("p"), "ParameterDecl", "name" to "n", "paramType" to tyId(Ty.IntT))
        val inner = Ctx(c.vars + Var(n, Ty.IntT), r, minOf(c.fuel - 1, 2), c.handled)
        val base = leafInt(inner)
        val step = genInt(inner)
        val nRef = node(fresh("v"), "VarRef", "binder" to n)
        val selfRef = node(fresh("v"), "VarRef", "binder" to self)
        val recur = app(selfRef, listOf(app(sub(), listOf(nRef, intLit(1).id))))
        val stepThenRecur = node(fresh("let"), "Let", "name" to "_", "value" to step.id, "body" to recur)
        val intT = tyId(Ty.IntT)
        val zero = node(fresh("pat"), "Pattern", "kind" to "literal", "patternType" to intT, "literal" to intLit(0).id)
        val other = node(fresh("pat"), "Pattern", "kind" to "wildcard", "patternType" to intT)
        val caseZero = node(fresh("case"), "MatchCase", "pattern" to zero, "body" to base.id)
        val caseOther = node(fresh("case"), "MatchCase", "pattern" to other, "body" to stepThenRecur)
        val scrut = node(fresh("v"), "VarRef", "binder" to n)
        val match = node(fresh("match"), "Match", "scrutinee" to scrut, "cases" to listOf(caseZero, caseOther))
        val lam = node(fresh("lam"), "Lambda", "parameters" to listOf(self, n), "body" to match, "effects" to cats(r))
        val fix = node(fresh("fix"), "Fixpoint", "recursionType" to tyId(recT), "body" to lam)
        val free = (base.free + step.free) - n
        return E(app(fix, listOf(intLit(ch.int(3)).id)), Ty.IntT, r, base.lat + step.lat, free)
    }

    /**
     * A polymorphic effectful function `Λa. λ(x: a). step; x` applied at
     * [ty]. The abstraction is closed and is kept for reuse, so one node is
     * instantiated at several types.
     */
    private fun poly(ty: Ty, c: Ctx): E {
        features += "polymorphic"
        val sig = if (ty == Ty.IntT) Sig.II else Sig.SS
        val permitted = rowFor(sig, c)
        val reusable = polys.filter { permitted.containsAll(it.effects) && c.allowed.containsAll(it.lat) }
        val p = if (reusable.isNotEmpty() && ch.bool()) ch.pick(reusable) else {
            val effects = row(permitted)
            val tp = node(fresh("tp"), "TypeParameter", "name" to "a")
            val x = node(fresh("p"), "ParameterDecl", "name" to "x", "paramType" to tp)
            val step = genInt(Ctx(emptyList(), effects, minOf(c.fuel - 1, 2), c.handled))
            val xRef = node(fresh("v"), "VarRef", "binder" to x)
            val body = node(fresh("let"), "Let", "name" to "_", "value" to step.id, "body" to xRef)
            val lam = node(fresh("lam"), "Lambda", "parameters" to listOf(x), "body" to body, "effects" to cats(effects))
            Poly(node(fresh("tabs"), "TypeAbstraction", "typeParameters" to listOf(tp), "body" to lam), effects, step.lat)
                .also { polys += it }
        }
        val arg = gen(ty, c)
        val inst = instances(p.effects, c, null, listOf(arg))
        return E(
            app(p.id, listOf(arg.id), inst.ids, typeArgs = listOf(tyId(ty))), ty,
            arg.fx + p.effects + inst.fx, arg.lat + p.lat + inst.lat, arg.free + inst.free,
        )
    }

    /** Pass an Int into a stand-in whose parameter is typed `Schema<Int>`. */
    private fun consume(c: Ctx): E {
        features += "schema-position"
        val k = ch.int(Schemas.COUNT)
        // Literal nodes are shared by value, so drawing from three probe
        // values (one violating each schema, one satisfying both) makes one
        // node reach both schema positions often.
        val arg = when (ch.int(3)) {
            0 -> intLit(ch.pick(SCHEMA_PROBES))
            1 -> leafInt(c)
            else -> genInt(c)
        }
        val fnT = Ty.Fn(listOf(Ty.SchemaInt(k)), Ty.IntT, emptySet())
        return E(app(plainForeign("consume:$k", FuzzHost.consume(k), fnT), listOf(arg.id)), Ty.IntT, arg.fx, arg.lat, arg.free)
    }

    /** Use a `Schema<Int>`-typed binder as an Int. */
    private fun unwrapSchemaVar(c: Ctx): E? {
        val vars = c.vars.filter { it.ty is Ty.SchemaInt }
        if (vars.isEmpty()) return null
        val v = ch.pick(vars)
        val ref = varRef(v)
        return if (ch.bool()) {
            E(app(add(), listOf(ref.id, intLit(0).id)), Ty.IntT, emptySet(), emptySet(), ref.free)
        } else {
            // The binder's own schema; a different one is a mismatch the
            // verifier is expected to reject.
            val own = (v.ty as Ty.SchemaInt).k
            val k = if (cheat()) (own + 1) % Schemas.COUNT else own
            val fnT = Ty.Fn(listOf(Ty.SchemaInt(k)), Ty.IntT, emptySet())
            E(app(plainForeign("consume:$k", FuzzHost.consume(k), fnT), listOf(ref.id)), Ty.IntT, emptySet(), emptySet(), ref.free)
        }
    }

    /** `(λx. step; λy. body)(a)(b)`: an effectful function returned by an effectful function. */
    private fun curried(c: Ctx): E {
        features += "curried"
        val outerRow = row(rowFor(null, c))
        val innerRow = row(rowFor(Sig.II, c))
        val x = node(fresh("p"), "ParameterDecl", "name" to "x", "paramType" to tyId(Ty.IntT))
        val outerCtx = Ctx(c.vars + Var(x, Ty.IntT), outerRow, c.fuel - 1, c.handled)
        val inner = lambda(listOf(Ty.IntT), Ty.IntT, innerRow, outerCtx.copy(allowed = c.allowed))
        val step = genInt(outerCtx.copy(fuel = minOf(outerCtx.fuel, 1)))
        val outerBody = node(fresh("let"), "Let", "name" to "_", "value" to step.id, "body" to inner.id)
        val outer = node(fresh("lam"), "Lambda", "parameters" to listOf(x), "body" to outerBody, "effects" to cats(outerRow))
        val a = leafInt(c)
        val b = leafInt(c)
        val innerFn = inner.ty as Ty.Fn
        val free = ((step.free + inner.free) - x) + a.free + b.free
        return E(
            app(app(outer, listOf(a.id)), listOf(b.id)), Ty.IntT,
            outerRow + innerFn.effects, step.lat + inner.lat, free,
        )
    }

    // ------------------------------------------------------------------
    // State machines
    // ------------------------------------------------------------------

    /**
     * A single-input machine over Int events with Int state and no outputs.
     * The transition body and the initialState are generated expressions;
     * the initialState is closed and may perform effects, which the machine
     * declaration must cover.
     */
    private fun genMachine(fuel: Int): String {
        val transitionRow = row(ALL)
        val s = node(fresh("p"), "ParameterDecl", "name" to "s", "paramType" to tyId(Ty.IntT))
        val e = node(fresh("p"), "ParameterDecl", "name" to "e", "paramType" to tyId(Ty.IntT))
        val next = genInt(Ctx(listOf(Var(s, Ty.IntT), Var(e, Ty.IntT)), transitionRow, fuel, emptyMap()))
        val emptyT = node("noOutputsT", "ProductType", "fields" to emptyList<String>())
        val stateF = node("resStateF", "ProductTypeField", "name" to "state", "fieldType" to tyId(Ty.IntT))
        val outputsF = node("resOutputsF", "ProductTypeField", "name" to "outputs", "fieldType" to emptyT)
        val resultT = node("resT", "ProductType", "fields" to listOf(stateF, outputsF))
        val outputsV = node("noOutputsV", "ProductValue", "ofType" to emptyT, "fields" to emptyList<String>())
        val stateFV = node(fresh("fs"), "ProductFieldValue", "fieldName" to "state", "value" to next.id)
        val outputsFV = node(fresh("fo"), "ProductFieldValue", "fieldName" to "outputs", "value" to outputsV)
        val result = node(fresh("res"), "ProductValue", "ofType" to resultT, "fields" to listOf(stateFV, outputsFV))
        val transition = node(
            fresh("lam"), "Lambda", "parameters" to listOf(s, e), "body" to result, "effects" to cats(transitionRow),
        )
        val init = genInt(Ctx(emptyList(), row(ALL), fuel, emptyMap()))
        if (init.fx.isNotEmpty()) features += "machine-effectful-initial-state"
        val covered = transitionRow + if (cheat()) emptySet() else init.fx
        val declared = if (ch.bool()) covered else covered + row(ALL)
        val input = node("inputStream", "EventStream", "eventType" to tyId(Ty.IntT), "streamKind" to "external")
        val receive = node("receiveFx", "EffectCategory", "categoryName" to "StateMachine.Receive")
        return node(
            "machine", "StateMachine",
            "transitionFn" to transition,
            "initialState" to init.id,
            "inputStreams" to listOf(input),
            "outputStreams" to emptyList<String>(),
            "effects" to cats(declared) + receive,
        )
    }

    companion object {
        private val ALL: Set<Cat> = Cat.entries.toSet()
        private val SENTINEL = PatternSpec(sentinel = true, slots = emptyList())
        val STRINGS = listOf("a", "b", StandIn.R_RESOURCE)
        private val SCHEMA_PROBES = listOf(1, 5, 8)

        /** genInt alternatives, leaf first: see the `when` in [genIntAt]. */
        private val INT_WEIGHTS = intArrayOf(5, 2, 8, 3, 3, 4, 2, 4, 3, 2, 2, 2, 2, 1, 2, 3, 1, 2, 2, 3, 2)

        private val NON_REFERENCE_KEYS = setOf(
            "type", "kind", "name", "fieldName", "caseName", "categoryName",
            "schemaName", "invariantName", "description", "streamKind",
        )
    }
}
