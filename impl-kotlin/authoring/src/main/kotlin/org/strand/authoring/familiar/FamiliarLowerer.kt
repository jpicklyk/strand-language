package org.strand.authoring.familiar

import org.strand.authoring.Arg
import org.strand.authoring.AuthoringError
import org.strand.authoring.AuthoringException
import org.strand.authoring.BuiltinSignatures
import org.strand.authoring.LayerADocument
import org.strand.authoring.LayerAGrammar
import org.strand.authoring.NodeDecl

/**
 * Q-061 Layer F — syntax-directed lowering from the familiar dialect to
 * the Layer A document model ([LayerADocument]).
 *
 * The lowered document flows through the EXISTING Elaborator +
 * DagJsonEmitter pipeline, so Layer F inherits prelude resolution
 * (Q-063 PreludeModule), implicit dotted-builtin expansion
 * (ImplicitBuiltinExpansion, density v5), and `@auto` effect-instance
 * synthesis (AutoEffectSynthesis) rather than reimplementing them.
 * Per the proposal's defining correctness property, the canonical
 * graph is node-for-node what the equivalent Layer A program produces
 * and hashes identically.
 *
 * Construct table (proposal § 4.1), step-1 rows:
 *
 *  * `function main(): T` — the entry point; its body IS the program
 *    root expression (no Lambda wrapper, matching the bare-expression
 *    roots of the corpus and evaluation references).
 *  * `function f(a: T): R uses E { ... }` — Lambda (recursion-free) or
 *    lambda-lifted Fixpoint (N-026) when the body references `f`. The
 *    effects row is always explicit (empty without a `uses` clause),
 *    so an omitted category is a verifier UncoveredEffects, never a
 *    silent inference (scenario 5).
 *  * `const x = e` inside a body — Let (N-013). A top-level `const`
 *    lowers to a named document node referenced directly (graph
 *    sharing; the corpus convention for shared literals/values).
 *  * `type P = {...}` / `type S = {tag...} | ...` — ProductType /
 *    SumType; a direct self-reference produces the RecursiveType +
 *    RecursiveSelf inner/outer product split the corpus recursive
 *    programs use (inner products reference RS inside the RT body;
 *    construction and pattern sites use outer products referencing
 *    the RT itself).
 *  * `match` — Match/MatchCase/Pattern towers, lowered exactly as
 *    Layer A MAT (constructor, literal, wildcard, binder patterns).
 *  * `attempt { e }` — Attempt (N-047); its Ok/Err consumption goes
 *    through the RES Result-sum sugar so patterns hash-equal the
 *    verifier-synthesized result type.
 *  * `declare function f(...): R from "strand-builtin:X" uses C(p)` —
 *    ForeignNode + FunctionType with the category row on both (the
 *    corpus convention for hand-declared builtins). A parameterized
 *    `uses C(p)` entry is a surface-level Q-039 projection: every
 *    call site gets an explicit EffectDecl whose parameters reference
 *    the same argument nodes the application consumes.
 *  * prelude / dotted-registry calls — the callee stays a bare
 *    reserved or dotted name; when the callee carries Q-039
 *    projections for a parameterized category and the governing
 *    `uses` entry is parameterized, the application site gets the
 *    `@auto` marker and AutoEffectSynthesis builds the EffectDecls.
 *  * `use "b3:<hash>" as f` — NodeRef by content hash (the Q-043
 *    `targetHash` ingest form).
 *
 * Everything semantic stays the verifier's job: the lowerer resolves
 * names and synthesizes pattern/payload types, but performs no type
 * checking beyond what the syntax-directed translation needs.
 */
internal class FamiliarLowerer(private val program: FProgram) {

    private val errors = mutableListOf<AuthoringError>()
    private val out = LinkedHashMap<String, NodeDecl>()
    private val usedIds = HashSet<String>()
    private var counter = 0

    // ------------------------------------------------------------------
    // Global declaration tables (surface-level; lowered on demand)
    // ------------------------------------------------------------------
    private val aliases = LinkedHashMap<String, FTypeAlias>()
    private val declares = LinkedHashMap<String, FDeclareFn>()
    private val functions = LinkedHashMap<String, FFunction>()
    private val topConsts = LinkedHashMap<String, FConst>()
    private val preludeImports = LinkedHashSet<String>()
    private val hashImports = LinkedHashMap<String, FUseHash>()

    // Memos.
    private val aliasInfos = HashMap<String, AliasInfo?>()
    private val aliasInProgress = HashSet<String>()
    private val loweredGlobals = HashMap<String, String>()
    private val globalTypes = HashMap<String, SType?>()
    private val globalFnSurfaces = HashMap<String, Pair<List<FParam>, FTypeRef>>()
    private val globalsInProgress = HashSet<String>()
    private val resCache = HashMap<String, String>()
    private val fnTypeCache = HashMap<String, String>()
    private val outerProductCache = HashMap<String, String>()
    /** declare-fn name -> (category id -> projected parameter indices). */
    private val declareProjections = HashMap<String, LinkedHashMap<String, List<Int>>>()
    /** type-node author id -> field name -> field type id (for `.field` typing). */
    private val productFields = HashMap<String, Map<String, String>>()

    /** Effect category name ("Filesystem.Write") -> reserved id ("writeFx"). */
    private val categoryByName: Map<String, String> =
        LayerAGrammar.reservedNodes.entries
            .filter { it.value.jsonType == "EffectCategory" }
            .associate { (id, spec) -> spec.stringFields.getValue("categoryName") to id }

    /** Registry target ("strand-builtin:Math.Abs") -> reserved prelude id ("abs"). */
    private val preludeByTarget: Map<String, String> =
        LayerAGrammar.reservedNodes.entries
            .filter { it.value.jsonType == "ForeignNode" }
            .associate { (id, spec) -> spec.stringFields.getValue("target") to id }

    private val primTypes = mapOf(
        "Int" to "intT", "Float" to "floatT", "String" to "stringT",
        "Bool" to "boolT", "Unit" to "unitT", "Bytes" to "bytesT",
    )

    // ------------------------------------------------------------------
    // Surface types
    // ------------------------------------------------------------------

    /** A lowering-time surface type: a type-node author id, or an attempt-result shape. */
    private sealed class SType {
        /** The author id of a type node (prim, alias, FNT, ...). */
        data class Ref(val id: String) : SType()

        /** The result of an `attempt` — `Ok(okId) | Err(errPayloadT)`. */
        data class Result(val okId: String?) : SType()
    }

    private data class Lowered(val id: String, val type: SType?)

    /** One resolved sum-alias: the type node plus per-case payload info. */
    private class AliasInfo(
        val name: String,
        val typeId: String,
        val isRecursive: Boolean,
        /** Tag -> case info; null for product aliases. */
        val cases: LinkedHashMap<String, CaseInfo>?,
    )

    private class CaseInfo(
        val tag: String,
        /** Field name -> OUTER-resolved type id, in declaration order. */
        val fields: List<Pair<String, String>>,
        val payload: Payload,
    )

    private sealed class Payload {
        object None : Payload()
        data class Single(val typeId: String) : Payload()
        /** Multi-field payload: ids of the products used inside the RT body and at use sites. */
        data class Product(val innerId: String, val aliasName: String, val tag: String) : Payload()
    }

    /** A local binding visible while lowering expressions. */
    private class Local(
        val nodeId: String,
        /** True for ParameterDecl / Let / pattern-variable binders (references need a VarRef). */
        val isBinder: Boolean,
        val type: SType?,
        /** Function-shaped surface info when the binding holds a callable. */
        val fnSurface: Pair<List<FParam>, FTypeRef>? = null,
    )

    /**
     * Per-body lowering context: the lexical locals, the governing
     * `uses` entries (category id -> parameterized?), and — inside a
     * recursive function — the function name plus its recursion-slot
     * ParameterDecl id.
     */
    private data class Ctx(
        val locals: Map<String, Local>,
        val usesParameterized: Map<String, Boolean>,
        val recursionName: String? = null,
        val recursionSlotId: String? = null,
    )

    // ------------------------------------------------------------------
    // Entry
    // ------------------------------------------------------------------

    fun lower(): LayerADocument {
        for (item in program.items) {
            when (item) {
                is FUsePrelude -> {
                    for (name in item.names) {
                        if (name !in LayerAGrammar.reservedNodes) {
                            err(item.line, "'$name' is not a prelude name — see the prelude tables " +
                                "for the reserved set (add / fsRead / netConnect / ...)")
                        } else {
                            preludeImports += name
                        }
                    }
                }
                is FUseHash -> declareGlobal(item.alias, item.line) { hashImports[item.alias] = item }
                is FTypeAlias -> declareGlobal(item.name, item.line) { aliases[item.name] = item }
                is FDeclareFn -> declareGlobal(item.name, item.line) { declares[item.name] = item }
                is FConst -> declareGlobal(item.name, item.line) { topConsts[item.name] = item }
                is FFunction -> declareGlobal(item.name, item.line) { functions[item.name] = item }
            }
        }

        val main = functions["main"]
        if (main == null) {
            err(program.items.lastOrNull()?.line ?: 1,
                "a Layer F program declares `function main(): T { ... }` as its entry point")
            throw AuthoringException(errors)
        }
        if (main.params.isNotEmpty()) {
            err(main.line, "`main` takes no parameters — it is the program root expression")
        }
        if (referencesName(main.body, "main")) {
            err(main.line, "`main` cannot be recursive — extract the recursion into a named function")
        }

        val mainUses = usesMapOf(main.uses)
        val rootId = try {
            lowerBlock(main.body, Ctx(locals = emptyMap(), usesParameterized = mainUses)).id
        } catch (e: LowerAbort) {
            throw AuthoringException(errors.ifEmpty {
                listOf(AuthoringError.TokenError(main.line, "lowering failed"))
            })
        }
        if (errors.isNotEmpty()) throw AuthoringException(errors)
        return LayerADocument(version = 1, rootId = rootId, nodes = out.values.toList())
    }

    private class LowerAbort : RuntimeException()

    private fun declareGlobal(name: String, line: Int, register: () -> Unit) {
        val taken = name in aliases || name in declares || name in functions ||
            name in topConsts || name in hashImports
        if (taken || name in preludeImports) {
            err(line, "'$name' is declared twice at the top level")
            return
        }
        usedIds += name
        register()
    }

    private fun err(line: Int, detail: String) {
        errors += AuthoringError.TokenError(line = line, detail = detail)
    }

    private fun abort(line: Int, detail: String): Nothing {
        err(line, detail)
        throw LowerAbort()
    }

    private fun freshId(base: String): String {
        var candidate = base
        while (candidate in usedIds || candidate in LayerAGrammar.reservedNodes ||
            candidate in BuiltinSignatures.table
        ) {
            candidate = "$base${counter++}"
        }
        usedIds += candidate
        return candidate
    }

    private fun emit(id: String, code: String, args: List<Arg>, line: Int): String {
        out[id] = NodeDecl(id = id, code = code, args = args, line = line)
        return id
    }

    // ------------------------------------------------------------------
    // Type references and aliases
    // ------------------------------------------------------------------

    /**
     * Resolve a surface type reference to a type-node author id.
     * [selfAlias]/[selfTargetId] handle the direct self-reference inside
     * a recursive alias body (INNER mode points at the RecursiveSelf,
     * OUTER mode at the RecursiveType).
     */
    private fun resolveTypeRef(t: FTypeRef, selfAlias: String? = null, selfTargetId: String? = null): String {
        return when (t) {
            is FNamedType -> {
                primTypes[t.name]?.let { return it }
                if (t.name == selfAlias) return selfTargetId
                    ?: abort(t.line, "type alias '${t.name}' references itself outside a union case")
                if (t.name in aliases) {
                    return aliasInfo(t.name)?.typeId ?: abort(t.line, "type alias '${t.name}' failed to lower")
                }
                if (t.name in preludeImports) return t.name
                val hint = if (t.name in LayerAGrammar.reservedNodes) {
                    " — add `use prelude.{${t.name}}`"
                } else ""
                abort(t.line, "unknown type '${t.name}'$hint")
            }
            is FFnType -> {
                val params = t.params.map { resolveTypeRef(it, selfAlias, selfTargetId) }
                val result = resolveTypeRef(t.result, selfAlias, selfTargetId)
                val key = "(${params.joinToString(",")})->$result"
                fnTypeCache.getOrPut(key) {
                    emit(freshId("__f_fnt"), "FNT",
                        listOf(Arg.Listing(params.map { Arg.Bare(it) }), Arg.Bare(result)), t.line)
                }
            }
        }
    }

    /** Lower (memoized) a type alias and return its info. */
    private fun aliasInfo(name: String): AliasInfo? {
        aliasInfos[name]?.let { return it }
        if (name in aliasInfos) return null // previously failed
        val alias = aliases[name] ?: return null
        if (name in aliasInProgress) {
            err(alias.line, "type alias '$name' is indirectly recursive — only a direct " +
                "self-reference inside its own union cases is supported in step 1")
            aliasInfos[name] = null
            return null
        }
        aliasInProgress += name
        val info = try {
            lowerAlias(alias)
        } finally {
            aliasInProgress -= name
        }
        aliasInfos[name] = info
        return info
    }

    private fun lowerAlias(alias: FTypeAlias): AliasInfo {
        if (alias.isProduct) {
            val shape = alias.shapes[0]
            val fieldIds = shape.fields.map { f ->
                if (f.type is FNamedType && f.type.name == alias.name) {
                    abort(f.line, "product alias '${alias.name}' cannot reference itself — " +
                        "recursion needs a tagged union (e.g. Cons/Nil)")
                }
                val typeId = resolveTypeRef(f.type)
                val prfId = emit(freshId("__f_${alias.name}_${f.name}"), "PRF",
                    listOf(Arg.Str(f.name), Arg.Bare(typeId)), f.line)
                Triple(f.name, typeId, prfId)
            }
            val prdId = emit(alias.name, "PRD",
                listOf(Arg.Listing(fieldIds.map { Arg.Bare(it.third) })), alias.line)
            productFields[prdId] = fieldIds.associate { it.first to it.second }
            return AliasInfo(name = alias.name, typeId = prdId, isRecursive = false, cases = null)
        }

        // Tagged union.
        val recursive = alias.shapes.any { shape ->
            shape.fields.any { it.type is FNamedType && (it.type as FNamedType).name == alias.name }
        }
        val rsId = if (recursive) emit(freshId("__f_${alias.name}_self"), "RS", emptyList(), alias.line) else null
        val rtId = if (recursive) alias.name else null
        val sumId = if (recursive) freshId("__f_${alias.name}_sum") else alias.name

        val cases = LinkedHashMap<String, CaseInfo>()
        val caseIds = mutableListOf<String>()
        for (shape in alias.shapes) {
            val tag = shape.tag ?: abort(shape.line, "internal: untagged shape in a union")
            if (tag in cases) abort(shape.line, "union '${alias.name}' declares case '$tag' twice")
            val innerFieldIds = shape.fields.map { f ->
                f.name to resolveTypeRef(f.type, selfAlias = alias.name, selfTargetId = rsId ?: alias.name)
            }
            val outerFieldIds = shape.fields.map { f ->
                f.name to resolveTypeRef(f.type, selfAlias = alias.name, selfTargetId = rtId ?: alias.name)
            }
            val payload: Payload
            val payloadArg: Arg
            when (shape.fields.size) {
                0 -> {
                    payload = Payload.None
                    payloadArg = Arg.Null
                }
                1 -> {
                    payload = Payload.Single(outerFieldIds[0].second)
                    payloadArg = Arg.Bare(innerFieldIds[0].second)
                }
                else -> {
                    val prfIds = shape.fields.mapIndexed { i, f ->
                        emit(freshId("__f_${alias.name}_${tag}_${f.name}"), "PRF",
                            listOf(Arg.Str(f.name), Arg.Bare(innerFieldIds[i].second)), f.line)
                    }
                    val innerPrdId = emit(freshId("__f_${alias.name}_$tag"), "PRD",
                        listOf(Arg.Listing(prfIds.map { Arg.Bare(it) })), shape.line)
                    if (!recursive) {
                        productFields[innerPrdId] = outerFieldIds.toMap()
                    }
                    payload = Payload.Product(innerId = innerPrdId, aliasName = alias.name, tag = tag)
                    payloadArg = Arg.Bare(innerPrdId)
                }
            }
            val scsId = emit(freshId("__f_${alias.name}_case_$tag"), "SCS",
                listOf(Arg.Str(tag), payloadArg), shape.line)
            caseIds += scsId
            cases[tag] = CaseInfo(tag = tag, fields = outerFieldIds, payload = payload)
        }
        emit(sumId, "SUM", listOf(Arg.Listing(caseIds.map { Arg.Bare(it) })), alias.line)
        if (recursive) emit(alias.name, "RT", listOf(Arg.Bare(sumId)), alias.line)
        return AliasInfo(
            name = alias.name,
            typeId = if (recursive) alias.name else sumId,
            isRecursive = recursive,
            cases = cases,
        )
    }

    /**
     * The product used at construction/pattern sites for a multi-field
     * case. Equal to the inner product for non-recursive aliases; for
     * recursive ones it is the OUTER product whose self-fields reference
     * the RecursiveType (emitted lazily so unused outers never appear).
     */
    private fun outerProductId(info: AliasInfo, case: CaseInfo): String {
        val payload = case.payload as Payload.Product
        if (!info.isRecursive) return payload.innerId
        return outerProductCache.getOrPut("${info.name}|${case.tag}") {
            val alias = aliases.getValue(info.name)
            val shape = alias.shapes.first { it.tag == case.tag }
            val prfIds = shape.fields.mapIndexed { i, f ->
                emit(freshId("__f_${info.name}_${case.tag}_outer_${f.name}"), "PRF",
                    listOf(Arg.Str(f.name), Arg.Bare(case.fields[i].second)), f.line)
            }
            val outerId = emit(freshId("__f_${info.name}_${case.tag}_outer"), "PRD",
                listOf(Arg.Listing(prfIds.map { Arg.Bare(it) })), shape.line)
            productFields[outerId] = case.fields.toMap()
            outerId
        }
    }

    /** The PVR/binder type for a constructor pattern of [case]. Null when payload-less. */
    private fun payloadTypeId(info: AliasInfo, case: CaseInfo): String? = when (val p = case.payload) {
        is Payload.None -> null
        is Payload.Single -> p.typeId
        is Payload.Product -> outerProductId(info, case)
    }

    // ------------------------------------------------------------------
    // Globals
    // ------------------------------------------------------------------

    private fun lowerGlobal(name: String, line: Int): String {
        loweredGlobals[name]?.let { return it }
        if (name in globalsInProgress) {
            abort(line, "'$name' is mutually recursive with another declaration — " +
                "step 1 supports direct self-recursion only")
        }
        globalsInProgress += name
        try {
            val id = when {
                name in declares -> lowerDeclareFn(declares.getValue(name))
                name in functions -> lowerFunction(functions.getValue(name))
                name in topConsts -> {
                    val c = topConsts.getValue(name)
                    val lowered = lowerExpr(c.value, Ctx(emptyMap(), emptyMap()))
                    globalTypes[name] = c.annotation?.let { SType.Ref(resolveTypeRef(it)) } ?: lowered.type
                    if (c.value is FArrow) {
                        globalFnSurfaces[name] = (c.value as FArrow).params to
                            (c.annotation as? FFnType)?.result.orUnknown(c)
                    }
                    lowered.id
                }
                name in hashImports -> {
                    val h = hashImports.getValue(name)
                    emit(name, "NRF", listOf(Arg.Str("b3:${h.hashHex}")), h.line)
                }
                else -> abort(line, "internal: '$name' is not a global")
            }
            loweredGlobals[name] = id
            return id
        } finally {
            globalsInProgress -= name
        }
    }

    private fun FTypeRef?.orUnknown(c: FConst): FTypeRef =
        this ?: FNamedType("Unit", c.line) // placeholder; arrow-const call typing without annotation is best-effort

    private fun lowerDeclareFn(d: FDeclareFn): String {
        val paramTypeIds = d.params.map { resolveTypeRef(it.type) }
        val resultId = resolveTypeRef(d.result)
        val categoryIds = mutableListOf<String>()
        val projections = LinkedHashMap<String, List<Int>>()
        for (entry in d.uses) {
            val catId = resolveCategory(entry.category, entry.line)
            categoryIds += catId
            if (entry.args != null) {
                val indices = entry.args.map { arg ->
                    val pname = (arg as? FIdent)?.name
                        ?: abort(entry.line, "a declare-function `uses` entry binds parameter NAMES " +
                            "(the surface Q-039 projection), e.g. uses ${entry.category}(${d.params.firstOrNull()?.name ?: "p"})")
                    val idx = d.params.indexOfFirst { it.name == pname }
                    if (idx < 0) abort(entry.line, "uses ${entry.category}($pname): '$pname' is not " +
                        "a parameter of declare function '${d.name}'")
                    idx
                }
                val arity = categoryParamCount(catId)
                if (arity != null && arity != indices.size) {
                    err(entry.line, "effect category '${entry.category}' takes $arity " +
                        "parameter(s) but the uses entry binds ${indices.size}")
                }
                projections[catId] = indices
            }
        }
        declareProjections[d.name] = projections
        globalFnSurfaces[d.name] = d.params to d.result

        val fntArgs = mutableListOf<Arg>(
            Arg.Listing(paramTypeIds.map { Arg.Bare(it) }),
            Arg.Bare(resultId),
        )
        if (categoryIds.isNotEmpty()) fntArgs += Arg.Listing(categoryIds.map { Arg.Bare(it) })
        val fntId = emit(freshId("__f_${d.name}_T"), "FNT", fntArgs, d.line)

        val fnArgs = mutableListOf<Arg>(Arg.Str(d.target), Arg.Bare(fntId))
        if (categoryIds.isNotEmpty()) fnArgs += Arg.Listing(categoryIds.map { Arg.Bare(it) })
        return emit(d.name, "FN", fnArgs, d.line)
    }

    private fun lowerFunction(f: FFunction): String {
        globalFnSurfaces[f.name] = f.params to f.result
        val effectIds = f.uses.map { resolveCategory(it.category, it.line) }
        val usesMap = usesMapOf(f.uses)
        val recursive = referencesName(f.body, f.name)

        val paramLocals = LinkedHashMap<String, Local>()
        val prcIds = f.params.map { p ->
            val typeId = resolveTypeRef(p.type)
            val prcId = emit(freshId("__f_${f.name}_${p.name}"), "PRC",
                listOf(Arg.Str(p.name), Arg.Bare(typeId)), p.line)
            paramLocals[p.name] = Local(
                nodeId = prcId, isBinder = true, type = SType.Ref(typeId),
                fnSurface = (p.type as? FFnType)?.let { emptyList<FParam>() to it.result },
            )
            prcId
        }

        if (!recursive) {
            val body = lowerBlock(f.body, Ctx(locals = paramLocals, usesParameterized = usesMap))
            return emit(f.name, "LAM", listOf(
                Arg.Listing(prcIds.map { Arg.Bare(it) }),
                Arg.Bare(body.id),
                Arg.Listing(effectIds.map { Arg.Bare(it) }),
            ), f.line)
        }

        // Named recursion: lambda-lift to Fixpoint (N-026). The body
        // Lambda's first parameter is the recursion slot, typed by the
        // function's own FunctionType; self-calls go through a VarRef to
        // the slot, exactly the corpus FIX shape.
        val fntArgs = mutableListOf<Arg>(
            Arg.Listing(f.params.map { Arg.Bare(resolveTypeRef(it.type)) }),
            Arg.Bare(resolveTypeRef(f.result)),
        )
        if (effectIds.isNotEmpty()) fntArgs += Arg.Listing(effectIds.map { Arg.Bare(it) })
        val fntId = emit(freshId("__f_${f.name}_T"), "FNT", fntArgs, f.line)
        val recSlotId = emit(freshId("__f_${f.name}_rec"), "PRC",
            listOf(Arg.Str(f.name), Arg.Bare(fntId)), f.line)
        val ctx = Ctx(
            locals = paramLocals,
            usesParameterized = usesMap,
            recursionName = f.name,
            recursionSlotId = recSlotId,
        )
        val body = lowerBlock(f.body, ctx)
        val lamId = emit(freshId("__f_${f.name}_lam"), "LAM", listOf(
            Arg.Listing((listOf(recSlotId) + prcIds).map { Arg.Bare(it) }),
            Arg.Bare(body.id),
            Arg.Listing(effectIds.map { Arg.Bare(it) }),
        ), f.line)
        return emit(f.name, "FIX", listOf(Arg.Bare(fntId), Arg.Bare(lamId)), f.line)
    }

    private fun usesMapOf(uses: List<FUsesEntry>): Map<String, Boolean> {
        val map = LinkedHashMap<String, Boolean>()
        for (entry in uses) {
            map[resolveCategory(entry.category, entry.line)] = entry.args != null
        }
        return map
    }

    private fun resolveCategory(name: String, line: Int): String {
        categoryByName[name]?.let { return it }
        val spec = LayerAGrammar.reservedNodes[name]
        if (spec?.jsonType == "EffectCategory") return name
        val known = categoryByName.keys.sorted().joinToString(", ")
        abort(line, "unknown effect category '$name' — the step-1 categories are the prelude's: $known")
    }

    private fun categoryParamCount(categoryId: String): Int? {
        val spec = LayerAGrammar.reservedNodes[categoryId] ?: return null
        if (spec.jsonType != "EffectCategory") return null
        return spec.refListFields["parameters"]?.size ?: 0
    }

    // ------------------------------------------------------------------
    // Blocks and expressions
    // ------------------------------------------------------------------

    private fun lowerBlock(block: FBlock, ctx: Ctx): Lowered {
        if (block.consts.isEmpty()) return lowerExpr(block.result, ctx)
        // Lower const values left to right, then fold the Lets from the
        // inside out so each value sits outside its own binder scope.
        var locals = ctx.locals
        val lets = mutableListOf<Triple<FConst, String, SType?>>() // (const, valueId, type)
        for (c in block.consts) {
            if (locals.containsKey(c.name) || c.name in topConsts || c.name in functions ||
                c.name in declares || c.name in hashImports
            ) {
                err(c.line, "'${c.name}' shadows an existing binding — pick a fresh name")
            }
            val value = lowerExpr(c.value, ctx.copy(locals = locals))
            val letId = freshId("__f_let_${c.name}")
            val type = c.annotation?.let { SType.Ref(resolveTypeRef(it)) } ?: value.type
            locals = locals + (c.name to Local(
                nodeId = letId, isBinder = true, type = type,
                fnSurface = (c.value as? FArrow)?.let { it.params to ((c.annotation as? FFnType)?.result ?: inferArrowResult(it)) },
            ))
            lets += Triple(c, value.id, type)
        }
        val result = lowerExpr(block.result, ctx.copy(locals = locals))
        var bodyId = result.id
        for ((i, entry) in lets.withIndex().toList().asReversed()) {
            val (c, valueId, _) = entry
            val letId = locals.getValue(c.name).nodeId
            // Re-emit each Let with its body; ids were reserved above.
            emit(letId, "LET", listOf(Arg.Str(c.name), Arg.Bare(valueId), Arg.Bare(bodyId)), c.line)
            bodyId = letId
            if (i == 0) break
        }
        return Lowered(bodyId, result.type)
    }

    /** Best-effort surface result type of an arrow (for call typing of arrow consts). */
    private fun inferArrowResult(arrow: FArrow): FTypeRef = FNamedType("Unit", arrow.line)

    private fun lowerExpr(e: FExpr, ctx: Ctx): Lowered = when (e) {
        is FIntLit -> Lowered(emit(freshId("__f_lit"), "ILT", listOf(Arg.IntL(e.value)), e.line), SType.Ref("intT"))
        is FFloatLit -> Lowered(emit(freshId("__f_lit"), "FLT", listOf(Arg.FloatL(e.value)), e.line), SType.Ref("floatT"))
        is FStringLit -> Lowered(emit(freshId("__f_lit"), "STR", listOf(Arg.Str(e.value)), e.line), SType.Ref("stringT"))
        is FBoolLit -> Lowered(emit(freshId("__f_lit"), "BLT", listOf(Arg.BoolL(e.value)), e.line), SType.Ref("boolT"))
        is FIdent -> lowerIdent(e, ctx)
        is FFieldGet -> lowerFieldGet(e, ctx)
        is FCall -> lowerCall(e, ctx)
        is FObjectLit -> lowerObjectLit(e, ctx)
        is FMatch -> lowerMatch(e, ctx)
        is FAttempt -> {
            val body = lowerExpr(e.body, ctx)
            val tryId = emit(freshId("__f_try"), "TRY", listOf(Arg.Bare(body.id)), e.line)
            Lowered(tryId, SType.Result((body.type as? SType.Ref)?.id))
        }
        is FArrow -> lowerArrow(e, ctx)
    }

    private fun lowerIdent(e: FIdent, ctx: Ctx): Lowered {
        ctx.locals[e.name]?.let { local ->
            return if (local.isBinder) {
                Lowered(emit(freshId("__f_var"), "VAR", listOf(Arg.Bare(local.nodeId)), e.line), local.type)
            } else {
                Lowered(local.nodeId, local.type)
            }
        }
        if (e.name == ctx.recursionName) {
            return Lowered(
                emit(freshId("__f_var"), "VAR", listOf(Arg.Bare(ctx.recursionSlotId!!)), e.line),
                null,
            )
        }
        if (e.name in topConsts) {
            val id = lowerGlobal(e.name, e.line)
            return Lowered(id, globalTypes[e.name])
        }
        if (e.name in functions || e.name in declares || e.name in hashImports) {
            return Lowered(lowerGlobal(e.name, e.line), null)
        }
        if (e.name in preludeImports) {
            return Lowered(e.name, preludeValueType(e.name))
        }
        if (e.name in LayerAGrammar.reservedNodes) {
            abort(e.line, "'${e.name}' is a prelude name — import it first: use prelude.{${e.name}}")
        }
        if (e.name in aliases) {
            abort(e.line, "'${e.name}' is a type alias — construct values with an object literal " +
                "{ tag: \"...\", ... } or { field: value, ... }")
        }
        abort(e.line, "unknown name '${e.name}'")
    }

    private fun preludeValueType(name: String): SType? {
        val spec = LayerAGrammar.reservedNodes[name] ?: return null
        return when (spec.jsonType) {
            "ForeignNode" -> null // callable; result typed at the call
            else -> null
        }
    }

    private fun lowerFieldGet(e: FFieldGet, ctx: Ctx): Lowered {
        dottedName(e)?.let { dotted ->
            if (rootIsUnresolvable(dotted.first(), ctx)) {
                abort(e.line, "'${dotted.joinToString(".")}' is not a registry builtin name — " +
                    "did you mean to call it, or is the root name undeclared?")
            }
        }
        val target = lowerExpr(e.target, ctx)
        val pfgId = emit(freshId("__f_pfg"), "PFG", listOf(Arg.Bare(target.id), Arg.Str(e.field)), e.line)
        val fieldType = (target.type as? SType.Ref)?.let { fieldTypeOf(it.id, e.field) }
        return Lowered(pfgId, fieldType?.let { SType.Ref(it) })
    }

    private fun fieldTypeOf(typeId: String, field: String): String? {
        productFields[typeId]?.let { return it[field] }
        // Reserved product types (errPayloadT, httpRespT, ...).
        val spec = LayerAGrammar.reservedNodes[typeId] ?: return null
        if (spec.jsonType != "ProductType") return null
        for (prfId in spec.refListFields["fields"].orEmpty()) {
            val prf = LayerAGrammar.reservedNodes[prfId] ?: continue
            if (prf.stringFields["name"] == field) return prf.refFields["fieldType"]
        }
        return null
    }

    /** The dotted-name segments of a FieldGet chain over a bare identifier root, or null. */
    private fun dottedName(e: FExpr): List<String>? = when (e) {
        is FIdent -> listOf(e.name)
        is FFieldGet -> dottedName(e.target)?.plus(e.field)
        else -> null
    }

    private fun rootIsUnresolvable(root: String, ctx: Ctx): Boolean =
        root !in ctx.locals && root != ctx.recursionName && root !in topConsts &&
            root !in functions && root !in declares && root !in hashImports &&
            root !in preludeImports && root[0].isUpperCase()

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    private fun lowerCall(e: FCall, ctx: Ctx): Lowered {
        val argIds = e.args.map { lowerExpr(it, ctx) }

        // Dotted registry-builtin callee (density-v5 interop): keep the
        // bare dotted name; ImplicitBuiltinExpansion synthesizes the FN.
        val dotted = (e.callee as? FFieldGet)?.let { dottedName(it) }
        if (dotted != null && dotted.size >= 2 && rootIsUnresolvable(dotted.first(), ctx)) {
            val name = dotted.joinToString(".")
            val sig = BuiltinSignatures.lookup(name)
            if (sig != null) {
                val auto = sig.effects.any { spec ->
                    val catId = spec.reservedId ?: return@any false
                    !spec.sources.isNullOrEmpty() && ctx.usesParameterized[catId] == true
                }
                val appArgs = mutableListOf<Arg>(Arg.Bare(name), Arg.Listing(argIds.map { Arg.Bare(it.id) }))
                if (auto) appArgs += Arg.Bare("@auto")
                val appId = emit(freshId("__f_app"), "APP", appArgs, e.line)
                val resultType = (sig.result as? BuiltinSignatures.Sig.Prim)?.let {
                    SType.Ref(primTypes.getValue(it.kind))
                }
                return Lowered(appId, resultType)
            }
            // Prelude-covered registry name (Math.Abs -> abs): call the
            // reserved ForeignNode directly; no import needed for the
            // dotted spelling.
            preludeByTarget[BuiltinSignatures.targetFor(name)]?.let { reservedId ->
                return lowerPreludeCall(reservedId, argIds, e.line, ctx)
            }
            val reason = BuiltinSignatures.exclusions[name]
            if (reason != null) {
                abort(e.line, "registry builtin '$name' is excluded from the implicit " +
                    "surface ($reason) — use `declare function ... from \"strand-builtin:$name\"`")
            }
            abort(e.line, "unknown registry builtin '$name'")
        }

        // Named callees.
        val calleeName = (e.callee as? FIdent)?.name
        if (calleeName != null) {
            return lowerNamedCall(e, calleeName, argIds, ctx)
        }

        // Computed callee (call of a field-get / call result).
        val callee = lowerExpr(e.callee, ctx)
        val appId = emit(freshId("__f_app"), "APP",
            listOf(Arg.Bare(callee.id), Arg.Listing(argIds.map { Arg.Bare(it.id) })), e.line)
        return Lowered(appId, null)
    }

    private fun lowerNamedCall(e: FCall, name: String, argIds: List<Lowered>, ctx: Ctx): Lowered {
        // Self-recursion: VarRef to the Fixpoint recursion slot.
        if (name == ctx.recursionName && name !in ctx.locals) {
            val varId = emit(freshId("__f_var"), "VAR", listOf(Arg.Bare(ctx.recursionSlotId!!)), e.line)
            val appId = emit(freshId("__f_app"), "APP",
                listOf(Arg.Bare(varId), Arg.Listing(argIds.map { Arg.Bare(it.id) })), e.line)
            val result = functions[name]?.result?.let { SType.Ref(resolveTypeRef(it)) }
            return Lowered(appId, result)
        }

        ctx.locals[name]?.let { local ->
            val calleeId = if (local.isBinder) {
                emit(freshId("__f_var"), "VAR", listOf(Arg.Bare(local.nodeId)), e.line)
            } else local.nodeId
            val appId = emit(freshId("__f_app"), "APP",
                listOf(Arg.Bare(calleeId), Arg.Listing(argIds.map { Arg.Bare(it.id) })), e.line)
            val result = local.fnSurface?.second?.let { runCatching { SType.Ref(resolveTypeRef(it)) }.getOrNull() }
            return Lowered(appId, result)
        }

        if (name in functions) {
            val calleeId = lowerGlobal(name, e.line)
            val appId = emit(freshId("__f_app"), "APP",
                listOf(Arg.Bare(calleeId), Arg.Listing(argIds.map { Arg.Bare(it.id) })), e.line)
            return Lowered(appId, SType.Ref(resolveTypeRef(functions.getValue(name).result)))
        }

        if (name in declares) {
            val d = declares.getValue(name)
            val calleeId = lowerGlobal(name, e.line)
            if (argIds.size != d.params.size) {
                err(e.line, "'$name' takes ${d.params.size} argument(s) but the call supplies ${argIds.size}")
            }
            // Surface Q-039 projection: one explicit EffectDecl per
            // parameterized uses entry, referencing the SAME argument
            // nodes the application consumes.
            val efdIds = declareProjections[name].orEmpty().map { (catId, indices) ->
                val params = indices.map { idx ->
                    argIds.getOrNull(idx)?.id
                        ?: abort(e.line, "the projection for '$catId' references argument ${idx + 1}, " +
                            "but the call supplies only ${argIds.size}")
                }
                emit(freshId("__f_efd"), "EFD",
                    listOf(Arg.Bare(catId), Arg.Listing(params.map { Arg.Bare(it) })), e.line)
            }
            val appArgs = mutableListOf<Arg>(Arg.Bare(calleeId), Arg.Listing(argIds.map { Arg.Bare(it.id) }))
            if (efdIds.isNotEmpty()) {
                appArgs += Arg.Listing(emptyList())
                appArgs += Arg.Listing(efdIds.map { Arg.Bare(it) })
            }
            val appId = emit(freshId("__f_app"), "APP", appArgs, e.line)
            return Lowered(appId, SType.Ref(resolveTypeRef(d.result)))
        }

        if (name in topConsts || name in hashImports) {
            val calleeId = lowerGlobal(name, e.line)
            val appId = emit(freshId("__f_app"), "APP",
                listOf(Arg.Bare(calleeId), Arg.Listing(argIds.map { Arg.Bare(it.id) })), e.line)
            val result = globalFnSurfaces[name]?.second?.let { runCatching { SType.Ref(resolveTypeRef(it)) }.getOrNull() }
            return Lowered(appId, result)
        }

        if (name in preludeImports) {
            val spec = LayerAGrammar.reservedNodes.getValue(name)
            if (spec.jsonType != "ForeignNode") {
                abort(e.line, "prelude name '$name' is not callable (${spec.jsonType})")
            }
            return lowerPreludeCall(name, argIds, e.line, ctx)
        }

        if (name in LayerAGrammar.reservedNodes) {
            abort(e.line, "'$name' is a prelude name — import it first: use prelude.{$name}")
        }
        abort(e.line, "unknown function '$name'")
    }

    /** Application of a reserved prelude ForeignNode by id. */
    private fun lowerPreludeCall(reservedId: String, argIds: List<Lowered>, line: Int, ctx: Ctx): Lowered {
        val spec = LayerAGrammar.reservedNodes.getValue(reservedId)
        val auto = spec.effectProjections.any { proj ->
            proj.sources.isNotEmpty() && ctx.usesParameterized[proj.category] == true
        }
        val appArgs = mutableListOf<Arg>(Arg.Bare(reservedId), Arg.Listing(argIds.map { Arg.Bare(it.id) }))
        if (auto) appArgs += Arg.Bare("@auto")
        val appId = emit(freshId("__f_app"), "APP", appArgs, line)
        val resultId = spec.refFields["foreignType"]
            ?.let { LayerAGrammar.reservedNodes[it]?.refFields?.get("result") }
        return Lowered(appId, resultId?.let { SType.Ref(it) })
    }

    // ------------------------------------------------------------------
    // Object literals (product and sum construction)
    // ------------------------------------------------------------------

    private fun lowerObjectLit(e: FObjectLit, ctx: Ctx): Lowered {
        if (e.tag != null) {
            // Sum construction: resolve the alias by tag at the surface level
            // so only the alias actually used gets lowered.
            val candidates = aliases.values.filter { a ->
                !a.isProduct && a.shapes.any { it.tag == e.tag }
            }
            val alias = when {
                candidates.isEmpty() -> abort(e.line, "no union alias declares a case tagged \"${e.tag}\"")
                candidates.size > 1 -> abort(e.line, "tag \"${e.tag}\" is ambiguous between " +
                    candidates.joinToString(", ") { it.name } + " — rename one of the cases")
                else -> candidates.single()
            }
            val info = aliasInfo(alias.name) ?: abort(e.line, "type alias '${alias.name}' failed to lower")
            val case = info.cases!!.getValue(e.tag)
            val declared = case.fields.map { it.first }
            val supplied = e.fields.map { it.name }
            if (declared.toSet() != supplied.toSet()) {
                abort(e.line, "case \"${e.tag}\" of '${alias.name}' has fields " +
                    "{${declared.joinToString(", ")}} but the literal supplies {${supplied.joinToString(", ")}}")
            }
            val valueById = e.fields.associate { f -> f.name to lowerExpr(f.value, ctx) }
            val payloadArg: Arg = when (case.payload) {
                is Payload.None -> Arg.Null
                is Payload.Single -> Arg.Bare(valueById.getValue(declared[0]).id)
                is Payload.Product -> {
                    val prdId = outerProductId(info, case)
                    val pfvIds = case.fields.map { (fname, _) ->
                        emit(freshId("__f_pfv"), "PFV",
                            listOf(Arg.Str(fname), Arg.Bare(valueById.getValue(fname).id)), e.line)
                    }
                    val pvId = emit(freshId("__f_pv"), "PV",
                        listOf(Arg.Bare(prdId), Arg.Listing(pfvIds.map { Arg.Bare(it) })), e.line)
                    Arg.Bare(pvId)
                }
            }
            val svId = emit(freshId("__f_sv"), "SV",
                listOf(Arg.Bare(info.typeId), Arg.Str(e.tag), payloadArg), e.line)
            return Lowered(svId, SType.Ref(info.typeId))
        }

        // Product construction: resolve the alias by exact field-name match.
        val supplied = e.fields.map { it.name }.toSet()
        val candidates = aliases.values.filter { a ->
            a.isProduct && a.shapes[0].fields.map { it.name }.toSet() == supplied
        }
        val alias = when {
            candidates.isEmpty() -> abort(e.line, "no product alias matches the fields " +
                "{${supplied.joinToString(", ")}} — declare one with `type P = { ... }` " +
                "(or add the tag: \"...\" discriminant for a union case)")
            candidates.size > 1 -> abort(e.line, "fields {${supplied.joinToString(", ")}} are ambiguous " +
                "between " + candidates.joinToString(", ") { it.name })
            else -> candidates.single()
        }
        val info = aliasInfo(alias.name) ?: abort(e.line, "type alias '${alias.name}' failed to lower")
        val valueById = e.fields.associate { f -> f.name to lowerExpr(f.value, ctx) }
        val pfvIds = alias.shapes[0].fields.map { f ->
            emit(freshId("__f_pfv"), "PFV",
                listOf(Arg.Str(f.name), Arg.Bare(valueById.getValue(f.name).id)), e.line)
        }
        val pvId = emit(freshId("__f_pv"), "PV",
            listOf(Arg.Bare(info.typeId), Arg.Listing(pfvIds.map { Arg.Bare(it) })), e.line)
        return Lowered(pvId, SType.Ref(info.typeId))
    }

    // ------------------------------------------------------------------
    // Match
    // ------------------------------------------------------------------

    private sealed class SumResolution {
        data class Alias(val info: AliasInfo) : SumResolution()
        data class ResultSum(val okId: String?) : SumResolution()
        object None : SumResolution()
    }

    private fun lowerMatch(e: FMatch, ctx: Ctx): Lowered {
        val scrutinee = lowerExpr(e.scrutinee, ctx)
        val resolution = resolveMatchSum(e, scrutinee.type)

        val sumTypeId: String? = when (resolution) {
            is SumResolution.Alias -> resolution.info.typeId
            is SumResolution.ResultSum -> {
                val okId = resolution.okId
                    ?: abort(e.line, "the attempt result's Ok payload type could not be determined — " +
                        "bind the attempt to a const first or simplify the body")
                resCache.getOrPut(okId) {
                    emit(freshId("__f_res"), "RES", listOf(Arg.Bare(okId)), e.line)
                }
            }
            is SumResolution.None -> (scrutinee.type as? SType.Ref)?.id
        }

        val caseIds = mutableListOf<String>()
        var firstBodyType: SType? = null
        for (arm in e.arms) {
            val (patternId, bodyLocals) = lowerPattern(arm.pattern, resolution, sumTypeId, scrutinee.type, e, ctx)
            val body = lowerExpr(arm.body, ctx.copy(locals = ctx.locals + bodyLocals))
            if (firstBodyType == null) firstBodyType = body.type
            caseIds += emit(freshId("__f_mc"), "MC",
                listOf(Arg.Bare(patternId), Arg.Bare(body.id)), arm.line)
        }
        val matId = emit(freshId("__f_mat"), "MAT",
            listOf(Arg.Bare(scrutinee.id), Arg.Listing(caseIds.map { Arg.Bare(it) })), e.line)
        return Lowered(matId, firstBodyType)
    }

    private fun resolveMatchSum(e: FMatch, scrutineeType: SType?): SumResolution {
        if (scrutineeType is SType.Result) return SumResolution.ResultSum(scrutineeType.okId)
        if (scrutineeType is SType.Ref) {
            aliasInfos.values.filterNotNull().firstOrNull { it.typeId == scrutineeType.id && it.cases != null }
                ?.let { return SumResolution.Alias(it) }
        }
        val ctorNames = e.arms.mapNotNull { (it.pattern as? FCtorPattern)?.ctor }
        if (ctorNames.isEmpty()) {
            // Bare-name arms may still be payload-less constructors; check
            // whether any arm name appears as a case tag somewhere.
            val nameArms = e.arms.mapNotNull { (it.pattern as? FNamePattern)?.name }
            val tagged = aliases.values.filter { a ->
                !a.isProduct && nameArms.any { n -> a.shapes.any { it.tag == n } }
            }
            if (tagged.size == 1) {
                val info = aliasInfo(tagged.single().name) ?: return SumResolution.None
                return SumResolution.Alias(info)
            }
            return SumResolution.None
        }
        if (ctorNames.all { it == "Ok" || it == "Err" }) {
            abort(e.line, "Ok/Err patterns consume an attempt result, but the scrutinee is not " +
                "an attempt — wrap the fallible expression: match (attempt { ... }) { Ok(v) => ..., Err(e) => ... }")
        }
        val candidates = aliases.values.filter { a ->
            !a.isProduct && ctorNames.all { n -> a.shapes.any { it.tag == n } }
        }
        return when {
            candidates.isEmpty() -> abort(e.line, "no union alias declares the case(s) " +
                ctorNames.distinct().joinToString(", ") { "\"$it\"" })
            candidates.size > 1 -> abort(e.line, "the cases ${ctorNames.distinct()} are ambiguous between " +
                candidates.joinToString(", ") { it.name })
            else -> {
                val info = aliasInfo(candidates.single().name)
                    ?: abort(e.line, "type alias '${candidates.single().name}' failed to lower")
                SumResolution.Alias(info)
            }
        }
    }

    /** Lower one pattern; returns the pattern node id plus the binder locals it introduces. */
    private fun lowerPattern(
        p: FPattern,
        resolution: SumResolution,
        sumTypeId: String?,
        scrutineeType: SType?,
        match: FMatch,
        ctx: Ctx,
    ): Pair<String, Map<String, Local>> {
        fun scrutineeTypeId(): String = (scrutineeType as? SType.Ref)?.id
            ?: sumTypeId
            ?: abort(p.line, "the scrutinee type could not be determined for this pattern — " +
                "annotate the value (e.g. bind it to a typed const) or match on constructors")

        return when (p) {
            is FLitPattern -> {
                val lit = lowerExpr(p.literal, ctx)
                val patType = (lit.type as SType.Ref).id
                emit(freshId("__f_pat"), "PLT",
                    listOf(Arg.Bare(patType), Arg.Bare(lit.id)), p.line) to emptyMap()
            }
            is FWildPattern ->
                emit(freshId("__f_pat"), "PWC", listOf(Arg.Bare(scrutineeTypeId())), p.line) to emptyMap()
            is FNamePattern -> {
                when (resolution) {
                    is SumResolution.Alias -> {
                        val case = resolution.info.cases?.get(p.name)
                        if (case != null) {
                            if (case.payload != Payload.None) {
                                abort(p.line, "case \"${p.name}\" of '${resolution.info.name}' carries a " +
                                    "payload — bind it: ${p.name}(x) or discard it: ${p.name}(_)")
                            }
                            return emit(freshId("__f_pat"), "PCN",
                                listOf(Arg.Bare(resolution.info.typeId), Arg.Str(p.name), Arg.Null),
                                p.line) to emptyMap()
                        }
                    }
                    is SumResolution.ResultSum -> {
                        if (p.name == "Ok" || p.name == "Err") {
                            abort(p.line, "${p.name} carries a payload — bind it: ${p.name}(x) or ${p.name}(_)")
                        }
                    }
                    is SumResolution.None -> {}
                }
                // Variable binder.
                val typeId = scrutineeTypeId()
                val pvrId = emit(freshId("__f_bind_${p.name}"), "PVR",
                    listOf(Arg.Bare(typeId), Arg.Str(p.name)), p.line)
                pvrId to mapOf(p.name to Local(nodeId = pvrId, isBinder = true, type = SType.Ref(typeId)))
            }
            is FCtorPattern -> {
                val (patTypeId, payloadId) = when (resolution) {
                    is SumResolution.Alias -> {
                        val case = resolution.info.cases?.get(p.ctor)
                            ?: abort(p.line, "'${resolution.info.name}' has no case \"${p.ctor}\"")
                        resolution.info.typeId to payloadTypeId(resolution.info, case)
                    }
                    is SumResolution.ResultSum -> {
                        val resId = sumTypeId!!
                        when (p.ctor) {
                            "Ok" -> resId to resolution.okId
                            "Err" -> resId to "errPayloadT"
                            else -> abort(p.line, "an attempt result matches Ok(...) or Err(...); " +
                                "found \"${p.ctor}\"")
                        }
                    }
                    is SumResolution.None -> abort(p.line, "constructor pattern \"${p.ctor}\" needs a " +
                        "union alias or an attempt-result scrutinee")
                }
                if (payloadId == null) {
                    if (p.binder != null || p.binderIsWild) {
                        abort(p.line, "case \"${p.ctor}\" carries no payload — write it bare: ${p.ctor}")
                    }
                    return emit(freshId("__f_pat"), "PCN",
                        listOf(Arg.Bare(patTypeId), Arg.Str(p.ctor), Arg.Null), p.line) to emptyMap()
                }
                if (p.binderIsWild) {
                    val wildId = emit(freshId("__f_pat"), "PWC", listOf(Arg.Bare(payloadId)), p.line)
                    return emit(freshId("__f_pat"), "PCN",
                        listOf(Arg.Bare(patTypeId), Arg.Str(p.ctor), Arg.Bare(wildId)), p.line) to emptyMap()
                }
                val binder = p.binder
                    ?: abort(p.line, "case \"${p.ctor}\" carries a payload — bind it: ${p.ctor}(x) or ${p.ctor}(_)")
                val pvrId = emit(freshId("__f_bind_$binder"), "PVR",
                    listOf(Arg.Bare(payloadId), Arg.Str(binder)), p.line)
                val pcnId = emit(freshId("__f_pat"), "PCN",
                    listOf(Arg.Bare(patTypeId), Arg.Str(p.ctor), Arg.Bare(pvrId)), p.line)
                pcnId to mapOf(binder to Local(nodeId = pvrId, isBinder = true, type = SType.Ref(payloadId)))
            }
        }
    }

    // ------------------------------------------------------------------
    // Arrows
    // ------------------------------------------------------------------

    private fun lowerArrow(e: FArrow, ctx: Ctx): Lowered {
        val prcIds = mutableListOf<String>()
        var locals = ctx.locals
        for (p in e.params) {
            val typeId = resolveTypeRef(p.type)
            val prcId = emit(freshId("__f_arr_${p.name}"), "PRC",
                listOf(Arg.Str(p.name), Arg.Bare(typeId)), p.line)
            prcIds += prcId
            locals = locals + (p.name to Local(
                nodeId = prcId, isBinder = true, type = SType.Ref(typeId),
                fnSurface = (p.type as? FFnType)?.let { emptyList<FParam>() to it.result },
            ))
        }
        val body = lowerExpr(e.body, ctx.copy(locals = locals))
        // Arrows are pure by construction: the effects row is explicitly
        // empty. An effectful arrow body is a verifier UncoveredEffects;
        // effectful code belongs in a named function with a uses clause.
        val lamId = emit(freshId("__f_arrow"), "LAM", listOf(
            Arg.Listing(prcIds.map { Arg.Bare(it) }),
            Arg.Bare(body.id),
            Arg.Listing(emptyList()),
        ), e.line)
        return Lowered(lamId, null)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun referencesName(block: FBlock, name: String): Boolean =
        block.consts.any { referencesName(it.value, name) } || referencesName(block.result, name)

    private fun referencesName(e: FExpr, name: String): Boolean = when (e) {
        is FIdent -> e.name == name
        is FCall -> referencesName(e.callee, name) || e.args.any { referencesName(it, name) }
        is FFieldGet -> referencesName(e.target, name)
        is FObjectLit -> e.fields.any { referencesName(it.value, name) }
        is FMatch -> referencesName(e.scrutinee, name) ||
            e.arms.any { referencesName(it.body, name) }
        is FAttempt -> referencesName(e.body, name)
        is FArrow -> referencesName(e.body, name)
        else -> false
    }
}
