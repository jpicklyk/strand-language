package org.strand.corpus

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.strand.authoring.BuiltinSignatures
import org.strand.authoring.LayerAGrammar
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher
import org.strand.interpreter.Builtins
import org.strand.interpreter.Interpreter
import org.strand.interpreter.Value
import org.strand.interpreter.invoke

/**
 * Q-065 behavioral audit: every registry entry marked
 * [Builtins.Determinism.Deterministic] is evaluated twice on
 * representative inputs and must return bit-identical results.
 *
 * Inputs are signature-driven. Table-covered entries
 * ([BuiltinSignatures.table]) get values generated from their [Sig]
 * shapes (canonical samples per primitive, short canonical Cons/Nil
 * lists, small products, Some-wrapped options, the blessed
 * JsonValue/MarkdownDocument shapes, real closures via
 * [Interpreter.applyCallable] for function-typed parameters);
 * prelude-covered entries ([LayerAGrammar.reservedNodes]) get values
 * generated from their reserved FunctionType's primitive parameter
 * list. Entries whose domains the generator cannot derive (resource
 * handles surfaced as Int) carry an explicit entry in [fixtures].
 *
 * TOTALITY: the audit fails — it does not skip — when any
 * Deterministic entry has neither a derivable signature nor a fixture,
 * so coverage is total by construction rather than by diligence.
 *
 * Float results are compared on raw bits (NaN payloads included), per
 * the proposal's bit-identical re-evaluation contract.
 */
class BuiltinDeterminismAuditTest {

    // ===== Canonical samples =====
    // Positional primitives: the second Int position is never zero so
    // divisor-taking entries (Int.Div / Int.Mod / Math.Mod) stay in
    // domain; the first String position is a valid regex pattern so the
    // Regex.* family compiles.
    private val intSamples = longArrayOf(7, 3, 2, 5, 11, 13)
    private val floatSamples = doubleArrayOf(2.5, 1.5, 0.5)
    private val stringSamples = arrayOf("strand", "ra", "nd")
    private val bytesSample = byteArrayOf(1, 2, 3)

    private fun consList(items: List<Value>): Value {
        var v: Value = Value.SumV("Nil", null)
        for (item in items.reversed()) {
            v = Value.SumV("Cons", Value.ProductV(mapOf("head" to item, "tail" to v)))
        }
        return v
    }

    /** Canonical persistent-map sample, built through the builtins themselves. */
    private val mapSample: Value by lazy {
        val empty = Builtins.lookup("strand-builtin:Map.Empty")!!.invoke(emptyList())
        val put = Builtins.lookup("strand-builtin:Map.Put")!!
        val m1 = put.invoke(listOf(empty, Value.IntV(1), Value.StringV("a")))
        put.invoke(listOf(m1, Value.IntV(2), Value.StringV("b")))
    }

    /** Canonical persistent-set sample, built through the builtins themselves. */
    private val setSample: Value by lazy {
        val empty = Builtins.lookup("strand-builtin:Set.Empty")!!.invoke(emptyList())
        val add = Builtins.lookup("strand-builtin:Set.Add")!!
        val s1 = add.invoke(listOf(empty, Value.IntV(1)))
        add.invoke(listOf(s1, Value.IntV(2)))
    }

    // ===== Real closures for higher-order entries =====
    // Each callback is a genuine Strand Closure compiled from dag-json
    // and applied through the standard Interpreter.applyCallable path —
    // the same applyValueToArgs infrastructure the dispatcher threads
    // into higher-order builtins at runtime.
    private class Callback(val fn: Value, val apply: Builtins.ApplyFn)

    private fun compileClosure(json: String): Callback {
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        val interp = Interpreter(finalized.store, finalized.hashToNodeId)
        val fn = interp.eval(finalized.root)
        return Callback(fn, Builtins.ApplyFn { f, args -> interp.applyCallable(f, args) })
    }

    private fun params(n: Int): String =
        (0 until n).joinToString(",") {
            """"p$it": { "type": "ParameterDecl", "name": "x$it", "paramType": "intT" }"""
        }

    private fun paramIds(n: Int): String = (0 until n).joinToString(",") { "\"p$it\"" }

    /** n-ary closure returning its first argument. */
    private fun firstArg(n: Int): Callback = compileClosure(
        """{ "version": 1, "root": "lam", "nodes": {
             "intT": { "type": "PrimitiveType", "kind": "Int" },
             ${params(n)},
             "r0": { "type": "VarRef", "binder": "p0" },
             "lam": { "type": "Lambda", "parameters": [${paramIds(n)}], "body": "r0" }
        } }"""
    )

    /** n-ary closure returning the constant true. */
    private fun constTrue(n: Int): Callback = compileClosure(
        """{ "version": 1, "root": "lam", "nodes": {
             "intT": { "type": "PrimitiveType", "kind": "Int" },
             ${params(n)},
             "t": { "type": "BoolLit", "value": true },
             "lam": { "type": "Lambda", "parameters": [${paramIds(n)}], "body": "t" }
        } }"""
    )

    /** Binary Int less-than closure (a consistent comparator for List.Sort). */
    private fun lessThan2(): Callback = compileClosure(
        """{ "version": 1, "root": "lam", "nodes": {
             "intT": { "type": "PrimitiveType", "kind": "Int" },
             "boolT": { "type": "PrimitiveType", "kind": "Bool" },
             "ltT": { "type": "FunctionType", "parameters": ["intT", "intT"], "result": "boolT" },
             "lt": { "type": "ForeignNode", "target": "strand-builtin:Int.Lt", "foreignType": "ltT" },
             "pa": { "type": "ParameterDecl", "name": "a", "paramType": "intT" },
             "pb": { "type": "ParameterDecl", "name": "b", "paramType": "intT" },
             "ra": { "type": "VarRef", "binder": "pa" },
             "rb": { "type": "VarRef", "binder": "pb" },
             "app": { "type": "Application", "function": "lt", "arguments": ["ra", "rb"] },
             "lam": { "type": "Lambda", "parameters": ["pa", "pb"], "body": "app" }
        } }"""
    )

    /** Unary identity closure. */
    private fun identity1(): Callback = compileClosure(
        """{ "version": 1, "root": "lam", "nodes": {
             "intT": { "type": "PrimitiveType", "kind": "Int" },
             "p0": { "type": "ParameterDecl", "name": "x", "paramType": "intT" },
             "r0": { "type": "VarRef", "binder": "p0" },
             "lam": { "type": "Lambda", "parameters": ["p0"], "body": "r0" }
        } }"""
    )

    /** Pick the callback for a function-typed parameter shape. */
    private fun callbackFor(shape: BuiltinSignatures.Sig.FnOf): Callback {
        val bool = BuiltinSignatures.Sig.Prim("Bool")
        return when {
            // Comparator shape (A, A) -> Bool: a real ordering, so sorts
            // see a consistent comparator.
            shape.result == bool && shape.params.size == 2 &&
                shape.params[0] == shape.params[1] -> lessThan2()
            shape.result == bool -> constTrue(shape.params.size)
            shape.params.size == 1 -> identity1()
            else -> firstArg(shape.params.size)
        }
    }

    // ===== Signature-driven generation =====

    private class AuditInputs(val args: List<Value>, val apply: Builtins.ApplyFn? = null)

    private class Underivable(val why: String) : RuntimeException(why)

    private fun samplePrim(kind: String, pos: Int, dotted: String): Value = when (kind) {
        "Int" -> Value.IntV(intSamples[pos % intSamples.size])
        "Float" -> Value.FloatV(floatSamples[pos % floatSamples.size])
        "String" -> Value.StringV(stringSamples[pos % stringSamples.size])
        "Bool" -> Value.BoolV(true)
        "Unit" -> Value.UnitV
        "Bytes" -> when {
            // The Map.* / Set.* families declare their opaque persistent
            // values with the Bytes placeholder surface type.
            dotted.startsWith("Map.") -> mapSample
            dotted.startsWith("Set.") -> setSample
            else -> Value.BytesV(bytesSample.copyOf())
        }
        else -> throw Underivable("unknown primitive kind '$kind'")
    }

    private fun sample(sig: BuiltinSignatures.Sig, pos: Int, dotted: String): Value = when (sig) {
        is BuiltinSignatures.Sig.Prim -> samplePrim(sig.kind, pos, dotted)
        is BuiltinSignatures.Sig.Var -> Value.IntV(intSamples[pos % intSamples.size])
        is BuiltinSignatures.Sig.ListOf -> consList(
            listOf(sample(sig.elem, pos, dotted), sample(sig.elem, pos + 1, dotted))
        )
        is BuiltinSignatures.Sig.OptionOf -> Value.SumV("Some", sample(sig.elem, pos, dotted))
        is BuiltinSignatures.Sig.ProductOf -> Value.ProductV(
            sig.fields.withIndex().associate { (i, f) -> f.first to sample(f.second, pos + i, dotted) }
        )
        BuiltinSignatures.Sig.JsonValue -> Value.SumV("JsonString", Value.StringV("strand"))
        BuiltinSignatures.Sig.MarkdownDoc -> Value.SumV("Nil", null)
        is BuiltinSignatures.Sig.FnOf ->
            throw Underivable("nested function shape — needs a fixture")
    }

    /** Generate inputs from the signature table, or null when not table-covered. */
    private fun deriveFromTable(target: String): AuditInputs? {
        val dotted = target.removePrefix("strand-builtin:")
        val sig = BuiltinSignatures.lookup(dotted) ?: return null
        var callback: Callback? = null
        val args = sig.params.mapIndexed { i, p ->
            if (p is BuiltinSignatures.Sig.FnOf) {
                val cb = callbackFor(p)
                callback = cb
                cb.fn
            } else {
                sample(p, i, dotted)
            }
        }
        return AuditInputs(args, callback?.apply)
    }

    /** Generate inputs from the prelude reserved FunctionType, or null when not prelude-covered. */
    private fun deriveFromPrelude(target: String): AuditInputs? {
        val dotted = target.removePrefix("strand-builtin:")
        val foreignSpec = LayerAGrammar.reservedNodes.values.firstOrNull {
            it.jsonType == "ForeignNode" && it.stringFields["target"] == target
        } ?: return null
        val fntName = foreignSpec.refFields["foreignType"]
            ?: throw Underivable("prelude ForeignNode for $target has no foreignType")
        val fntSpec = LayerAGrammar.reservedNodes[fntName]
            ?: throw Underivable("reserved FunctionType '$fntName' not found")
        val paramNames = fntSpec.refListFields["parameters"] ?: emptyList()
        val args = paramNames.mapIndexed { i, typeName ->
            val typeSpec = LayerAGrammar.reservedNodes[typeName]
            val kind = typeSpec?.takeIf { it.jsonType == "PrimitiveType" }?.stringFields?.get("kind")
                ?: throw Underivable(
                    "prelude parameter '$typeName' of $target is not a primitive — needs a fixture"
                )
            samplePrim(kind, i, dotted)
        }
        return AuditInputs(args)
    }

    // ===== Fixture table =====
    // Entries whose argument domains the generators cannot derive: the
    // resource-closing family declares its handle parameter with the Int
    // opaque-handle surface type, but the implementations require a
    // Value.Resource. Close-of-unknown-handle is documented idempotent
    // for all five, so a never-issued handle id is squarely in domain.
    private val fixtures: Map<String, () -> AuditInputs> = mapOf(
        "strand-builtin:Net.Close" to
            { AuditInputs(listOf(Value.Resource(987_654_321L, "socket"))) },
        "strand-builtin:LLM.Stream.Close" to
            { AuditInputs(listOf(Value.Resource(987_654_321L, "llm_stream"))) },
        "strand-builtin:Http.ServerClose" to
            { AuditInputs(listOf(Value.Resource(987_654_321L, "http_server"))) },
        "strand-builtin:Pinecone.Index.Close" to
            { AuditInputs(listOf(Value.Resource(987_654_321L, "pinecone_index"))) },
        "strand-builtin:Chroma.Collection.Close" to
            { AuditInputs(listOf(Value.Resource(987_654_321L, "chroma_collection"))) },
    )

    // ===== Bit-exact value comparison =====
    private fun bitEqual(a: Value, b: Value): Boolean = when {
        a is Value.FloatV && b is Value.FloatV ->
            java.lang.Double.doubleToRawLongBits(a.v) == java.lang.Double.doubleToRawLongBits(b.v)
        a is Value.ProductV && b is Value.ProductV ->
            a.fields.keys == b.fields.keys &&
                a.fields.all { (k, v) -> bitEqual(v, b.fields.getValue(k)) }
        a is Value.SumV && b is Value.SumV ->
            a.case == b.case && when {
                a.payload == null && b.payload == null -> true
                a.payload != null && b.payload != null -> bitEqual(a.payload!!, b.payload!!)
                else -> false
            }
        else -> a == b
    }

    private fun invoke(meta: Builtins.EntryMeta, inputs: AuditInputs): Value =
        if (meta.higherOrder) {
            val apply = inputs.apply
                ?: Builtins.ApplyFn { _, _ -> error("${meta.target}: audit supplied no callback") }
            Builtins.lookupHigherOrder(meta.target)!!.invoke(inputs.args, apply)
        } else {
            Builtins.lookup(meta.target)!!.invoke(inputs.args)
        }

    // ===== The audit =====

    @Test
    fun `every Deterministic entry double-evaluates to bit-identical results — no silent skips`() {
        val deterministic = Builtins.entryMetadata()
            .filter { it.determinism == Builtins.Determinism.Deterministic }
            .sortedBy { it.target }
        assertTrue(deterministic.isNotEmpty())

        val problems = mutableListOf<String>()
        var generatedCount = 0
        var fixturedCount = 0

        for (meta in deterministic) {
            val inputs = try {
                val fixture = fixtures[meta.target]
                if (fixture != null) {
                    fixturedCount++
                    fixture()
                } else {
                    val derived = deriveFromTable(meta.target) ?: deriveFromPrelude(meta.target)
                    if (derived == null) {
                        problems += "${meta.target}: NO COVERAGE — neither signature-derivable " +
                            "nor fixtured (totality violation: add a fixture)"
                        continue
                    }
                    generatedCount++
                    derived
                }
            } catch (e: Underivable) {
                problems += "${meta.target}: NO COVERAGE — ${e.why} (totality violation: add a fixture)"
                continue
            }

            try {
                val first = invoke(meta, inputs)
                val second = invoke(meta, inputs)
                if (!bitEqual(first, second)) {
                    problems += "${meta.target}: NONDETERMINISTIC — first=$first second=$second " +
                        "on args=${inputs.args}"
                }
            } catch (e: Exception) {
                problems += "${meta.target}: evaluation failed on generated/fixture inputs " +
                    "args=${inputs.args}: ${e::class.simpleName}: ${e.message}"
            }
        }

        assertTrue(problems.isEmpty()) {
            "determinism audit failures (${problems.size}):\n" + problems.joinToString("\n")
        }
        println(
            "Q-065 determinism audit: ${deterministic.size} Deterministic entries covered " +
                "($generatedCount signature-generated, $fixturedCount fixtured)"
        )
    }

    @Test
    fun `the fixture table names only registered Deterministic entries`() {
        val deterministicTargets = Builtins.entryMetadata()
            .filter { it.determinism == Builtins.Determinism.Deterministic }
            .map { it.target }
            .toSet()
        val stale = fixtures.keys - deterministicTargets
        assertTrue(stale.isEmpty()) {
            "fixtures for targets that are not registered Deterministic entries: ${stale.sorted()}"
        }
    }
}
