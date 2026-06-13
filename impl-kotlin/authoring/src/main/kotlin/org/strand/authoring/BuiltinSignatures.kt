package org.strand.authoring

/**
 * Q-060 M-4 slice a — the authoritative Kotlin-side typed signature table
 * for the registry builtins that are NOT in the implicit prelude.
 *
 * The implicit prelude ([LayerAGrammar.reservedNodes]) covers monomorphic
 * builtins only: each reserved `ForeignNode` + `FunctionType` pair is a
 * closed static shape. The polymorphic and Option/List/product-returning
 * builtins (the `List.*`, `Map.*`, `Set.*` families, `Json.Parse`,
 * `Http.Request`, ...) historically required a hand-declared FNT + FN at
 * every use site — the largest residual boilerplate class and the direct
 * source of the Q-056 misdeclaration failure shape.
 *
 * This table expresses those signatures once, parameterized over type
 * variables (e.g. `List.Map: (List<A>, (A) -> B) -> List<B>`). The
 * Elaborator's implicit-builtin inference case
 * ([ImplicitBuiltinExpansion]) instantiates a signature from the
 * already-elaborated argument types at the application site — local
 * instantiation only, matching argument types against parameter shapes
 * positionally; no unification across sites (the Q-034 § 6 boundary).
 * Where argument types underdetermine the instantiation (e.g.
 * `Map.Get`'s value type `V`, or `List.Empty`'s element type), the
 * expansion fails with an [ElaborationGap] naming the annotation needed
 * — it never guesses.
 *
 * **Authority.** Parameter/result shapes, declared effects, and effect
 * projections mirror the per-builtin documentation in
 * `evaluation/dynamic/prompts/strand-system.md` and the runtime
 * registrations in `interpreter/Builtins.kt`. The structural macros
 * ([Sig.JsonValue], [Sig.MarkdownDoc]) reproduce the precise N-048
 * JsonValue tower (Q-069 — the model corpus 88/89 construct, a
 * JsonArray over a real List<JsonValue> and a JsonObject over a real
 * entry list) and the corpus-61 (MarkdownDocument) type tower
 * byte-identically.
 *
 * **Surface-type conventions** (same as the documented agent-facing ones):
 *  * `List<T>` is the canonical recursive sum
 *    `mu. Cons({head: T, tail: self}) | Nil`.
 *  * `Option<T>` is the flat sum `Some(T) | None`.
 *  * `Map<K, V>` and `Set<T>` are opaque persistent values declared with
 *    the `Bytes` placeholder surface type; type variables appearing only
 *    in the erased Map/Set position vanish from the instantiation, so
 *    e.g. `Map.Put` is fully instantiable from its arguments while
 *    `Map.Get`'s `V` is underdetermined by design (gap).
 *  * Handles (sockets, processes, HTTP servers/responders, streams) are
 *    declared `Int`.
 *
 * **Exclusions.** Registry entries that cannot carry a single canonical
 * signature are listed in [exclusions] with reasons; the registry-sweep
 * test (`BuiltinRegistrySweepTest` in `:corpus`) asserts every registry
 * target is prelude-covered, table-covered, or excluded.
 */
object BuiltinSignatures {

    /**
     * A signature type shape. [Var] is a signature-local type variable
     * bound by positional matching against argument types; everything
     * else is structural.
     */
    sealed class Sig {
        /** Signature-local type variable (`A`, `B`, `K`, `V`, ...). */
        data class Var(val name: String) : Sig()

        /** Primitive type by kind: Int / Float / String / Bool / Unit / Bytes. */
        data class Prim(val kind: String) : Sig()

        /** Canonical recursive list `mu. Cons({head: T, tail: self}) | Nil`. */
        data class ListOf(val elem: Sig) : Sig()

        /** Canonical option sum `Some(T) | None`. */
        data class OptionOf(val elem: Sig) : Sig()

        /** Function type `(params) -> result`. */
        data class FnOf(val params: List<Sig>, val result: Sig) : Sig()

        /** Product type with named fields in declaration order. */
        data class ProductOf(val fields: List<Pair<String, Sig>>) : Sig()

        /** The precise N-048 JsonValue recursive sum (6 cases, Q-069). */
        object JsonValue : Sig() {
            override fun toString(): String = "JsonValue"
        }

        /** The blessed corpus-61 MarkdownDocument recursive block list. */
        object MarkdownDoc : Sig() {
            override fun toString(): String = "MarkdownDocument"
        }

        /** True if this shape (transitively) contains a [Var]. */
        val hasVars: Boolean
            get() = when (this) {
                is Var -> true
                is Prim, JsonValue, MarkdownDoc -> false
                is ListOf -> elem.hasVars
                is OptionOf -> elem.hasVars
                is FnOf -> params.any { it.hasVars } || result.hasVars
                is ProductOf -> fields.any { it.second.hasVars }
            }
    }

    /**
     * One declared effect on a builtin's ForeignNode. Either a reserved
     * prelude EffectCategory name ([reservedId], e.g. `"readFx"`) or a
     * category to synthesize locally ([categoryName], for categories the
     * prelude does not reserve — `Network.Listen`, `Process.Spawn`).
     *
     * [sources] is the Q-039 projection source list for this category:
     * argument indices (`ProjectionSource.ArgRef`). `null` means "no
     * projection entry"; per the N-020 contract, when any effect on a
     * ForeignNode carries a projection, every effect must (categories
     * without parameters get an empty source list).
     */
    data class EffectSpec(
        val reservedId: String? = null,
        val categoryName: String? = null,
        val sources: List<Int>? = null,
    ) {
        init {
            require((reservedId == null) != (categoryName == null)) {
                "EffectSpec needs exactly one of reservedId / categoryName"
            }
        }
    }

    /** One table entry: positional parameter shapes, result shape, effects. */
    data class BuiltinSignature(
        val params: List<Sig>,
        val result: Sig,
        val effects: List<EffectSpec> = emptyList(),
    ) {
        init {
            val projected = effects.count { it.sources != null }
            require(projected == 0 || projected == effects.size) {
                "when any effect carries a projection, every effect must"
            }
        }
    }

    // Shorthands for table construction.
    private val A = Sig.Var("A")
    private val B = Sig.Var("B")
    private val K = Sig.Var("K")
    private val V = Sig.Var("V")
    private val W = Sig.Var("W")
    private val INT = Sig.Prim("Int")
    private val FLOAT = Sig.Prim("Float")
    private val STR = Sig.Prim("String")
    private val BOOL = Sig.Prim("Bool")
    private val UNIT = Sig.Prim("Unit")
    private val BYTES = Sig.Prim("Bytes")

    private fun sig(vararg params: Sig, result: Sig, effects: List<EffectSpec> = emptyList()) =
        BuiltinSignature(params.toList(), result, effects)

    /**
     * The table, keyed by the dotted canonical builtin name as written in
     * Layer A callee position (without the `strand-builtin:` prefix).
     * Author ids cannot contain dots ([LayerAParser] rejects them at the
     * id position), so a dotted callee is unambiguous.
     */
    val table: Map<String, BuiltinSignature> = linkedMapOf(
        // ===== List.* — canonical recursive list, polymorphic in elem =====
        "List.Empty" to sig(result = Sig.ListOf(A)),
        "List.IsEmpty" to sig(Sig.ListOf(A), result = BOOL),
        "List.Length" to sig(Sig.ListOf(A), result = INT),
        "List.Reverse" to sig(Sig.ListOf(A), result = Sig.ListOf(A)),
        "List.Take" to sig(Sig.ListOf(A), INT, result = Sig.ListOf(A)),
        "List.Drop" to sig(Sig.ListOf(A), INT, result = Sig.ListOf(A)),
        "List.Concat" to sig(Sig.ListOf(A), Sig.ListOf(A), result = Sig.ListOf(A)),
        "List.Nth" to sig(Sig.ListOf(A), INT, result = Sig.OptionOf(A)),
        "List.Range" to sig(INT, INT, result = Sig.ListOf(INT)),
        "List.Zip" to sig(
            Sig.ListOf(A), Sig.ListOf(B),
            result = Sig.ListOf(Sig.ProductOf(listOf("first" to A, "second" to B))),
        ),
        "List.Unzip" to sig(
            Sig.ListOf(Sig.ProductOf(listOf("first" to A, "second" to B))),
            result = Sig.ProductOf(listOf("first" to Sig.ListOf(A), "second" to Sig.ListOf(B))),
        ),
        "List.Distinct" to sig(Sig.ListOf(A), result = Sig.ListOf(A)),
        "List.Sum" to sig(Sig.ListOf(INT), result = INT),
        "List.Product" to sig(Sig.ListOf(INT), result = INT),
        "List.Min" to sig(Sig.ListOf(INT), result = Sig.OptionOf(INT)),
        "List.Max" to sig(Sig.ListOf(INT), result = Sig.OptionOf(INT)),
        // Higher-order List ops.
        "List.Map" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A), B), result = Sig.ListOf(B)),
        "List.Filter" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A), BOOL), result = Sig.ListOf(A)),
        "List.Fold" to sig(Sig.ListOf(A), B, Sig.FnOf(listOf(B, A), B), result = B),
        "List.Find" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A), BOOL), result = Sig.OptionOf(A)),
        "List.Any" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A), BOOL), result = BOOL),
        "List.All" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A), BOOL), result = BOOL),
        "List.Sort" to sig(Sig.ListOf(A), Sig.FnOf(listOf(A, A), BOOL), result = Sig.ListOf(A)),

        // ===== Map.* — opaque persistent map; Bytes placeholder surface =====
        "Map.Empty" to sig(result = BYTES),
        "Map.Get" to sig(BYTES, K, result = Sig.OptionOf(V)),
        "Map.Put" to sig(BYTES, K, V, result = BYTES),
        "Map.Remove" to sig(BYTES, K, result = BYTES),
        "Map.Has" to sig(BYTES, K, result = BOOL),
        "Map.Size" to sig(BYTES, result = INT),
        "Map.Keys" to sig(BYTES, result = Sig.ListOf(K)),
        "Map.Values" to sig(BYTES, result = Sig.ListOf(V)),
        "Map.Entries" to sig(
            BYTES,
            result = Sig.ListOf(Sig.ProductOf(listOf("key" to K, "value" to V))),
        ),
        "Map.Fold" to sig(BYTES, B, Sig.FnOf(listOf(B, K, V), B), result = B),
        "Map.Map" to sig(BYTES, Sig.FnOf(listOf(V), W), result = BYTES),
        "Map.Merge" to sig(BYTES, BYTES, Sig.FnOf(listOf(V, V), V), result = BYTES),
        "Map.Filter" to sig(BYTES, Sig.FnOf(listOf(K, V), BOOL), result = BYTES),

        // ===== Set.* — opaque persistent set; Bytes placeholder surface =====
        "Set.Empty" to sig(result = BYTES),
        "Set.Add" to sig(BYTES, A, result = BYTES),
        "Set.Remove" to sig(BYTES, A, result = BYTES),
        "Set.Has" to sig(BYTES, A, result = BOOL),
        "Set.Size" to sig(BYTES, result = INT),
        "Set.Union" to sig(BYTES, BYTES, result = BYTES),
        "Set.Intersect" to sig(BYTES, BYTES, result = BYTES),
        "Set.Difference" to sig(BYTES, BYTES, result = BYTES),
        "Set.ToList" to sig(BYTES, result = Sig.ListOf(A)),
        "Set.FromList" to sig(Sig.ListOf(A), result = BYTES),
        "Set.Fold" to sig(BYTES, B, Sig.FnOf(listOf(B, A), B), result = B),

        // ===== String.* — list/Option-returning entries not in the prelude =====
        "String.Split" to sig(STR, STR, result = Sig.ListOf(STR)),
        "String.Join" to sig(Sig.ListOf(STR), STR, result = STR),
        "String.Format" to sig(STR, Sig.ListOf(STR), result = STR),
        "String.Lines" to sig(STR, result = Sig.ListOf(STR)),
        "String.Chars" to sig(STR, result = Sig.ListOf(STR)),
        "String.CharAt" to sig(STR, INT, result = Sig.OptionOf(STR)),
        "String.ParseInt" to sig(STR, result = Sig.OptionOf(INT)),
        "String.ParseFloat" to sig(STR, result = Sig.OptionOf(FLOAT)),

        // ===== Bytes.* — Option-returning parsers =====
        "Bytes.ParseUtf8" to sig(BYTES, result = Sig.OptionOf(STR)),
        "Bytes.ParseBase64" to sig(STR, result = Sig.OptionOf(BYTES)),
        "Bytes.ParseHex" to sig(STR, result = Sig.OptionOf(BYTES)),

        // ===== Json — typed against the precise N-048 JsonValue (Q-069) =====
        "Json.Parse" to sig(STR, result = Sig.OptionOf(Sig.JsonValue)),
        "Json.Stringify" to sig(Sig.JsonValue, result = STR),

        // ===== Markdown — typed against the blessed corpus-61 document =====
        "Markdown.Parse" to sig(STR, result = Sig.OptionOf(Sig.MarkdownDoc)),
        "Markdown.Stringify" to sig(Sig.MarkdownDoc, result = STR),

        // ===== Csv / Tsv — List<List<String>> tables =====
        "Csv.Parse" to sig(STR, result = Sig.ListOf(Sig.ListOf(STR))),
        "Csv.Stringify" to sig(Sig.ListOf(Sig.ListOf(STR)), result = STR),
        "Tsv.Parse" to sig(STR, result = Sig.ListOf(Sig.ListOf(STR))),
        "Tsv.Stringify" to sig(Sig.ListOf(Sig.ListOf(STR)), result = STR),

        // ===== Url =====
        "Url.Parse" to sig(
            STR,
            result = Sig.OptionOf(Sig.ProductOf(listOf(
                "scheme" to STR, "host" to STR, "port" to INT,
                "path" to STR, "query" to STR, "fragment" to STR,
            ))),
        ),
        "Url.QueryDecode" to sig(STR, result = Sig.OptionOf(STR)),

        // ===== Compress =====
        "Compress.Gunzip" to sig(BYTES, result = Sig.OptionOf(BYTES)),

        // ===== DateTime =====
        "DateTime.ParseIso" to sig(STR, result = Sig.OptionOf(INT)),

        // ===== Regex — Option / List returning entries =====
        "Regex.Match" to sig(STR, STR, result = Sig.OptionOf(STR)),
        "Regex.FindAll" to sig(STR, STR, result = Sig.ListOf(STR)),
        "Regex.Split" to sig(STR, STR, result = Sig.ListOf(STR)),

        // ===== Fs.List — List-returning filesystem read =====
        "Fs.List" to sig(
            STR, result = Sig.ListOf(STR),
            effects = listOf(EffectSpec(reservedId = "readFx", sources = listOf(0))),
        ),

        // ===== Http — seven-arg request + sync server family =====
        // The canonical seven-arg Http.Request: the (host, port) arguments
        // are positional so the Q-039 projection binds connectFx's
        // {host, port} refinement parameters to ArgRef(0)/ArgRef(1).
        "Http.Request" to sig(
            STR, INT, STR, STR, STR,
            Sig.ListOf(Sig.ProductOf(listOf("name" to STR, "value" to STR))),
            BYTES,
            result = Sig.ProductOf(listOf(
                "status" to INT,
                "body" to BYTES,
                "headers" to Sig.ListOf(Sig.ProductOf(listOf("name" to STR, "value" to STR))),
            )),
            effects = listOf(
                EffectSpec(reservedId = "connectFx", sources = listOf(0, 1)),
                EffectSpec(reservedId = "netSendFx", sources = emptyList()),
                EffectSpec(reservedId = "netRecvFx", sources = emptyList()),
            ),
        ),
        // E-002 Network.Listen has no reserved prelude name; the category
        // is synthesized at the use site (parameterless, mirroring the
        // procWaitFx precedent of dropping refinement parameters the
        // implementation does not yet check).
        "Http.Listen" to sig(
            INT, result = INT,
            effects = listOf(EffectSpec(categoryName = "Network.Listen")),
        ),
        "Http.Accept" to sig(
            INT,
            result = Sig.ProductOf(listOf(
                "method" to STR, "path" to STR, "body" to BYTES, "responder" to INT,
            )),
            effects = listOf(EffectSpec(reservedId = "netRecvFx")),
        ),
        "Http.Respond" to sig(
            INT, INT, BYTES, result = UNIT,
            effects = listOf(EffectSpec(reservedId = "netSendFx")),
        ),
        // ServerClose mirrors Net.Close: closing is the dual of opening,
        // no declared effect (same as the prelude netClose entry).
        "Http.ServerClose" to sig(INT, result = UNIT),

        // ===== Streaming receives (Q-045) — uniform Option<Bytes> reads =====
        "Net.Stream.Receive" to sig(
            INT, INT, result = Sig.OptionOf(BYTES),
            effects = listOf(EffectSpec(reservedId = "netRecvFx")),
        ),
        "LLM.Stream.Receive" to sig(
            INT, INT, result = Sig.OptionOf(BYTES),
            effects = listOf(EffectSpec(reservedId = "netRecvFx")),
        ),

        // ===== Process =====
        // E-013 Process.Spawn has no reserved prelude name; synthesized
        // parameterless at the use site (procWaitFx precedent).
        "Process.Spawn" to sig(
            STR, Sig.ListOf(STR), result = INT,
            effects = listOf(EffectSpec(categoryName = "Process.Spawn")),
        ),
        // Process.EnvVar reads stable host-environment state; E-033
        // OS.Read (reserved: osReadFx) is the closest existing category
        // (the documented convention is "Process.*-ish, registry doesn't
        // enforce" — OS.Read is the category the round-3 host-environment
        // builtins standardized on).
        "Process.EnvVar" to sig(
            STR, result = Sig.OptionOf(STR),
            effects = listOf(EffectSpec(reservedId = "osReadFx")),
        ),
    )

    /**
     * Registry targets that are deliberately NOT table-covered, with
     * reasons. Keys are dotted names (no `strand-builtin:` prefix).
     */
    val exclusions: Map<String, String> = linkedMapOf(
        "Filesystem.Write" to "legacy Q-031 reference stub (corpus 12-14); superseded by " +
            "Fs.Write (prelude: fsWrite)",
        "Network.Connect" to "legacy Q-031 reference stub (corpus 33-34); superseded by " +
            "Net.Connect (prelude: netConnect)",
        "Test.EffectfulNoOp" to "test-only builtin for capability-path tests; not part of " +
            "the agent-facing surface",
        "Anthropic.Messages.CreateStream" to "agent-typed bytesT-payload GenerateRequest " +
            "product; no single canonical signature (same reason the blocking Create " +
            "variants use opaque bytesT prelude entries)",
        "OpenAI.Chat.CompletionsStream" to "agent-typed bytesT-payload GenerateRequest " +
            "product; no single canonical signature",
        "Gemini.GenerateContentStream" to "agent-typed bytesT-payload GenerateRequest " +
            "product; no single canonical signature",
    )

    /** Full builtin target string for a dotted table name. */
    fun targetFor(name: String): String = "strand-builtin:$name"

    /** Look up a dotted callee name; null when not table-covered. */
    fun lookup(name: String): BuiltinSignature? = table[name]
}
