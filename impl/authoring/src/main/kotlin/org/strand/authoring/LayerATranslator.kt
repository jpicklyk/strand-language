package org.strand.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.strand.core.Hash
import org.strand.core.JsonIngest
import org.strand.hashing.Hasher

/**
 * Canonical dag-json → [LayerADocument] translator. Inverse of [DagJsonEmitter].
 *
 * **Step 1 scope (Q-036 reverse projection, first slice).** This implementation
 * produces canonical-form Layer A only: always `MAT` (never `IF` / `WHEN`),
 * always bare-ref lists for `LAM.parameters` and `PV.fields` (never the compact
 * `name:typeRef` or `name=ref` density forms), and every field present (no
 * elaboration-aware field omission). Density-sugar projection and
 * elaboration-omission land in subsequent steps; the surface here is the
 * shell the later passes plug into.
 *
 * Round-trip property (asserted by `LayerAReverseRoundTripTest`):
 * for every corpus dag-json document, parsing the rendered Layer A text via
 * [Authoring.compileToDagJson] produces canonical bytes with hash equality
 * to the original.
 *
 * The walk visits `nodes` in JSON document order (which kotlinx-serialization's
 * [JsonObject] preserves) so the rendered output's node order matches the
 * source's. Each node's `type` field is looked up against [LayerAGrammar.codes];
 * variant-bearing categories (Pattern, EventStream) are disambiguated by the
 * schema's `discriminator` pair. Sugar codes (`IF`, `WHEN`) share `jsonType`
 * with the canonical `MAT` code; Step 1 skips them so the canonical code
 * wins. Step 4 of the proposal will detect their structural trigger patterns
 * and prefer the sugar where applicable.
 */
object LayerATranslator {

    /**
     * Sugar codes that share a `jsonType` with a canonical code (today: `IF`
     * and `WHEN` both share `"Match"` with `MAT`). Step 1's translator skips
     * them during code resolution; the canonical `MAT` is the right pick
     * when no structural detection is in play. A future step that adds
     * density-sugar projection will positively detect IF/WHEN trigger
     * patterns and override this default.
     */
    private val SUGAR_CODES = setOf("IF", "WHEN")

    /**
     * Codes whose optional fields are always emitted explicitly (as empty
     * list / null when absent in the source JSON). This bypasses Elaborator
     * inference that could unsafely change the recompiled canonical bytes.
     *
     * Today this is `APP` only: case 2 (effectInstances defaulting) can
     * select an EffectDecl whose parameter VarRefs reference binders out of
     * scope at the call site — corpus programs 33-35 exhibit this. Always
     * emitting an explicit `effectInstances` field (empty list when absent)
     * forces the Elaborator's `node.args.size !in 2..3` short-circuit so the
     * inference doesn't fire. The canonical CBOR encoder gates effectInstances
     * on non-empty so byte-equality is preserved.
     */
    private val FORCE_ALL_OPTIONALS = setOf("APP")

    /**
     * Translate dag-json [text] into a [LayerADocument]. Applies the
     * Q-036 SAFE elaboration-omission rules (Step 2): the Elaborator's
     * deterministically-reproducible inferences (Lambda.effects from the
     * body's closure; recursion-slot paramType from FIX.recursionType) are
     * stripped from the output so the rendered Layer A is more compact and
     * matches the form an LLM would emit. The Elaborator re-derives the
     * stripped fields on the way back through forward compilation; the
     * round-trip hash is preserved.
     *
     * Density sugars (IF/WHEN/compact LAM/inline literals/etc.) and the
     * probe-and-fallback path for BORDERLINE elaboration cases land in
     * subsequent steps.
     */
    fun translate(text: String): LayerADocument {
        val canonical = translateCanonical(text)
        val safelyOmitted = omitSafelyInferableFields(canonical)
        val probed = probeAndOmit(safelyOmitted)
        val prelude = applyImplicitPrelude(probed)
        val ifSugar = applyIfSugar(prelude)
        val compactLam = applyCompactLam(ifSugar)
        val inlined = applyInlineSubstitutions(compactLam)
        val pfvFolded = applyInlinePfv(inlined)
        val whenSugar = applyWhenSugar(pfvFolded)
        val nested = applyNestedExpressions(whenSugar)
        return nested
    }

    /** Translate without any elaboration-omission. Useful for testing and
     *  as the structural building block Step 2's omission pass works against. */
    fun translateCanonical(text: String): LayerADocument {
        val parser = Json { ignoreUnknownKeys = true }
        val root = parser.parseToJsonElement(text) as? JsonObject
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "dag-json document must be a JSON object")
            ))
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-integer 'version' field")
            ))
        val rootId = (root["root"] as? JsonPrimitive)?.contentOrNull
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-string 'root' field")
            ))
        val nodesObj = root["nodes"] as? JsonObject
            ?: throw AuthoringException(listOf(
                AuthoringError.HeaderError(line = 0, detail = "missing or non-object 'nodes' field")
            ))

        val errors = mutableListOf<AuthoringError>()
        val nodeDecls = mutableListOf<NodeDecl>()
        for ((authorId, nodeJson) in nodesObj) {
            val nodeObj = nodeJson as? JsonObject
            if (nodeObj == null) {
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' must be a JSON object",
                )
                continue
            }
            val decl = translateNode(authorId, nodeObj, errors) ?: continue
            nodeDecls += decl
        }
        if (errors.isNotEmpty()) throw AuthoringException(errors)
        return LayerADocument(version = version, rootId = rootId, nodes = nodeDecls)
    }

    private fun translateNode(
        authorId: String,
        nodeObj: JsonObject,
        errors: MutableList<AuthoringError>,
    ): NodeDecl? {
        val jsonType = (nodeObj["type"] as? JsonPrimitive)?.contentOrNull
            ?: run {
                errors += AuthoringError.HeaderError(line = 0, detail = "node '$authorId' missing 'type' field")
                return null
            }

        val code = resolveCode(jsonType, nodeObj) ?: run {
            errors += AuthoringError.HeaderError(
                line = 0,
                detail = "node '$authorId' (type=$jsonType) has no matching Layer A code — Name / Provenance / unmapped categories are not yet translatable",
            )
            return null
        }
        val schema = LayerAGrammar.codes.getValue(code)

        val args = mutableListOf<Arg>()
        for (spec in schema.required) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                // NULLABLE_REF required fields (e.g., SumValue.payload for
                // nullary case `None`) tolerate absent/explicit-null JSON by
                // emitting Arg.Null. Other required kinds error.
                if (spec.kind == LayerAGrammar.ArgKind.NULLABLE_REF) {
                    args += Arg.Null
                    continue
                }
                errors += AuthoringError.HeaderError(
                    line = 0,
                    detail = "node '$authorId' (code=$code) missing required field '${spec.jsonField}'",
                )
                return null
            }
            val arg = jsonToArg(spec, jsonField, authorId, errors) ?: return null
            args += arg
        }

        // Optional slots: per Layer A grammar, positional order is fixed
        // (e.g., APP's typeArguments must come before effectInstances). When
        // a LATER optional slot is present but an EARLIER one is absent,
        // emit a placeholder for the earlier slot so positional encoding
        // stays correct (empty list for LIST_REF, `_` for NULLABLE_REF).
        //
        // For codes in [FORCE_ALL_OPTIONALS], we always emit every optional
        // slot explicitly even when absent. This blocks Elaborator inference
        // that would otherwise fire on the recompiled output (e.g., case 2 —
        // Application.effectInstances defaulting from capability context —
        // can unsafely insert an EffectDecl whose parameter VarRefs reference
        // binders out of scope at the call site). The canonical encoder
        // gates these slots on non-default values so emitting explicit empty
        // lists preserves byte-identical canonical bytes.
        val forceAll = code in FORCE_ALL_OPTIONALS
        val lastPresentOpt: Int = if (forceAll) {
            schema.optional.lastIndex
        } else {
            schema.optional.indices.lastOrNull { i ->
                val f = nodeObj[schema.optional[i].jsonField]
                f != null && f !is JsonNull
            } ?: -1
        }
        for ((j, spec) in schema.optional.withIndex()) {
            val jsonField = nodeObj[spec.jsonField]
            if (jsonField == null || jsonField is JsonNull) {
                if (j <= lastPresentOpt) {
                    args += placeholderFor(spec, authorId, errors) ?: return null
                }
                continue
            }
            val arg = jsonToArg(spec, jsonField, authorId, errors) ?: return null
            args += arg
        }

        return NodeDecl(id = authorId, code = code, args = args, line = 0)
    }

    /**
     * Pick the Layer A code matching [jsonType]. Pass 1 looks for variant-
     * bearing categories where the discriminator pair (jsonField, value)
     * matches. Pass 2 picks any non-variant code with matching `jsonType`.
     * Sugar codes are skipped both passes so the canonical code wins; later
     * steps' sugar projection will override this default by positive
     * structural detection.
     */
    private fun resolveCode(jsonType: String, nodeObj: JsonObject): String? {
        for ((code, schema) in LayerAGrammar.codes) {
            if (code in SUGAR_CODES) continue
            if (schema.jsonType != jsonType) continue
            val disc = schema.discriminator ?: continue
            val (field, expectedValue) = disc
            val actualValue = (nodeObj[field] as? JsonPrimitive)?.contentOrNull
            if (actualValue == expectedValue) return code
        }
        for ((code, schema) in LayerAGrammar.codes) {
            if (code in SUGAR_CODES) continue
            if (schema.jsonType == jsonType && schema.discriminator == null) return code
        }
        return null
    }

    private fun jsonToArg(
        spec: LayerAGrammar.FieldSpec,
        json: JsonElement,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? = when (spec.kind) {
        LayerAGrammar.ArgKind.REFERENCE,
        LayerAGrammar.ArgKind.KEYWORD -> {
            val text = (json as? JsonPrimitive)?.contentOrNull
            if (text == null) { typeError(spec, "string", json, authorId, errors); null }
            else Arg.Bare(text)
        }
        LayerAGrammar.ArgKind.STRING -> {
            val text = (json as? JsonPrimitive)?.contentOrNull
            if (text == null) { typeError(spec, "string", json, authorId, errors); null }
            else Arg.Str(text)
        }
        LayerAGrammar.ArgKind.INT -> {
            val value = (json as? JsonPrimitive)?.longOrNull
            if (value == null) { typeError(spec, "int", json, authorId, errors); null }
            else Arg.IntL(value)
        }
        LayerAGrammar.ArgKind.FLOAT -> {
            val value = (json as? JsonPrimitive)?.doubleOrNull
            if (value == null) { typeError(spec, "float", json, authorId, errors); null }
            else Arg.FloatL(value)
        }
        LayerAGrammar.ArgKind.BOOL -> {
            val value = (json as? JsonPrimitive)?.booleanOrNull
            if (value == null) { typeError(spec, "bool", json, authorId, errors); null }
            else Arg.BoolL(value)
        }
        LayerAGrammar.ArgKind.LIST_REF,
        LayerAGrammar.ArgKind.PARAM_LIST,
        LayerAGrammar.ArgKind.FIELD_LIST -> {
            // Step 1: emit legacy bare-ref lists for all three list-shaped
            // kinds. The compact `name:typeRef` (PARAM_LIST) and `name=ref`
            // (FIELD_LIST) density forms are emitter-only sugars; future
            // steps will project them when their trigger patterns match.
            val arr = json as? JsonArray
            if (arr == null) {
                typeError(spec, "ref array", json, authorId, errors); null
            } else {
                val items = arr.mapNotNull { elt ->
                    val text = (elt as? JsonPrimitive)?.contentOrNull
                    if (text == null) { typeError(spec, "ref in array", elt, authorId, errors); null }
                    else Arg.Bare(text)
                }
                if (items.size == arr.size) Arg.Listing(items) else null
            }
        }
        LayerAGrammar.ArgKind.NULLABLE_REF -> {
            if (json is JsonNull) Arg.Null
            else {
                val text = (json as? JsonPrimitive)?.contentOrNull
                if (text == null) { typeError(spec, "nullable ref", json, authorId, errors); null }
                else Arg.Bare(text)
            }
        }
    }

    private fun placeholderFor(
        spec: LayerAGrammar.FieldSpec,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ): Arg? = when (spec.kind) {
        LayerAGrammar.ArgKind.LIST_REF -> Arg.Listing(emptyList())
        LayerAGrammar.ArgKind.NULLABLE_REF -> Arg.Null
        else -> {
            errors += AuthoringError.HeaderError(
                line = 0,
                detail = "node '$authorId' has a hole at optional '${spec.jsonField}' of non-list-non-nullable kind ${spec.kind}; cannot synthesize placeholder",
            )
            null
        }
    }

    private fun typeError(
        spec: LayerAGrammar.FieldSpec,
        expected: String,
        actual: JsonElement,
        authorId: String,
        errors: MutableList<AuthoringError>,
    ) {
        errors += AuthoringError.HeaderError(
            line = 0,
            detail = "node '$authorId' field '${spec.jsonField}': expected $expected, got ${actual::class.simpleName}",
        )
    }

    // ========================================================================
    // Step 2 — Static SAFE elaboration omission
    // ========================================================================

    /**
     * Apply Step 2's SAFE omission rules.
     *
     * Recursion-slot `paramType` is stripped: the Elaborator's case 5 sets
     * the first parameter's type of a FIX body Lambda from
     * `FIX.recursionType`, which equals the original by the verifier's
     * Fixpoint shape check, so re-derivation reproduces the same value.
     *
     * `Lambda.effects` was initially classified SAFE (per the Step 2
     * research) but corpus programs 12, 13, 14 surfaced an
     * over-declaration counter-example: a Lambda may legally declare more
     * effects than its body's closure produces (e.g., for hand-off through
     * a CapabilityScope, or for forward-compatibility with future body
     * extensions), and the Elaborator's case 1 fires only when the closure
     * is non-empty AND inserts the *closure*, not the declared set. Strict
     * static omission therefore changes canonical bytes for over-declared
     * Lambdas. The rule is demoted to BORDERLINE; the probe-and-fallback
     * path in Step 3 handles it correctly.
     */
    private fun omitSafelyInferableFields(doc: LayerADocument): LayerADocument {
        val byId = doc.nodes.associateBy { it.id }
        val recursionSlotPrcIds = recursionSlotPrcs(doc, byId)
        val newNodes = doc.nodes.map { node ->
            when (node.code) {
                "PRC" -> if (node.id in recursionSlotPrcIds) stripPrcParamType(node) else node
                else -> node
            }
        }
        return doc.copy(nodes = newNodes)
    }

    /**
     * Find every PRC node that is the FIRST parameter of a Lambda that is
     * the body of a FIX. Those PRCs' `paramType` equals `FIX.recursionType`
     * by construction and is recoverable by the Elaborator's case 5.
     */
    private fun recursionSlotPrcs(
        doc: LayerADocument,
        byId: Map<String, NodeDecl>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        for (fix in doc.nodes.filter { it.code == "FIX" }) {
            // FIX args: recursionType (0), body (1)
            val bodyRef = (fix.args.getOrNull(1) as? Arg.Bare)?.text ?: continue
            val body = byId[bodyRef] ?: continue
            if (body.code != "LAM") continue
            // LAM args: parameters (0), body (1), [effects (2)]
            val params = (body.args.getOrNull(0) as? Arg.Listing) ?: continue
            val firstParam = (params.items.firstOrNull() as? Arg.Bare)?.text ?: continue
            val prc = byId[firstParam] ?: continue
            if (prc.code != "PRC") continue
            result += prc.id
        }
        return result
    }

    /**
     * Drop the optional `paramType` argument from a PRC declaration. PRC
     * args are: name (required), paramType (optional). When present,
     * paramType is at index 1; truncating to 1 arg removes it.
     */
    private fun stripPrcParamType(node: NodeDecl): NodeDecl {
        if (node.args.size <= 1) return node
        return node.copy(args = node.args.subList(0, 1))
    }

    // ========================================================================
    // Step 3 — Probe-and-fallback for BORDERLINE elaboration cases
    // ========================================================================

    /**
     * Walk each node in [doc] and tentatively omit each candidate
     * borderline field; if elaborating the resulting document produces a
     * dag-json JsonObject identical to the baseline, accept the omission.
     * Strips are tried in safe order (last optional first) so positional
     * encoding stays valid throughout the probe.
     *
     * The probe runs the existing forward Elaborator + DagJsonEmitter
     * pipeline, so it auto-tracks any future Elaborator change — no manual
     * synchronization between projection and Elaborator gate logic.
     *
     * Per §4.5 of the Q-036 proposal, the UNSAFE static rule for
     * SchemaType-wrapped `paramType` is checked first: if the canonical's
     * paramType resolves to a SCH node, we never probe-strip it (the
     * Elaborator's bidirectional inference cannot resolve the
     * SchemaType ↔ T ambiguity without unification).
     */
    private fun probeAndOmit(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        var current = doc
        for (i in current.nodes.indices) {
            for (strip in candidateStrips(current.nodes[i], byId)) {
                val stripped = strip(current.nodes[i])
                if (stripped === current.nodes[i] || stripped == current.nodes[i]) continue
                val candidate = current.copy(
                    nodes = current.nodes.toMutableList().also { it[i] = stripped },
                )
                val candidateHash = canonicalHash(candidate) ?: continue
                if (candidateHash == baselineHash) {
                    current = candidate
                }
            }
        }
        return current
    }

    /**
     * Per-code candidate strips, ordered from outermost (last optional)
     * inward so positional encoding remains valid as each strip lands.
     */
    private fun candidateStrips(
        node: NodeDecl,
        byId: Map<String, NodeDecl>,
    ): List<(NodeDecl) -> NodeDecl> = when (node.code) {
        "APP" -> listOf(::stripAppEffectInstances, ::stripAppTypeArguments)
        "LAM" -> listOf(::stripLambdaEffectsProbe)
        "PRC" -> if (isSchemaTypedPrc(node, byId)) emptyList()
                 else listOf(::stripPrcParamTypeProbe)
        "FNT" -> listOf(::stripFntEffects)
        "SCS" -> listOf(::stripScsCaseType)
        else -> emptyList()
    }

    /**
     * UNSAFE static rule (proposal §4.5): a PRC whose `paramType` resolves
     * to a SCH node carries information the Elaborator's bidirectional
     * inference cannot reconstruct (the SchemaType↔T subtyping ambiguity —
     * see corpus 54's `jv:jsonValueSchema` annotation in density v4). Never
     * probe-strip such PRCs.
     */
    private fun isSchemaTypedPrc(node: NodeDecl, byId: Map<String, NodeDecl>): Boolean {
        if (node.code != "PRC" || node.args.size < 2) return false
        val typeRef = (node.args[1] as? Arg.Bare)?.text ?: return false
        return byId[typeRef]?.code == "SCH"
    }

    /**
     * Drop the optional last arg from a 4-arg APP (effectInstances). No-op
     * if APP has fewer args. Must run before [stripAppTypeArguments].
     */
    private fun stripAppEffectInstances(node: NodeDecl): NodeDecl {
        if (node.code != "APP" || node.args.size != 4) return node
        return node.copy(args = node.args.subList(0, 3))
    }

    /**
     * Drop the optional last arg from a 3-arg APP (typeArguments). Only
     * fires when [stripAppEffectInstances] has already succeeded; the
     * arity check ensures positional encoding stays valid.
     */
    private fun stripAppTypeArguments(node: NodeDecl): NodeDecl {
        if (node.code != "APP" || node.args.size != 3) return node
        return node.copy(args = node.args.subList(0, 2))
    }

    /** Drop the optional last arg from a 3-arg LAM (effects). */
    private fun stripLambdaEffectsProbe(node: NodeDecl): NodeDecl {
        if (node.code != "LAM" || node.args.size != 3) return node
        return node.copy(args = node.args.subList(0, 2))
    }

    /** Drop the optional last arg from a 2-arg PRC (paramType). */
    private fun stripPrcParamTypeProbe(node: NodeDecl): NodeDecl {
        if (node.code != "PRC" || node.args.size != 2) return node
        return node.copy(args = node.args.subList(0, 1))
    }

    /** Drop the optional last arg from a 3-arg FNT (effects). */
    private fun stripFntEffects(node: NodeDecl): NodeDecl {
        if (node.code != "FNT" || node.args.size != 3) return node
        return node.copy(args = node.args.subList(0, 2))
    }

    /**
     * Strip a SumTypeCase's `caseType` reference, replacing it with `_`
     * (the NULLABLE_REF placeholder). SCS args: name (required), caseType
     * (required NULLABLE_REF). When the Elaborator's case 7 can re-infer
     * from SV usage payload types, the strip is safe; the probe verifies.
     */
    private fun stripScsCaseType(node: NodeDecl): NodeDecl {
        if (node.code != "SCS" || node.args.size != 2) return node
        if (node.args[1] == Arg.Null) return node
        return node.copy(args = listOf(node.args[0], Arg.Null))
    }

    private fun elaborateAndEmit(doc: LayerADocument): JsonObject =
        DagJsonEmitter.emitJson(Elaborator.elaborate(doc))

    /**
     * Compute the canonical root hash of [doc] by running the forward
     * pipeline (Elaborator → DagJsonEmitter → JsonIngest → Hasher). Returns
     * null if any step fails (e.g., the document is malformed after a probe
     * strip); the probe treats null as "candidate rejected".
     */
    private fun canonicalHash(doc: LayerADocument): Hash? = try {
        val json = DagJsonEmitter.emit(Elaborator.elaborate(doc))
        val ingest = JsonIngest.parse(json)
        val finalized = Hasher(ingest.rawStore).finalize(ingest.root)
        finalized.nodeIdToHash[finalized.root]
    } catch (e: Exception) {
        null
    }

    // ========================================================================
    // Step 4 (Slice 1) — Implicit prelude
    // ========================================================================

    /**
     * Slice 1: when a local NodeDecl matches a reserved-name spec from
     * [LayerAGrammar.reservedNodes] (same content shape, same author id),
     * drop the local declaration. The forward DagJsonEmitter resolves
     * references to the reserved name back to the synthesized node so the
     * canonical hash is preserved by construction.
     *
     * The pass collects all removable declarations, applies them all at
     * once, and validates by canonical-hash comparison. If the whole-set
     * substitution fails (e.g., a corner case where the canonical encoder
     * disagrees), the pass falls back to per-declaration probing.
     */
    private fun applyImplicitPrelude(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val toRemove = doc.nodes.filter { decl ->
            val spec = LayerAGrammar.reservedNodes[decl.id] ?: return@filter false
            val schema = LayerAGrammar.codes[decl.code] ?: return@filter false
            matchesReservedSpec(decl, spec, schema)
        }.map { it.id }.toSet()
        if (toRemove.isEmpty()) return doc

        val bulk = doc.copy(nodes = doc.nodes.filterNot { it.id in toRemove })
        if (canonicalHash(bulk) == baselineHash) return bulk

        // Per-decl fallback: try each removal independently. Should not
        // normally fire — included for defense against unforeseen corner
        // cases in the reserved-spec ↔ canonical-encoder alignment.
        var current = doc
        var currentHash = baselineHash
        for (id in toRemove) {
            val candidate = current.copy(nodes = current.nodes.filterNot { it.id == id })
            val candidateHash = canonicalHash(candidate) ?: continue
            if (candidateHash == currentHash) {
                current = candidate
            }
        }
        return current
    }

    /**
     * Check whether [decl] is content-equivalent to the reserved [spec]
     * under [schema]'s positional arg shape. Compares each required field
     * by kind: STRING and KEYWORD by string equality against `spec.stringFields`,
     * REFERENCE by author-id equality against `spec.refFields`, LIST_REF
     * by element-wise author-id equality against `spec.refListFields`. Any
     * present optional fields must also match — for the reserved table this
     * is just the `effects` list on a few foreign nodes (e.g., `now`).
     */
    private fun matchesReservedSpec(
        decl: NodeDecl,
        spec: LayerAGrammar.ReservedNodeSpec,
        schema: LayerAGrammar.CodeSchema,
    ): Boolean {
        if (schema.jsonType != spec.jsonType) return false
        var idx = 0
        for (fieldSpec in schema.required) {
            val arg = decl.args.getOrNull(idx++) ?: return false
            if (!fieldMatches(fieldSpec, arg, spec)) return false
        }
        for ((j, fieldSpec) in schema.optional.withIndex()) {
            val i = schema.required.size + j
            val arg = decl.args.getOrNull(i)
            if (arg == null) {
                // Optional absent in decl; spec must not declare a value for it.
                if (spec.stringFields.containsKey(fieldSpec.jsonField)) return false
                if (spec.refFields.containsKey(fieldSpec.jsonField)) return false
                if (spec.refListFields.containsKey(fieldSpec.jsonField)) return false
                continue
            }
            if (!fieldMatches(fieldSpec, arg, spec)) return false
        }
        // Reject if decl has *more* args than schema accounts for; defensive.
        if (decl.args.size > schema.required.size + schema.optional.size) return false
        return true
    }

    // ========================================================================
    // Step 4 (Slice 4) — IF/Match-on-Bool sugar
    // ========================================================================

    /**
     * Detect Match nodes that fold to an IF: exactly two cases, both
     * LiteralPattern over boolT with one BoolLit `true` and one BoolLit
     * `false`. The six wrapper nodes (2 BoolLit, 2 Pattern, 2 MatchCase)
     * must each be used exactly once.
     *
     * On match: rewrite the MAT to an IF (3 args: scrutinee, then, else),
     * remove the six wrapper nodes. Canonical hash is preserved because
     * the IF code's DagJsonEmitter expansion re-synthesizes the same tower
     * on the way back through forward compilation.
     */
    private fun applyIfSugar(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        val useCounts = computeUseCounts(doc)

        val rewrites = mutableListOf<IfRewrite>()
        val toRemove = mutableSetOf<String>()

        for (mat in doc.nodes.filter { it.code == "MAT" }) {
            val rewrite = detectIfPattern(mat, byId, useCounts) ?: continue
            // Skip if any wrapper overlaps an earlier rewrite (defensive).
            if (rewrite.wrapperIds.any { it in toRemove }) continue
            rewrites += rewrite
            toRemove += rewrite.wrapperIds
        }

        if (rewrites.isEmpty()) return doc

        val rewriteById = rewrites.associateBy { it.matId }
        val newNodes = doc.nodes.mapNotNull { node ->
            if (node.id in toRemove) return@mapNotNull null
            val rewrite = rewriteById[node.id] ?: return@mapNotNull node
            NodeDecl(
                id = node.id,
                code = "IF",
                args = listOf(rewrite.scrutinee, rewrite.thenBody, rewrite.elseBody),
                line = node.line,
            )
        }

        val candidate = doc.copy(nodes = newNodes)
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    private data class IfRewrite(
        val matId: String,
        val scrutinee: Arg,
        val thenBody: Arg,
        val elseBody: Arg,
        val wrapperIds: Set<String>,
    )

    private fun detectIfPattern(
        mat: NodeDecl,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
    ): IfRewrite? {
        if (mat.args.size != 2) return null
        val scrutinee = mat.args[0]
        val cases = (mat.args[1] as? Arg.Listing) ?: return null
        if (cases.items.size != 2) return null

        val case1Ref = (cases.items[0] as? Arg.Bare)?.text ?: return null
        val case2Ref = (cases.items[1] as? Arg.Bare)?.text ?: return null
        val case1 = byId[case1Ref] ?: return null
        val case2 = byId[case2Ref] ?: return null
        if (case1.code != "MC" || case2.code != "MC") return null
        if (case1.args.size != 2 || case2.args.size != 2) return null

        val pat1Ref = (case1.args[0] as? Arg.Bare)?.text ?: return null
        val pat2Ref = (case2.args[0] as? Arg.Bare)?.text ?: return null
        val body1 = case1.args[1]
        val body2 = case2.args[1]

        val pat1 = byId[pat1Ref] ?: return null
        val pat2 = byId[pat2Ref] ?: return null
        if (pat1.code != "PLT" || pat2.code != "PLT") return null
        if (pat1.args.size != 2 || pat2.args.size != 2) return null

        val pat1Type = (pat1.args[0] as? Arg.Bare)?.text ?: return null
        val pat2Type = (pat2.args[0] as? Arg.Bare)?.text ?: return null
        if (!isBoolTypeRef(pat1Type, byId)) return null
        if (!isBoolTypeRef(pat2Type, byId)) return null

        val lit1Ref = (pat1.args[1] as? Arg.Bare)?.text ?: return null
        val lit2Ref = (pat2.args[1] as? Arg.Bare)?.text ?: return null
        val lit1 = byId[lit1Ref] ?: return null
        val lit2 = byId[lit2Ref] ?: return null
        if (lit1.code != "BLT" || lit2.code != "BLT") return null

        val lit1Val = (lit1.args.getOrNull(0) as? Arg.BoolL)?.value ?: return null
        val lit2Val = (lit2.args.getOrNull(0) as? Arg.BoolL)?.value ?: return null
        if (lit1Val == lit2Val) return null

        val wrapperIds = setOf(case1Ref, case2Ref, pat1Ref, pat2Ref, lit1Ref, lit2Ref)
        if (wrapperIds.size != 6) return null  // collision means shared wrapper — refuse
        for (id in wrapperIds) {
            if ((useCounts[id] ?: 0) != 1) return null
        }

        val (thenBody, elseBody) = if (lit1Val) (body1 to body2) else (body2 to body1)
        return IfRewrite(
            matId = mat.id,
            scrutinee = scrutinee,
            thenBody = thenBody,
            elseBody = elseBody,
            wrapperIds = wrapperIds,
        )
    }

    /**
     * True when [id] refers to a boolT-equivalent node: either the
     * reserved name "boolT" (which forward emission resolves to a
     * PrimitiveType{Bool}), OR a local PRM with kind "Bool", OR the local
     * node was previously removed by [applyImplicitPrelude] (in which case
     * the reference remains as "boolT" but no longer exists in `byId`).
     */
    private fun isBoolTypeRef(id: String, byId: Map<String, NodeDecl>): Boolean {
        if (id == "boolT") return true
        val node = byId[id] ?: return false
        if (node.code != "PRM") return false
        val kind = (node.args.getOrNull(0) as? Arg.Bare)?.text ?: return false
        return kind == "Bool"
    }

    /**
     * Count how many times each node id appears as an [Arg.Bare] anywhere
     * in [doc]. References inside nested args (Listings, Nested) are
     * counted recursively. The root id gets +1 since the document declares
     * it as the entry point.
     */
    private fun computeUseCounts(doc: LayerADocument): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (node in doc.nodes) {
            for (arg in node.args) countRefsInArg(arg, counts)
        }
        counts.merge(doc.rootId, 1) { a, b -> a + b }
        return counts
    }

    private fun countRefsInArg(arg: Arg, counts: MutableMap<String, Int>) {
        when (arg) {
            is Arg.Bare -> counts.merge(arg.text, 1) { a, b -> a + b }
            is Arg.Listing -> arg.items.forEach { countRefsInArg(it, counts) }
            is Arg.Nested -> arg.args.forEach { countRefsInArg(it, counts) }
            else -> Unit
        }
    }

    // ========================================================================
    // Step 4 (Slice 5) — Compact Lambda parameter declarations
    // ========================================================================

    /**
     * Detect Lambdas whose `parameters` PRCs satisfy the compact-form
     * preconditions and rewrite them as `[name:typeRef ...]` entries with
     * the explicit PRC declarations dropped.
     *
     * A PRC qualifies for compact-form lifting when:
     *  * Its author id equals its `name` string (else the synthesized PRC
     *    would have a different id and break VarRefs).
     *  * Its `name` is a valid identifier (no `:` or other separators).
     *  * It is referenced from the parameters list of exactly one LAM
     *    (sharing a binder across LAMs is technically possible but the
     *    compact form has nowhere to express the sharing).
     *
     * The compact entry is `name:typeRef` when the PRC retains its
     * paramType, or bare `name` when paramType was already stripped by
     * Step 2's recursion-slot rule. The forward Slice 5 emission +
     * Elaborator case 5 re-derive paramType for the bare form.
     */
    private fun applyCompactLam(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }

        // Pre-pass: identify which LAM each PRC is bound by (via parameters).
        val prcOwnerLam = mutableMapOf<String, MutableList<String>>()
        for (lam in doc.nodes.filter { it.code == "LAM" }) {
            val params = (lam.args.getOrNull(0) as? Arg.Listing) ?: continue
            for (param in params.items) {
                val text = (param as? Arg.Bare)?.text ?: continue
                if (':' in text) continue
                val prc = byId[text] ?: continue
                if (prc.code != "PRC") continue
                prcOwnerLam.getOrPut(text) { mutableListOf() } += lam.id
            }
        }

        val toRemove = mutableSetOf<String>()
        val newNodes = doc.nodes.map nodeMap@{ node ->
            if (node.code != "LAM") return@nodeMap node
            val params = (node.args.getOrNull(0) as? Arg.Listing) ?: return@nodeMap node
            var changed = false
            val newItems = params.items.map { param ->
                val text = (param as? Arg.Bare)?.text ?: return@map param
                if (':' in text) return@map param
                val prc = byId[text] ?: return@map param
                if (prc.code != "PRC") return@map param
                val owners = prcOwnerLam[text] ?: return@map param
                if (owners.size != 1 || owners[0] != node.id) return@map param

                val prcName = (prc.args.getOrNull(0) as? Arg.Str)?.value ?: return@map param
                if (prcName != prc.id || !isValidIdentifier(prcName)) return@map param

                val paramType = (prc.args.getOrNull(1) as? Arg.Bare)?.text
                val compactText = if (paramType != null) "$prcName:$paramType" else prcName
                changed = true
                toRemove += text
                Arg.Bare(compactText)
            }
            if (!changed) return@nodeMap node
            val newArgs = node.args.toMutableList()
            newArgs[0] = Arg.Listing(newItems)
            node.copy(args = newArgs)
        }

        if (toRemove.isEmpty()) return doc

        val candidate = doc.copy(
            nodes = newNodes.filterNot { it.id in toRemove }
        )
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    // ========================================================================
    // Step 4 (Slices 2 + 3) — Inline literals and auto-VarRef
    // ========================================================================

    /**
     * Combined inline-substitution pass for two density sugars:
     *
     *  * **Slice 2 (inline literals).** When a single-use literal node
     *    (ILT/FLT/BLT/STR) sits at a REFERENCE / LIST_REF / NULLABLE_REF
     *    arg position of some parent, inline its value at that position
     *    and drop the standalone declaration. The forward DagJsonEmitter's
     *    Slice 2 logic synthesizes a child literal node on the way back.
     *
     *  * **Slice 3 (auto-VarRef).** When a single-use VarRef node sits at
     *    a value-position arg and its binder is a PRC, substitute the PRC
     *    id directly at the parent's arg position and drop the VarRef
     *    declaration. The forward emitter's Slice 3 resolver re-wraps the
     *    bare PRC reference into a VarRef on emit.
     *
     * Both substitutions are gated on slot kind — only REFERENCE,
     * NULLABLE_REF, and LIST_REF positions are eligible (KEYWORD, STRING,
     * INT, etc. positions are left alone; PARAM_LIST and FIELD_LIST entries
     * are binder declarations, not references, so they are also skipped).
     */
    private fun applyInlineSubstitutions(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        val useCounts = computeUseCounts(doc)
        val toRemove = mutableSetOf<String>()

        val newNodes = doc.nodes.map { node ->
            val schema = LayerAGrammar.codes[node.code] ?: return@map node
            var changed = false
            val newArgs = node.args.mapIndexed { i, arg ->
                val spec = positionToFieldSpec(schema, i) ?: return@mapIndexed arg
                val replaced = substituteAtSlot(arg, spec.kind, byId, useCounts, toRemove)
                if (replaced !== arg) changed = true
                replaced
            }
            if (changed) node.copy(args = newArgs) else node
        }

        if (toRemove.isEmpty()) return doc

        val candidate = doc.copy(nodes = newNodes.filterNot { it.id in toRemove })
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    private fun positionToFieldSpec(
        schema: LayerAGrammar.CodeSchema,
        position: Int,
    ): LayerAGrammar.FieldSpec? {
        if (position < schema.required.size) return schema.required[position]
        val optIdx = position - schema.required.size
        return schema.optional.getOrNull(optIdx)
    }

    private fun substituteAtSlot(
        arg: Arg,
        kind: LayerAGrammar.ArgKind,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
        toRemove: MutableSet<String>,
    ): Arg = when (kind) {
        LayerAGrammar.ArgKind.REFERENCE,
        LayerAGrammar.ArgKind.NULLABLE_REF -> trySubstituteValuePosition(arg, byId, useCounts, toRemove)
        LayerAGrammar.ArgKind.LIST_REF -> {
            if (arg !is Arg.Listing) arg
            else {
                val newItems = arg.items.map { item ->
                    trySubstituteValuePosition(item, byId, useCounts, toRemove)
                }
                if (newItems == arg.items) arg else Arg.Listing(newItems)
            }
        }
        else -> arg
    }

    /**
     * Try to replace [arg] (an [Arg.Bare] reference) with either an inline
     * literal value or a PRC-id auto-VarRef. Returns [arg] unchanged when
     * the target is not single-use, not a literal/VarRef leaf, or otherwise
     * ineligible.
     */
    private fun trySubstituteValuePosition(
        arg: Arg,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
        toRemove: MutableSet<String>,
    ): Arg {
        if (arg !is Arg.Bare) return arg
        val targetId = arg.text
        if (targetId in toRemove) return arg
        if ((useCounts[targetId] ?: 0) != 1) return arg
        val target = byId[targetId] ?: return arg
        val replacement: Arg = when (target.code) {
            "ILT" -> target.args.getOrNull(0) as? Arg.IntL ?: return arg
            "FLT" -> target.args.getOrNull(0) as? Arg.FloatL ?: return arg
            "BLT" -> target.args.getOrNull(0) as? Arg.BoolL ?: return arg
            "STR" -> target.args.getOrNull(0) as? Arg.Str ?: return arg
            "VAR" -> {
                val binderRef = (target.args.getOrNull(0) as? Arg.Bare) ?: return arg
                val binder = byId[binderRef.text] ?: return arg
                if (binder.code != "PRC") return arg
                Arg.Bare(binderRef.text)
            }
            else -> return arg
        }
        toRemove += targetId
        return replacement
    }

    // ========================================================================
    // Step 4 (Slice 8) — Inline ProductFieldValue list
    // ========================================================================

    /**
     * Detect ProductValue nodes whose PFV children are single-use and have
     * a Bare-ref value, and fold them into compact `name=ref` entries in
     * the PV's `fields` slot.
     *
     * PFVs whose value was already inlined to a literal by Slice 2 stay
     * explicit (the compact form supports `name=ref` only, not
     * `name=<literal>`). This is consistent with the forward Slice 8
     * grammar.
     */
    private fun applyInlinePfv(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        val useCounts = computeUseCounts(doc)
        val toRemove = mutableSetOf<String>()

        val newNodes = doc.nodes.map { node ->
            if (node.code != "PV") return@map node
            val fields = (node.args.getOrNull(1) as? Arg.Listing) ?: return@map node
            var changed = false
            val newItems = fields.items.map { item ->
                val ref = (item as? Arg.Bare)?.text ?: return@map item
                if ('=' in ref) return@map item
                if ((useCounts[ref] ?: 0) != 1) return@map item
                val pfv = byId[ref] ?: return@map item
                if (pfv.code != "PFV" || pfv.args.size != 2) return@map item
                val fieldName = (pfv.args[0] as? Arg.Str)?.value ?: return@map item
                val valueRef = (pfv.args[1] as? Arg.Bare)?.text ?: return@map item
                if (!isValidIdentifier(fieldName)) return@map item
                toRemove += ref
                changed = true
                Arg.Bare("$fieldName=$valueRef")
            }
            if (!changed) return@map node
            val newArgs = node.args.toMutableList()
            newArgs[1] = Arg.Listing(newItems)
            node.copy(args = newArgs)
        }

        if (toRemove.isEmpty()) return doc
        val candidate = doc.copy(nodes = newNodes.filterNot { it.id in toRemove })
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    // ========================================================================
    // Step 4 (Slice 9) — WHEN / constructor-pattern sugar
    // ========================================================================

    /**
     * Detect Match nodes whose cases are all `PCN` constructor patterns
     * (optionally with a single-binder `PVR` payload) over a common
     * SumType, and collapse them to a single `WHEN scrutinee sumType
     * "Case1 -> body | Case2(binder) -> body | ..."` form.
     *
     * Constraints (per impl/CLAUDE.md Slice 9 note):
     *  * Each MC's pattern is `PCN` (no wildcards, no literal patterns,
     *    no nested constructor patterns).
     *  * Each PCN's payload is either absent (nullary case) or a single
     *    `PVR` whose name is a valid identifier.
     *  * Each MC, PCN, and PVR is used exactly once (no sharing).
     *  * All PCNs share a single SumType `patternType`.
     *  * Each MC's body is a single token (Arg.Bare, IntL, FloatL, BoolL).
     *    Compound expressions (Arg.Nested, Arg.Listing, Arg.Str) can't be
     *    expressed as a single WHEN case-string token.
     */
    private fun applyWhenSugar(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        val useCounts = computeUseCounts(doc)

        val rewrites = mutableListOf<WhenRewrite>()
        val allToRemove = mutableSetOf<String>()

        for (mat in doc.nodes.filter { it.code == "MAT" }) {
            val rewrite = detectWhenPattern(mat, byId, useCounts, doc) ?: continue
            if (rewrite.toRemove.any { it in allToRemove }) continue
            rewrites += rewrite
            allToRemove += rewrite.toRemove
        }

        if (rewrites.isEmpty()) return doc

        val rewriteById = rewrites.associateBy { it.matId }
        val newNodes = doc.nodes.mapNotNull { node ->
            if (node.id in allToRemove) return@mapNotNull null
            val rewrite = rewriteById[node.id] ?: return@mapNotNull node
            NodeDecl(
                id = node.id,
                code = "WHEN",
                args = listOf(rewrite.scrutinee, rewrite.sumType, Arg.Str(rewrite.caseList)),
                line = node.line,
            )
        }

        val candidate = doc.copy(nodes = newNodes)
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    private data class WhenRewrite(
        val matId: String,
        val scrutinee: Arg,
        val sumType: Arg,
        val caseList: String,
        val toRemove: Set<String>,
    )

    private fun detectWhenPattern(
        mat: NodeDecl,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
        doc: LayerADocument,
    ): WhenRewrite? {
        if (mat.args.size != 2) return null
        val scrutinee = mat.args[0]
        val casesArg = (mat.args[1] as? Arg.Listing) ?: return null
        if (casesArg.items.isEmpty()) return null

        val toRemove = mutableSetOf<String>()
        val caseDescs = mutableListOf<String>()
        var sharedSumType: String? = null

        for (caseItem in casesArg.items) {
            val mcId = (caseItem as? Arg.Bare)?.text ?: return null
            val mc = byId[mcId] ?: return null
            if (mc.code != "MC" || mc.args.size != 2) return null

            val pcnRef = mc.args[0]
            val body = mc.args[1]
            val pcnId = (pcnRef as? Arg.Bare)?.text ?: return null
            val pcn = byId[pcnId] ?: return null
            if (pcn.code != "PCN") return null
            if (pcn.args.size < 2 || pcn.args.size > 3) return null
            val patternType = (pcn.args[0] as? Arg.Bare)?.text ?: return null
            val caseName = (pcn.args[1] as? Arg.Str)?.value ?: return null
            if (!isValidIdentifier(caseName)) return null

            if (sharedSumType == null) sharedSumType = patternType
            else if (sharedSumType != patternType) return null

            var binderName: String? = null
            var pvrId: String? = null
            val payloadArg = pcn.args.getOrNull(2)
            if (payloadArg != null && payloadArg != Arg.Null) {
                val id = (payloadArg as? Arg.Bare)?.text ?: return null
                val pvr = byId[id] ?: return null
                if (pvr.code != "PVR" || pvr.args.size != 2) return null
                val pvrName = (pvr.args[1] as? Arg.Str)?.value ?: return null
                if (!isValidIdentifier(pvrName)) return null
                binderName = pvrName
                pvrId = id
                toRemove += id
            }

            // Determine the body token. If the body is a single-use VarRef
            // pointing at the case's PVR, render it as the binder name (the
            // forward WHEN expansion re-synthesizes the VarRef-to-binder),
            // and mark the VarRef for removal so the PVR's external use
            // count drops to zero.
            val bodyToken: String = run {
                if (body is Arg.Bare && binderName != null && pvrId != null) {
                    val varNode = byId[body.text]
                    if (varNode != null && varNode.code == "VAR" && varNode.args.size == 1 &&
                        (useCounts[body.text] ?: 0) == 1) {
                        val varBinder = (varNode.args[0] as? Arg.Bare)?.text
                        if (varBinder == pvrId) {
                            toRemove += body.text
                            return@run binderName
                        }
                    }
                }
                bodyTokenForWhen(body) ?: return null
            }

            toRemove += mcId
            toRemove += pcnId

            caseDescs += if (binderName != null) "$caseName($binderName) -> $bodyToken"
                         else "$caseName -> $bodyToken"
        }

        // Check that nothing outside (toRemove ∪ {mat.id}) references anything
        // in toRemove. Equivalent to: each removed node's references all come
        // from things also being removed or from the MAT being rewritten.
        val excludedFromCount = toRemove + setOf(mat.id)
        val externalCounts = mutableMapOf<String, Int>()
        for (node in doc.nodes) {
            if (node.id in excludedFromCount) continue
            for (arg in node.args) countRefsInArg(arg, externalCounts)
        }
        if (doc.rootId !in excludedFromCount) {
            externalCounts.merge(doc.rootId, 1) { a, b -> a + b }
        }
        for (id in toRemove) {
            if ((externalCounts[id] ?: 0) != 0) return null
        }

        return WhenRewrite(
            matId = mat.id,
            scrutinee = scrutinee,
            sumType = Arg.Bare(sharedSumType!!),
            caseList = caseDescs.joinToString(" | "),
            toRemove = toRemove,
        )
    }

    /**
     * Render a MatchCase body as a single WHEN case-string token. Returns
     * null for shapes that can't be expressed in one token (compound
     * expressions need an explicit MAT/MC tower).
     *
     * Strings are intentionally excluded: WHEN's case-string is itself a
     * quoted string, so embedded quotes would require escape handling the
     * Layer A parser doesn't currently support.
     */
    private fun bodyTokenForWhen(arg: Arg): String? = when (arg) {
        is Arg.Bare -> if (' ' in arg.text || '|' in arg.text || '"' in arg.text) null else arg.text
        is Arg.IntL -> arg.value.toString()
        is Arg.FloatL -> arg.value.toString()
        is Arg.BoolL -> if (arg.value) "true" else "false"
        else -> null
    }

    // ========================================================================
    // Step 4 (Slice 10) — Nested expressions
    // ========================================================================

    /**
     * Inline value-producing single-use nodes as `(CODE args...)` nested
     * expressions at their use site, dropping the standalone declaration.
     * Eligible at REFERENCE / LIST_REF / NULLABLE_REF slots only.
     *
     * The substitution recurses: when inlining target Y into parent X, the
     * pass also tries to inline target Y's args. This produces fully
     * collapsed expression trees in one pass — e.g., the factorial body
     * collapses to a single nested `(APP mul [n (APP recurse [(APP sub [n 1])])])`.
     */
    private fun applyNestedExpressions(doc: LayerADocument): LayerADocument {
        val baselineHash = canonicalHash(doc) ?: return doc
        val byId = doc.nodes.associateBy { it.id }
        val useCounts = computeUseCounts(doc)
        val toRemove = mutableSetOf<String>()
        // Track each node's updated form as we walk the document. When a
        // later subject substitutes a single-use target whose args reference
        // grandchildren that have already been inlined, we want the target's
        // UPDATED args, not the byId's original. Doc order is roughly
        // bottom-up (leaves first per ingest), so processedById is populated
        // before its parents look up the entry.
        val processedById = mutableMapOf<String, NodeDecl>()
        val newNodesList = mutableListOf<NodeDecl>()

        for (node in doc.nodes) {
            val schema = LayerAGrammar.codes[node.code]
            val newArgs = if (schema == null) node.args
            else node.args.mapIndexed { i, arg ->
                val spec = positionToFieldSpec(schema, i) ?: return@mapIndexed arg
                substituteNestedAtSlot(arg, spec.kind, processedById, byId, useCounts, toRemove)
            }
            val processed = if (newArgs == node.args) node else node.copy(args = newArgs)
            processedById[node.id] = processed
            newNodesList += processed
        }

        if (toRemove.isEmpty()) return doc
        val candidate = doc.copy(nodes = newNodesList.filterNot { it.id in toRemove })
        return if (canonicalHash(candidate) == baselineHash) candidate else doc
    }

    private fun substituteNestedAtSlot(
        arg: Arg,
        kind: LayerAGrammar.ArgKind,
        processedById: Map<String, NodeDecl>,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
        toRemove: MutableSet<String>,
    ): Arg = when (kind) {
        LayerAGrammar.ArgKind.REFERENCE,
        LayerAGrammar.ArgKind.NULLABLE_REF -> trySubstituteNested(arg, processedById, byId, useCounts, toRemove)
        LayerAGrammar.ArgKind.LIST_REF -> {
            if (arg !is Arg.Listing) arg
            else {
                val newItems = arg.items.map { item ->
                    trySubstituteNested(item, processedById, byId, useCounts, toRemove)
                }
                if (newItems == arg.items) arg else Arg.Listing(newItems)
            }
        }
        else -> arg
    }

    private fun trySubstituteNested(
        arg: Arg,
        processedById: Map<String, NodeDecl>,
        byId: Map<String, NodeDecl>,
        useCounts: Map<String, Int>,
        toRemove: MutableSet<String>,
    ): Arg {
        if (arg is Arg.Nested) {
            val nestedSchema = LayerAGrammar.codes[arg.code] ?: return arg
            val newArgs = arg.args.mapIndexed { i, a ->
                val spec = positionToFieldSpec(nestedSchema, i) ?: return@mapIndexed a
                substituteNestedAtSlot(a, spec.kind, processedById, byId, useCounts, toRemove)
            }
            return if (newArgs == arg.args) arg else Arg.Nested(arg.code, newArgs)
        }
        if (arg !is Arg.Bare) return arg
        val targetId = arg.text
        if (targetId in toRemove) return arg
        if ((useCounts[targetId] ?: 0) != 1) return arg
        val target = processedById[targetId] ?: byId[targetId] ?: return arg
        val schema = LayerAGrammar.codes[target.code] ?: return arg
        if (!schema.producesValue) return arg
        if (!isSafeToNest(target)) return arg
        toRemove += targetId
        return Arg.Nested(target.code, target.args)
    }

    /**
     * Restrict Slice 10 nesting to nodes whose forward emission survives
     * being inside another nested expression.
     *
     * The Elaborator runs once over top-level NodeDecls BEFORE DagJsonEmitter
     * expansion. Nodes whose forward emission requires Elaborator-side
     * post-processing (i.e., case 5 recursion-slot paramType inference) are
     * unsafe to nest:
     *  * **FIX:** Elaborator's case 5 scans top-level FIX nodes. If FIX is
     *    inlined, the body LAM's recursion-slot PRC never receives paramType
     *    and JsonIngest rejects the result. Skip.
     *  * **LAM with a bare-name compact param** (no `:type` suffix): the
     *    bare entry is a recursion-slot parameter that depends on the
     *    enclosing FIX's case-5 inference. Skip the same way.
     */
    private fun isSafeToNest(target: NodeDecl): Boolean {
        if (target.code == "FIX") return false
        if (target.code == "LAM") {
            val params = (target.args.getOrNull(0) as? Arg.Listing) ?: return true
            val hasBareName = params.items.any { it is Arg.Bare && ':' !in it.text }
            if (hasBareName) return false
        }
        return true
    }

    private fun isValidIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!s[0].isLetter() && s[0] != '_') return false
        return s.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun fieldMatches(
        fieldSpec: LayerAGrammar.FieldSpec,
        arg: Arg,
        spec: LayerAGrammar.ReservedNodeSpec,
    ): Boolean = when (fieldSpec.kind) {
        LayerAGrammar.ArgKind.STRING -> {
            val expected = spec.stringFields[fieldSpec.jsonField]
            expected != null && arg is Arg.Str && arg.value == expected
        }
        LayerAGrammar.ArgKind.KEYWORD -> {
            val expected = spec.stringFields[fieldSpec.jsonField]
            expected != null && arg is Arg.Bare && arg.text == expected
        }
        LayerAGrammar.ArgKind.REFERENCE -> {
            val expected = spec.refFields[fieldSpec.jsonField]
            expected != null && arg is Arg.Bare && arg.text == expected
        }
        LayerAGrammar.ArgKind.LIST_REF -> {
            val expected = spec.refListFields[fieldSpec.jsonField]
            expected != null && arg is Arg.Listing &&
                arg.items.size == expected.size &&
                arg.items.zip(expected).all { (item, exp) ->
                    item is Arg.Bare && item.text == exp
                }
        }
        // Reserved specs don't currently use other kinds (INT/FLOAT/BOOL/
        // PARAM_LIST/FIELD_LIST/NULLABLE_REF). If they do in the future,
        // matching support extends here.
        else -> false
    }
}
