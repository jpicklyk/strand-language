package org.strand.authoring

import org.strand.authoring.BuiltinSignatures.Sig
import org.strand.verifier.BuiltinShape
import org.strand.verifier.BuiltinSignatureOracle

/**
 * Q-056: the co-resident builtin signature/effect ground truth, published
 * to the verifier through `META-INF/services` (see [BuiltinSignatureOracle]
 * / `org.strand.verifier.BuiltinSignatures` for the resolution contract).
 *
 * The truth is assembled from the two authoring-side registries the
 * elaborator already treats as authoritative and the corpus sweep already
 * pins against the runtime registry:
 *
 *  - the implicit-prelude reserved ForeignNodes
 *    ([LayerAGrammar.reservedNodes] entries with `jsonType == "ForeignNode"`),
 *    which carry each monomorphic builtin's foreignType (a reserved
 *    FunctionType) and its declared effect EffectCategory names;
 *  - the polymorphic and non-prelude [BuiltinSignatures] table, which carries
 *    per-builtin [Sig] parameter/result shapes and [BuiltinSignatures.EffectSpec]
 *    effects.
 *
 * **Effect names** are exposed for every known builtin (the soundness-critical
 * cross-check: an under-declared effect shrinks the ADR-010 closure below the
 * truth). **Signature shapes** are exposed only for *pure* builtins (empty
 * effect set) whose parameters and result are all primitive types — the
 * majority monomorphic case (arithmetic, comparison, Math.*, Hash.*, ...),
 * each of which has exactly one honest signature. Effectful and agent-typed
 * builtins are signature-deferred (null shape): the Fs.* / Net.* / LLM.* /
 * Vector.* families are legitimately wrapped in structured request/response
 * product types at their call sites (an `Anthropic.Messages.Create` typed
 * `(GenerateRequest) -> GenerateResult`, an `Fs.Write` used as a structured
 * effect stand-in), so the opaque reserved `bytesT` surface is not the single
 * canonical shape — cross-checking it would over-reject legitimate declarations.
 * For those the verifier checks the effect surface (this oracle's effect-name
 * set) and leaves the signature to the standard structural rules. Anything with
 * a type variable, or a Product / Sum / list / option / Json / Markdown tower,
 * is likewise reported as polymorphic (null shape).
 */
class BuiltinsSignatureOracle : BuiltinSignatureOracle {

    override fun effectNamesFor(target: String): Set<String>? =
        index[target]?.effectNames

    override fun signatureShapeFor(target: String): BuiltinShape.Fun? =
        index[target]?.shape

    private data class Info(val effectNames: Set<String>, val shape: BuiltinShape.Fun?)

    private val index: Map<String, Info> by lazy { buildIndex() }

    private fun buildIndex(): Map<String, Info> {
        val out = LinkedHashMap<String, Info>()

        // Source 1 — implicit-prelude reserved ForeignNodes.
        for ((_, spec) in LayerAGrammar.reservedNodes) {
            if (spec.jsonType != "ForeignNode") continue
            val target = spec.stringFields["target"] ?: continue
            if (!target.startsWith("strand-builtin:")) continue
            val effectNames = (spec.refListFields["effects"] ?: emptyList())
                .mapNotNull { effectRef -> reservedCategoryName(effectRef) }
                .toSet()
            // Signature exact-check is reserved for pure builtins — effectful
            // ones are legitimately used with structured request/response
            // signatures (see class doc).
            val fnTypeRef = spec.refFields["foreignType"]
            val shape = if (effectNames.isEmpty()) fnTypeRef?.let { primOnlyShapeOfReservedFn(it) } else null
            out[target] = Info(effectNames, shape)
        }

        // Source 2 — the polymorphic / non-prelude table. Prelude ForeignNodes
        // above win on any key overlap (there is none by construction, but the
        // putIfAbsent makes the precedence explicit).
        for ((dotted, sig) in BuiltinSignatures.table) {
            val target = BuiltinSignatures.targetFor(dotted)
            val effectNames = sig.effects.map { effectCategoryName(it) }.toSet()
            val shape = if (effectNames.isEmpty()) primOnlyShapeOfSig(sig) else null
            out.putIfAbsent(target, Info(effectNames, shape))
        }

        return out
    }

    // ------------------------------------------------------------------
    // Effect-name resolution
    // ------------------------------------------------------------------

    /** Category name of a reserved EffectCategory id, or null if not one. */
    private fun reservedCategoryName(reservedId: String): String? {
        val spec = LayerAGrammar.reservedNodes[reservedId] ?: return null
        if (spec.jsonType != "EffectCategory") return null
        return spec.stringFields["categoryName"]
    }

    /** Category name for a table [BuiltinSignatures.EffectSpec]. */
    private fun effectCategoryName(spec: BuiltinSignatures.EffectSpec): String =
        spec.categoryName
            ?: spec.reservedId?.let { reservedCategoryName(it) }
            ?: error("EffectSpec $spec resolves to no category name")

    // ------------------------------------------------------------------
    // Signature-shape resolution (primitive-only monomorphic shapes)
    // ------------------------------------------------------------------

    /**
     * The [BuiltinShape.Fun] of a reserved FunctionType id, but only when
     * every parameter and the result resolve to a primitive type. Returns
     * null for any non-primitive position (product/sum/recursive/function),
     * which pushes the target into the arity-only / deferred path.
     */
    private fun primOnlyShapeOfReservedFn(fnRef: String): BuiltinShape.Fun? {
        val spec = LayerAGrammar.reservedNodes[fnRef] ?: return null
        if (spec.jsonType != "FunctionType") return null
        val paramRefs = spec.refListFields["parameters"] ?: return null
        val resultRef = spec.refFields["result"] ?: return null
        val params = paramRefs.map { reservedPrim(it) ?: return null }
        val result = reservedPrim(resultRef) ?: return null
        return BuiltinShape.Fun(params, result)
    }

    /** [BuiltinShape.Prim] of a reserved PrimitiveType id, else null. */
    private fun reservedPrim(ref: String): BuiltinShape.Prim? {
        val spec = LayerAGrammar.reservedNodes[ref] ?: return null
        if (spec.jsonType != "PrimitiveType") return null
        val kind = spec.stringFields["kind"] ?: return null
        return BuiltinShape.Prim(kind)
    }

    /**
     * The [BuiltinShape.Fun] of a table [BuiltinSignatures.BuiltinSignature],
     * but only when every parameter and the result is a [Sig.Prim]. Any
     * variable or compound shape yields null (polymorphic / deferred).
     */
    private fun primOnlyShapeOfSig(sig: BuiltinSignatures.BuiltinSignature): BuiltinShape.Fun? {
        val params = sig.params.map { sigPrim(it) ?: return null }
        val result = sigPrim(sig.result) ?: return null
        return BuiltinShape.Fun(params, result)
    }

    /** [BuiltinShape.Prim] of a [Sig.Prim], else null (any non-primitive shape). */
    private fun sigPrim(s: Sig): BuiltinShape.Prim? =
        (s as? Sig.Prim)?.let { BuiltinShape.Prim(it.kind) }
}
