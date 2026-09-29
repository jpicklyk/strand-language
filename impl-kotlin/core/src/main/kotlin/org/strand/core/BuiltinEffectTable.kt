package org.strand.core

/**
 * The effect floor of every effectful registry-bound foreign target: the set
 * of EffectCategory `categoryName`s a [Node.ForeignNode] binding the target
 * must declare. A ForeignNode's effect row is graph-supplied (ADR-005), so
 * without this table an agent could bind `strand-builtin:Fs.Write` with
 * `effects: []`, surface an empty effect closure, and perform the write
 * under an empty capability grant. The table is the host-side authority on
 * what each builtin actually does; the declared row may over-approximate it
 * but never under-approximate it.
 *
 * Consumers:
 *  - The verifier rejects a ForeignNode whose declared row (the union of
 *    `ForeignNode.effects` and its `foreignType`'s FunctionType effects)
 *    does not cover [requiredCategories] for its target
 *    (`VerifyError.ForeignEffectUnderDeclared`).
 *  - The interpreter re-checks the same floor at dispatch as defence in
 *    depth (a store admitted without verification, or built
 *    programmatically) and raises `InterpretError.BuiltinContractViolation`.
 *
 * Scope. Keys are full target strings. Every effectful `strand-builtin:`
 * registry entry (`fx`, `fxH`, `nondet` registrations in `Builtins.kt`)
 * has a row; effect-free (`det`/`detH`) entries have none. The
 * `interpreter` test `BuiltinEffectTableConsistencyTest` enforces this
 * two-way correspondence. The runtime-intercepted
 * `strand-runtime:StateMachine.Spawn` / `.Terminate` targets are listed as
 * well. Targets absent from the table (non-registry bindings, effect-free
 * builtins) keep the ADR-005 provenance-trust semantics unchanged.
 *
 * Exemption. The `strand-builtin:Test.` namespace is exempt (see
 * [isExempt]): `Test.EffectfulNoOp` performs no I/O and exists so tests
 * can stand it in for an arbitrary effect category when exercising the
 * capability and handler machinery. Pinning it to one category would make
 * those tests impossible to write, and exempting it grants nothing because
 * the builtin has no side effect.
 *
 * Category names follow the prelude (`authoring/LayerAGrammar.kt`
 * `reservedNodes`, `prelude-module.json`), the density-v5 signature table
 * (`authoring/BuiltinSignatures.kt`), and the E-NNN registry. Where the
 * prelude declares more categories than the builtin strictly needs (for
 * example `Pinecone.Index.Open` declaring both `Vector.Read` and
 * `Vector.Write` because the returned handle supports both directions),
 * the table records the floor that opening the handle itself exercises;
 * each subsequent handle operation carries its own row.
 */
object BuiltinEffectTable {

    private const val FS_READ = "Filesystem.Read"
    private const val FS_WRITE = "Filesystem.Write"
    private const val NET_CONNECT = "Network.Connect"
    private const val NET_SEND = "Network.Send"
    private const val NET_RECEIVE = "Network.Receive"
    private const val NET_LISTEN = "Network.Listen"
    private const val LLM_GENERATE = "LLM.Generate"
    private const val LLM_EMBED = "LLM.Embed"
    private const val VECTOR_READ = "Vector.Read"
    private const val VECTOR_WRITE = "Vector.Write"
    private const val RANDOM = "Crypto.RandomBytes"
    private const val LOG = "Log.Write"
    private const val OS_READ = "OS.Read"

    /** Target → required effect category names. */
    val table: Map<String, Set<String>> = linkedMapOf(
        // Time
        "strand-builtin:Time.Now" to setOf("Time.Now"),
        "strand-builtin:Time.Sleep" to setOf("Time.Sleep"),

        // Filesystem (legacy Q-031 stub + Fs.* family)
        "strand-builtin:Filesystem.Write" to setOf(FS_WRITE),
        "strand-builtin:Fs.Write" to setOf(FS_WRITE),
        "strand-builtin:Fs.Append" to setOf(FS_WRITE),
        "strand-builtin:Fs.Delete" to setOf(FS_WRITE),
        "strand-builtin:Fs.Read" to setOf(FS_READ),
        "strand-builtin:Fs.Exists" to setOf(FS_READ),
        "strand-builtin:Fs.List" to setOf(FS_READ),

        // Network sockets (legacy Q-031 stub + Net.* family)
        "strand-builtin:Network.Connect" to setOf(NET_CONNECT),
        "strand-builtin:Net.Connect" to setOf(NET_CONNECT),
        "strand-builtin:Net.Send" to setOf(NET_SEND),
        "strand-builtin:Net.Receive" to setOf(NET_RECEIVE),
        "strand-builtin:Net.Stream.Receive" to setOf(NET_RECEIVE),

        // HTTP client and server
        "strand-builtin:Http.Request" to setOf(NET_CONNECT, NET_SEND, NET_RECEIVE),
        "strand-builtin:Http.RequestFromUrl" to setOf(NET_CONNECT, NET_SEND, NET_RECEIVE),
        "strand-builtin:Http.Listen" to setOf(NET_LISTEN),
        "strand-builtin:Http.Accept" to setOf(NET_RECEIVE),
        "strand-builtin:Http.Respond" to setOf(NET_SEND),

        // Process and host environment
        "strand-builtin:Process.Spawn" to setOf("Process.Spawn"),
        "strand-builtin:Process.Wait" to setOf("Process.Wait"),
        "strand-builtin:Process.EnvVar" to setOf(OS_READ),
        "strand-builtin:OS.Hostname" to setOf(OS_READ),
        "strand-builtin:OS.Platform" to setOf(OS_READ),
        "strand-builtin:OS.Cwd" to setOf(OS_READ),
        "strand-builtin:System.Exit" to setOf("System.Exit"),

        // Randomness
        "strand-builtin:Random.Int" to setOf(RANDOM),
        "strand-builtin:Random.Float" to setOf(RANDOM),
        "strand-builtin:Random.Bytes" to setOf(RANDOM),

        // Logging
        "strand-builtin:Log.Info" to setOf(LOG),
        "strand-builtin:Log.Warn" to setOf(LOG),
        "strand-builtin:Log.Error" to setOf(LOG),

        // LLM providers (blocking, streaming opens, stream reads)
        "strand-builtin:Anthropic.Messages.Create" to setOf(LLM_GENERATE),
        "strand-builtin:OpenAI.Chat.Completions" to setOf(LLM_GENERATE),
        "strand-builtin:Gemini.GenerateContent" to setOf(LLM_GENERATE),
        "strand-builtin:Anthropic.Messages.CreateStream" to setOf(LLM_GENERATE),
        "strand-builtin:OpenAI.Chat.CompletionsStream" to setOf(LLM_GENERATE),
        "strand-builtin:Gemini.GenerateContentStream" to setOf(LLM_GENERATE),
        "strand-builtin:LLM.Stream.Receive" to setOf(NET_RECEIVE),
        "strand-builtin:Anthropic.Embeddings.Create" to setOf(LLM_EMBED),
        "strand-builtin:OpenAI.Embeddings.Create" to setOf(LLM_EMBED),
        "strand-builtin:Gemini.EmbedContent" to setOf(LLM_EMBED),

        // Vector stores
        "strand-builtin:Pinecone.Index.Open" to setOf(VECTOR_READ),
        "strand-builtin:Pinecone.Index.Upsert" to setOf(VECTOR_WRITE),
        "strand-builtin:Pinecone.Index.Delete" to setOf(VECTOR_WRITE),
        "strand-builtin:Pinecone.Index.Query" to setOf(VECTOR_READ),
        "strand-builtin:Pinecone.Index.Fetch" to setOf(VECTOR_READ),
        "strand-builtin:Chroma.Collection.Open" to setOf(VECTOR_READ),
        "strand-builtin:Chroma.Collection.Add" to setOf(VECTOR_WRITE),
        "strand-builtin:Chroma.Collection.Delete" to setOf(VECTOR_WRITE),
        "strand-builtin:Chroma.Collection.Query" to setOf(VECTOR_READ),
        "strand-builtin:Chroma.Collection.Get" to setOf(VECTOR_READ),

        // Runtime-intercepted supervision targets (Layer 6 step 3 slice 3.2)
        "strand-runtime:StateMachine.Spawn" to setOf("StateMachine.Spawn"),
        "strand-runtime:StateMachine.Terminate" to setOf("StateMachine.Terminate"),
    )

    /** Namespace exempt from the floor; see the class kdoc. */
    const val EXEMPT_PREFIX: String = "strand-builtin:Test."

    /** True when [target] is in the exempt test namespace. */
    fun isExempt(target: String): Boolean = target.startsWith(EXEMPT_PREFIX)

    /**
     * The category names [target] requires, or null when the target carries
     * no floor (effect-free builtin, non-registry binding, or exempt).
     */
    fun requiredCategories(target: String): Set<String>? =
        if (isExempt(target)) null else table[target]

    /**
     * The required category names [declaredCategoryNames] fails to cover for
     * [target]; empty when the target has no floor or the declaration covers it.
     */
    fun missingCategories(target: String, declaredCategoryNames: Set<String>): Set<String> {
        val required = requiredCategories(target) ?: return emptySet()
        return required - declaredCategoryNames
    }
}
