package org.strand.hashing

/**
 * Q-058: the cached verify verdict for one root hash, the admit-and-verify-once
 * record. Keyed by root hash in the store's `verdicts/` directory.
 *
 * The verdict is a self-contained value in `:hashing` (no `:verifier`
 * dependency): it carries the **rendered** forms of the verifier's outputs —
 * the root type, the per-node types keyed by node *hash* (stable across runs,
 * unlike NodeId), the warnings, and (for a failed verify) the errors. This is
 * enough to (a) reproduce the CLI's rendering and (b) make the pass/fail
 * decision without re-verifying.
 *
 * Where a caller needs the *structured* `TypeExpr` `nodeTypes` map — the
 * interpreter's schema-obligation wiring — it re-derives it by a cheap
 * re-verify of the already-admitted local store (which still skips re-ingest,
 * re-hash, and the file parse). The proposal § 8 records the structured
 * `TypeExpr` round-trip as the deferred refinement; the conservative fallback
 * is what ships, and the dedup / integrity / run-by-hash properties hold either
 * way.
 */
sealed class StoredVerdict {
    /** A clean verify. [nodeTypesByHash] is keyed by node content hash (re-projected onto admitted NodeIds on read). */
    data class Ok(
        val rootType: String,
        val nodeTypesByHash: Map<String, String>,
        val warnings: List<String>,
    ) : StoredVerdict()

    /** A failed verify; [errors] are the rendered diagnostics. */
    data class Failed(val errors: List<String>) : StoredVerdict()

    internal fun toJson(): Js.Obj = when (this) {
        is Ok -> {
            val nt = Js.Obj()
            for ((h, t) in nodeTypesByHash) nt.put(h, Js.of(t))
            Js.objOf(
                "kind" to Js.of("ok"),
                "rootType" to Js.of(rootType),
                "nodeTypes" to nt,
                "warnings" to Js.arrOf(warnings.map { Js.of(it) }),
            )
        }
        is Failed -> Js.objOf(
            "kind" to Js.of("failed"),
            "errors" to Js.arrOf(errors.map { Js.of(it) }),
        )
    }

    companion object {
        internal fun fromJson(o: Js.Obj): StoredVerdict? {
            return when (o.str("kind")) {
                "ok" -> {
                    val rootType = o.str("rootType") ?: return null
                    val ntObj = o.obj("nodeTypes") ?: Js.Obj()
                    val nt = LinkedHashMap<String, String>()
                    for ((k, v) in ntObj.entries) (v as? Js.Str)?.let { nt[k] = it.value }
                    val warnings = (o.arr("warnings")?.items ?: emptyList())
                        .mapNotNull { (it as? Js.Str)?.value }
                    Ok(rootType = rootType, nodeTypesByHash = nt, warnings = warnings)
                }
                "failed" -> {
                    val errors = (o.arr("errors")?.items ?: emptyList())
                        .mapNotNull { (it as? Js.Str)?.value }
                    Failed(errors = errors)
                }
                else -> null
            }
        }
    }
}
