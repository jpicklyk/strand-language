package org.strand.authoring

/**
 * Layer A grammar (Q-034 step 1, first slice).
 *
 * One node per line: `<author-id> <CODE> <arg>...`. Codes are fixed
 * three-character mnemonics; each code's positional argument schema is
 * documented here. The first slice covers Layer 1 node categories only —
 * literals, types, function/binding, NodeRef, type abstraction. Effects,
 * control flow, state machines, schemas, recursive types, and handlers
 * are deferred to follow-up slices.
 *
 * Document header (mandatory, first non-comment / non-blank line):
 *   `@v=1 root=<author-id>`
 *
 * Comments: lines whose first non-whitespace character is `#`.
 *
 * Lists: `[a b c]` (space-separated). Empty: `[]`.
 *
 * Strings: `"text"` with `\"`, `\\`, `\n`, `\t` escapes.
 *
 * Integers: `42`, `-3`, `0`.
 *
 * Floats: must contain a `.` (`3.14`, `-0.5`, `1.0`).
 *
 * Booleans: `true` or `false`.
 *
 * Null/absent: `_` (single underscore) — used for nullable fields.
 *
 * Bare references and keywords are alphanumeric+underscore identifiers.
 * Keywords like `Int`, `external`, `literal` are dispatched per code by
 * [DagJsonEmitter] — the parser does not pre-classify them.
 */
object LayerAGrammar {

    /**
     * Per-code field schema. The emitter uses this to drive arg-shape
     * validation and dag-json field assembly. Fields are positional.
     *
     * Some node categories share a `jsonType` and differ only in a
     * discriminator string field (e.g., Pattern's `kind`: literal /
     * variable / wildcard / constructor; EventStream's `streamKind`:
     * external / internal / output). For these, the [discriminator]
     * pair `(jsonField, value)` is emitted as a constant alongside the
     * positional fields. Each variant gets its own Layer A code so
     * arg-shape validation stays positional and per-code.
     */
    data class CodeSchema(
        /** The dag-json `type` value, e.g., "IntLit", "Application". */
        val jsonType: String,
        /** Required positional arguments. */
        val required: List<FieldSpec>,
        /** Optional positional arguments (appear at end of line if present). */
        val optional: List<FieldSpec> = emptyList(),
        /**
         * Optional fixed-string discriminator emitted as a constant JSON
         * field. Used for Pattern.kind (literal/variable/wildcard/
         * constructor) and EventStream.streamKind (external/internal/
         * output). Pair is (JSON field name, constant value).
         */
        val discriminator: Pair<String, String>? = null,
        /**
         * Slice 10 (Layer A density v4) — true if this code produces a
         * value, so it may legally appear in a `(CODE args...)` nested
         * expression at an expression-position reference slot. Type-only
         * codes (PRM, FNT, PRD, SUM, ...) and structural codes (PRC,
         * Pattern variants, MC, EFC, ...) are false.
         */
        val producesValue: Boolean = false,
        /**
         * True if this code produces a *type*, so it may legally appear
         * in a `(CODE args...)` nested expression at a TYPE-position
         * reference slot (one whose FieldSpec.acceptsType is true).
         * Type-only codes — PRM, PRD, SUM, FNT, TPM, FAL, RT, RS — are
         * marked here. The nested-expression validator distinguishes
         * value- vs type-position slots and rejects type codes in value
         * slots, so `APP add [1 (PRM Int)]` still surfaces as a
         * structured `ArgShapeMismatch` instead of falling through to
         * the verifier.
         */
        val producesType: Boolean = false,
    )

    /**
     * One positional field. [kind] is the expected [Arg] subclass; [jsonField]
     * is the dag-json field name the value flows into. [acceptsType]
     * marks slots that conceptually hold a type reference (e.g.,
     * PRF.fieldType, PRC.paramType, FNT.parameters/result) — those
     * accept nested `(CODE args...)` forms whose code is type-producing
     * in addition to the usual value-producing codes.
     */
    data class FieldSpec(
        val name: String,
        val kind: ArgKind,
        val jsonField: String,
        val acceptsType: Boolean = false,
    )

    enum class ArgKind {
        /** Bare token, expected to be an author-id reference. */
        REFERENCE,
        /** Bare token, expected to be a keyword (e.g., `Int`, `external`). */
        KEYWORD,
        /** Quoted string. */
        STRING,
        /** Signed integer. */
        INT,
        /** Float (must contain a dot). */
        FLOAT,
        /** `true` / `false`. */
        BOOL,
        /** `[...]` list whose elements are bare references. */
        LIST_REF,
        /** Reference or `_` (null). */
        NULLABLE_REF,
        /**
         * Slice 5 (Layer A density v2) — Lambda parameter list with
         * compact `name:typeRef` entries OR legacy bare PRC references.
         * The emitter synthesizes a PRC per entry whose text contains
         * `:`; legacy bare-ref entries pass through to the existing
         * PRC declaration. Used only by LAM's `parameters` slot today.
         */
        PARAM_LIST,
        /**
         * Slice 8 (Layer A density v2.5) — Product field-value list with
         * compact `name=ref` entries OR legacy bare PFV references. The
         * emitter synthesizes a PFV per entry whose text contains `=`;
         * legacy bare-ref entries pass through to the existing PFV
         * declaration. Used only by PV's `fields` slot today.
         *
         * The plan §Slice 8 proposed `{name=ref ...}` curly-brace syntax;
         * the implementation reuses `[...]` brackets (the parser already
         * tokenizes `=` inside bare tokens, so `name=ref` lexes as one
         * token without grammar changes). Documented in the proposal
         * Implementation note.
         */
        FIELD_LIST,
    }

    /**
     * Slice 1 (Layer A density v1) — synthetic node specification.
     *
     * A program that references a reserved name without locally declaring
     * it gets the synthetic node injected at emit time, transparently to
     * the verifier. Reserved names cover every primitive type, every
     * in-process builtin, and the canonical effect categories used by
     * state-machine corpus programs.
     *
     * Local declarations always win — a program that writes its own
     * `intT PRM Int` shadows the implicit one. Because the canonical
     * encoder content-addresses by structure (not author id), the local
     * and implicit forms hash identically.
     */
    data class ReservedNodeSpec(
        /** dag-json `type` field value (e.g., "PrimitiveType", "ForeignNode"). */
        val jsonType: String,
        /** Constant string-valued fields keyed by JSON field name. */
        val stringFields: Map<String, String> = emptyMap(),
        /** Single-reference fields keyed by JSON field name; values are reserved-name ids. */
        val refFields: Map<String, String> = emptyMap(),
        /** List-of-references fields keyed by JSON field name; values are reserved-name ids. */
        val refListFields: Map<String, List<String>> = emptyMap(),
    ) {
        /** Reserved-name ids this node depends on (must also be synthesized). */
        val dependencies: Set<String>
            get() = refFields.values.toSet() + refListFields.values.flatten().toSet()
    }

    /**
     * The reserved-name table. Iteration order is preserved so the
     * synthesized nodes appear in a deterministic order in the emitted
     * JSON document.
     *
     * Hash stability: each synthesized node's canonical bytes match a
     * hand-authored equivalent's bytes — the existing corpus programs
     * 16, 21, 41, etc. demonstrate the exact JSON shapes the reserved
     * table reproduces.
     */
    val reservedNodes: Map<String, ReservedNodeSpec> = linkedMapOf(
        // Primitive types
        "intT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "Int"),
        ),
        "floatT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "Float"),
        ),
        "stringT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "String"),
        ),
        "boolT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "Bool"),
        ),
        "unitT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "Unit"),
        ),
        "bytesT" to ReservedNodeSpec(
            jsonType = "PrimitiveType",
            stringFields = mapOf("kind" to "Bytes"),
        ),

        // FunctionType signatures for the common builtins.
        // Pure binops: (Int, Int) -> Int  /  (Int, Int) -> Bool  /  (Bool, Bool) -> Bool, etc.
        "addT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "subT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "mulT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "divT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "modT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "negT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "eqIntT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "ltT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "leT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "gtT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "geT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "notT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("boolT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "andT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("boolT", "boolT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "orT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("boolT", "boolT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "concatT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "eqStrT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
            refFields = mapOf("result" to "boolT"),
        ),
        // nowT is `() -> Int`. The effects live on the ForeignNode `now`,
        // not on the FunctionType — matches the canonical pattern in
        // corpus 16-builtin-time-now-under-capability.json.
        "nowT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to emptyList()),
            refFields = mapOf("result" to "intT"),
        ),

        // ForeignNode declarations. Pure builtins have no `effects` field.
        // `now` is the only effectful reserved foreign — it declares
        // [nowFx] to mirror corpus 16's canonical shape.
        "add" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Add"),
            refFields = mapOf("foreignType" to "addT"),
        ),
        "sub" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Sub"),
            refFields = mapOf("foreignType" to "subT"),
        ),
        "mul" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Mul"),
            refFields = mapOf("foreignType" to "mulT"),
        ),
        "div" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Div"),
            refFields = mapOf("foreignType" to "divT"),
        ),
        "mod" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Mod"),
            refFields = mapOf("foreignType" to "modT"),
        ),
        "neg" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Neg"),
            refFields = mapOf("foreignType" to "negT"),
        ),
        "eqInt" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Eq"),
            refFields = mapOf("foreignType" to "eqIntT"),
        ),
        "lt" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Lt"),
            refFields = mapOf("foreignType" to "ltT"),
        ),
        "le" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Le"),
            refFields = mapOf("foreignType" to "leT"),
        ),
        "gt" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Gt"),
            refFields = mapOf("foreignType" to "gtT"),
        ),
        "ge" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.Ge"),
            refFields = mapOf("foreignType" to "geT"),
        ),
        "not" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bool.Not"),
            refFields = mapOf("foreignType" to "notT"),
        ),
        "and" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bool.And"),
            refFields = mapOf("foreignType" to "andT"),
        ),
        "or" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bool.Or"),
            refFields = mapOf("foreignType" to "orT"),
        ),
        "concat" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Concat"),
            refFields = mapOf("foreignType" to "concatT"),
        ),
        "eqStr" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Eq"),
            refFields = mapOf("foreignType" to "eqStrT"),
        ),
        "now" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Time.Now"),
            refFields = mapOf("foreignType" to "nowT"),
            refListFields = mapOf("effects" to listOf("nowFx")),
        ),

        // Effect categories. Same mechanism, separate table per the plan.
        "receiveFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "StateMachine.Receive"),
        ),
        "sendFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "StateMachine.Send"),
        ),
        "spawnFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "StateMachine.Spawn"),
        ),
        "terminateFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "StateMachine.Terminate"),
        ),
        "nowFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Time.Now"),
        ),
        "writeFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Filesystem.Write"),
        ),
        "connectFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Network.Connect"),
        ),
        "cryptoFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Crypto.RandomBytes"),
        ),

        // Layer 4 step 2 backfill — effect categories for Fs.* /
        // Net.* / Process.* / Time.Sleep. Each Layer 4 step 2
        // builtin's ForeignNode entry below references one of these
        // (plus, for some, the existing writeFx / connectFx).
        // Naming: "netSendFx" / "netRecvFx" stay distinct from
        // StateMachine.Send/Receive (sendFx / receiveFx) to avoid
        // collision.
        "readFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Filesystem.Read"),
        ),
        "netSendFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Network.Send"),
        ),
        "netRecvFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Network.Receive"),
        ),
        "procWaitFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Process.Wait"),
        ),
        "sleepFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Time.Sleep"),
        ),
        // Stdlib expansion round 3 — diagnostic and host-environment
        // effect categories. E-032 / E-033 / E-034 in
        // design/effects-and-capabilities.md.
        "logFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "Log.Write"),
        ),
        "osReadFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "OS.Read"),
        ),
        "exitFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "System.Exit"),
        ),

        // Stdlib expansion round 2 — Math.* function types.
        // Sharing FNT shape across multiple names (e.g., absT and
        // signT are both (Int)->Int) follows the existing
        // negT/notT pattern: distinct names for readability even
        // when the underlying type structure is identical.
        "absT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "signT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "minT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "maxT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "mmodT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "floorT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "intT"),
        ),
        "ceilT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "intT"),
        ),
        "roundT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "intT"),
        ),
        "sqrtT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "powT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT", "floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "lnT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "expT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "sinT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "cosT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "tanT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "toFloatT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "floatT"),
        ),
        "toIntTruncT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "intT"),
        ),

        // Hash.* function types — all (Bytes) -> Bytes.
        "blake3T" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "sha256T" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "md5T" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),

        // Random.* function types. Random.* declares the existing
        // E-024 Crypto.RandomBytes effect category at the call site;
        // the ForeignNode entries below reference `cryptoFx`.
        "randIntT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "randFloatT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to emptyList()),
            refFields = mapOf("result" to "floatT"),
        ),
        "randBytesT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "bytesT"),
        ),

        // Bytes.FormatHex function type (monomorphic). The other
        // round-2 Bytes builtins (ParseHex, ParseBase64, ParseUtf8)
        // return Option<T> — pending a blessed Option<T> in the
        // prelude, those keep the explicit FNT + FRN form.
        "hexOfT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "stringT"),
        ),

        // Stdlib expansion round 2 — ForeignNode entries. Pure (no
        // effects) except Random.* which declares [cryptoFx]
        // (E-024 Crypto.RandomBytes).
        "abs" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Abs"),
            refFields = mapOf("foreignType" to "absT"),
        ),
        "sign" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Sign"),
            refFields = mapOf("foreignType" to "signT"),
        ),
        "min" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Min"),
            refFields = mapOf("foreignType" to "minT"),
        ),
        "max" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Max"),
            refFields = mapOf("foreignType" to "maxT"),
        ),
        // `mmod` (mathematical modulo, always non-negative for positive
        // divisors) avoids clashing with the existing `mod` reserved
        // name which targets `strand-builtin:Int.Mod` (JVM `%`,
        // sign-of-dividend). The two are different functions; the
        // distinct reserved names make the choice explicit.
        "mmod" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Mod"),
            refFields = mapOf("foreignType" to "mmodT"),
        ),
        "floor" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Floor"),
            refFields = mapOf("foreignType" to "floorT"),
        ),
        "ceil" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Ceil"),
            refFields = mapOf("foreignType" to "ceilT"),
        ),
        "round" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Round"),
            refFields = mapOf("foreignType" to "roundT"),
        ),
        "sqrt" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Sqrt"),
            refFields = mapOf("foreignType" to "sqrtT"),
        ),
        "pow" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Pow"),
            refFields = mapOf("foreignType" to "powT"),
        ),
        "ln" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Log"),
            refFields = mapOf("foreignType" to "lnT"),
        ),
        "exp" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Exp"),
            refFields = mapOf("foreignType" to "expT"),
        ),
        "sin" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Sin"),
            refFields = mapOf("foreignType" to "sinT"),
        ),
        "cos" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Cos"),
            refFields = mapOf("foreignType" to "cosT"),
        ),
        "tan" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Math.Tan"),
            refFields = mapOf("foreignType" to "tanT"),
        ),
        "toFloat" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Float.FromInt"),
            refFields = mapOf("foreignType" to "toFloatT"),
        ),
        "toIntTrunc" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Int.FromFloatTrunc"),
            refFields = mapOf("foreignType" to "toIntTruncT"),
        ),
        "blake3" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Hash.Blake3"),
            refFields = mapOf("foreignType" to "blake3T"),
        ),
        "sha256" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Hash.Sha256"),
            refFields = mapOf("foreignType" to "sha256T"),
        ),
        "md5" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Hash.Md5"),
            refFields = mapOf("foreignType" to "md5T"),
        ),
        "randInt" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Random.Int"),
            refFields = mapOf("foreignType" to "randIntT"),
            refListFields = mapOf("effects" to listOf("cryptoFx")),
        ),
        "randFloat" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Random.Float"),
            refFields = mapOf("foreignType" to "randFloatT"),
            refListFields = mapOf("effects" to listOf("cryptoFx")),
        ),
        "randBytes" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Random.Bytes"),
            refFields = mapOf("foreignType" to "randBytesT"),
            refListFields = mapOf("effects" to listOf("cryptoFx")),
        ),
        "hexOf" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.FormatHex"),
            refFields = mapOf("foreignType" to "hexOfT"),
        ),

        // ===== Layer 4 step 2 backfill (2026-05-26) =====
        // Prelude entries for the round-1 monomorphic IO + stdlib
        // builtins that originally shipped without reserved names.
        // The "When adding a new builtin" checklist now requires
        // prelude entries with every builtin slice; this block fills
        // the historical gap. Skipped: Fs.List (returns List<String>),
        // Process.Spawn (takes List<String>), Process.EnvVar (returns
        // Option<String>), Json.Parse / Json.Stringify / Markdown.Parse
        // (typed against a specific JsonValue / MarkdownDocument), and
        // String.Split / String.Join / String.ParseInt / String.ParseFloat
        // / Bytes.ParseUtf8 / Bytes.ParseHex / Bytes.ParseBase64
        // (polymorphic or Option-returning). Those keep the explicit
        // FNT + FRN form at the use site per the documented exceptions.

        // Function types — filesystem
        "fsReadT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "fsWriteT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "bytesT")),
            refFields = mapOf("result" to "intT"),
        ),
        "fsAppendT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "bytesT")),
            refFields = mapOf("result" to "intT"),
        ),
        "fsExistsT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "fsDeleteT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "boolT"),
        ),

        // Function types — network sockets. Socket handles are
        // represented as Int at the Strand surface (Layer 4 step 2
        // design call: opaque-handle representation, runtime
        // dispatch on Value.Resource.kind).
        "netConnectT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "netSendT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "bytesT")),
            refFields = mapOf("result" to "intT"),
        ),
        "netRecvT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT", "intT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "netCloseT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "unitT"),
        ),

        // Http.Request returns a fixed ProductType {status: Int, body: Bytes}.
        // Encoding the product type in the prelude requires three
        // pieces: the two ProductTypeField entries + the ProductType
        // itself. Names prefixed `http` for clarity.
        "httpRespStatusField" to ReservedNodeSpec(
            jsonType = "ProductTypeField",
            stringFields = mapOf("name" to "status"),
            refFields = mapOf("fieldType" to "intT"),
        ),
        "httpRespBodyField" to ReservedNodeSpec(
            jsonType = "ProductTypeField",
            stringFields = mapOf("name" to "body"),
            refFields = mapOf("fieldType" to "bytesT"),
        ),
        "httpRespT" to ReservedNodeSpec(
            jsonType = "ProductType",
            refListFields = mapOf("fields" to listOf("httpRespStatusField", "httpRespBodyField")),
        ),
        "httpReqT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT", "bytesT")),
            refFields = mapOf("result" to "httpRespT"),
        ),

        // Function types — process + time
        "procWaitT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "intT"),
        ),
        "sleepT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "unitT"),
        ),

        // Function types — String stdlib (monomorphic only). Several
        // FNTs share shape — kept distinct per the addT/subT precedent.
        "strLenT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "intT"),
        ),
        "subStrT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "intT", "intT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "indexOfT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
            refFields = mapOf("result" to "intT"),
        ),
        "containsT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
            refFields = mapOf("result" to "boolT"),
        ),
        "replaceT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT", "stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "upperT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "lowerT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "trimT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "intToStrT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "floatToStrT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("floatT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "boolToStrT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("boolT")),
            refFields = mapOf("result" to "stringT"),
        ),

        // Function types — Bytes stdlib (monomorphic only).
        "bytesLenT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "intT"),
        ),
        "bytesSliceT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT", "intT", "intT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "bytesCatT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT", "bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "fromUtf8T" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "b64OfT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "stringT"),
        ),

        // ForeignNode entries — filesystem
        "fsRead" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Fs.Read"),
            refFields = mapOf("foreignType" to "fsReadT"),
            refListFields = mapOf("effects" to listOf("readFx")),
        ),
        "fsWrite" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Fs.Write"),
            refFields = mapOf("foreignType" to "fsWriteT"),
            refListFields = mapOf("effects" to listOf("writeFx")),
        ),
        "fsAppend" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Fs.Append"),
            refFields = mapOf("foreignType" to "fsAppendT"),
            refListFields = mapOf("effects" to listOf("writeFx")),
        ),
        "fsExists" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Fs.Exists"),
            refFields = mapOf("foreignType" to "fsExistsT"),
            refListFields = mapOf("effects" to listOf("readFx")),
        ),
        "fsDelete" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Fs.Delete"),
            refFields = mapOf("foreignType" to "fsDeleteT"),
            refListFields = mapOf("effects" to listOf("writeFx")),
        ),

        // ForeignNode entries — network sockets + HTTP
        "netConnect" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Net.Connect"),
            refFields = mapOf("foreignType" to "netConnectT"),
            refListFields = mapOf("effects" to listOf("connectFx")),
        ),
        "netSend" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Net.Send"),
            refFields = mapOf("foreignType" to "netSendT"),
            refListFields = mapOf("effects" to listOf("netSendFx")),
        ),
        "netRecv" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Net.Receive"),
            refFields = mapOf("foreignType" to "netRecvT"),
            refListFields = mapOf("effects" to listOf("netRecvFx")),
        ),
        "netClose" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Net.Close"),
            refFields = mapOf("foreignType" to "netCloseT"),
        ),
        // Http.Request declares all three Network.* effects since the
        // underlying implementation establishes a connection, sends
        // the request, and reads the response.
        "httpReq" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Http.Request"),
            refFields = mapOf("foreignType" to "httpReqT"),
            refListFields = mapOf("effects" to listOf("connectFx", "netSendFx", "netRecvFx")),
        ),

        // ForeignNode entries — process + time
        "procWait" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Process.Wait"),
            refFields = mapOf("foreignType" to "procWaitT"),
            refListFields = mapOf("effects" to listOf("procWaitFx")),
        ),
        "sleep" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Time.Sleep"),
            refFields = mapOf("foreignType" to "sleepT"),
            refListFields = mapOf("effects" to listOf("sleepFx")),
        ),

        // ForeignNode entries — String stdlib. All pure.
        "strLen" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Length"),
            refFields = mapOf("foreignType" to "strLenT"),
        ),
        "subStr" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Substring"),
            refFields = mapOf("foreignType" to "subStrT"),
        ),
        "indexOf" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.IndexOf"),
            refFields = mapOf("foreignType" to "indexOfT"),
        ),
        "contains" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Contains"),
            refFields = mapOf("foreignType" to "containsT"),
        ),
        "replace" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Replace"),
            refFields = mapOf("foreignType" to "replaceT"),
        ),
        "upper" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.ToUpper"),
            refFields = mapOf("foreignType" to "upperT"),
        ),
        "lower" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.ToLower"),
            refFields = mapOf("foreignType" to "lowerT"),
        ),
        "trim" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.Trim"),
            refFields = mapOf("foreignType" to "trimT"),
        ),
        "intToStr" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.FromInt"),
            refFields = mapOf("foreignType" to "intToStrT"),
        ),
        "floatToStr" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.FromFloat"),
            refFields = mapOf("foreignType" to "floatToStrT"),
        ),
        "boolToStr" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:String.FromBool"),
            refFields = mapOf("foreignType" to "boolToStrT"),
        ),

        // ForeignNode entries — Bytes stdlib. All pure.
        "bytesLen" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.Length"),
            refFields = mapOf("foreignType" to "bytesLenT"),
        ),
        "bytesSlice" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.Slice"),
            refFields = mapOf("foreignType" to "bytesSliceT"),
        ),
        "bytesCat" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.Concat"),
            refFields = mapOf("foreignType" to "bytesCatT"),
        ),
        "fromUtf8" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.FromUtf8"),
            refFields = mapOf("foreignType" to "fromUtf8T"),
        ),
        "b64Of" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Bytes.FormatBase64"),
            refFields = mapOf("foreignType" to "b64OfT"),
        ),

        // ===== Stdlib expansion round 3 (2026-05-26) =====
        // Log.* / OS.* / System.Exit. All monomorphic; each declares
        // a new effect category (logFx / osReadFx / exitFx) registered
        // above. Function-type names follow the existing per-builtin
        // convention (logT/exitT all share `(String) -> Unit` shape,
        // hostT/platT/cwdT all share `() -> String`, but kept distinct
        // for readability per the addT/subT/mulT precedent).

        "logT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT")),
            refFields = mapOf("result" to "unitT"),
        ),
        "hostT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to emptyList()),
            refFields = mapOf("result" to "stringT"),
        ),
        "platT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to emptyList()),
            refFields = mapOf("result" to "stringT"),
        ),
        "cwdT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to emptyList()),
            refFields = mapOf("result" to "stringT"),
        ),
        "exitT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("intT")),
            refFields = mapOf("result" to "unitT"),
        ),

        // ForeignNode entries — Log.*. All three target a distinct
        // strand-builtin entry but declare the same logFx effect
        // category.
        "logInfo" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Log.Info"),
            refFields = mapOf("foreignType" to "logT"),
            refListFields = mapOf("effects" to listOf("logFx")),
        ),
        "logWarn" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Log.Warn"),
            refFields = mapOf("foreignType" to "logT"),
            refListFields = mapOf("effects" to listOf("logFx")),
        ),
        "logError" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Log.Error"),
            refFields = mapOf("foreignType" to "logT"),
            refListFields = mapOf("effects" to listOf("logFx")),
        ),

        // ForeignNode entries — OS.*. All three declare osReadFx.
        "hostname" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:OS.Hostname"),
            refFields = mapOf("foreignType" to "hostT"),
            refListFields = mapOf("effects" to listOf("osReadFx")),
        ),
        "platform" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:OS.Platform"),
            refFields = mapOf("foreignType" to "platT"),
            refListFields = mapOf("effects" to listOf("osReadFx")),
        ),
        "cwd" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:OS.Cwd"),
            refFields = mapOf("foreignType" to "cwdT"),
            refListFields = mapOf("effects" to listOf("osReadFx")),
        ),

        // ForeignNode entry — System.Exit. Declares exitFx (E-034).
        "exit" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:System.Exit"),
            refFields = mapOf("foreignType" to "exitT"),
            refListFields = mapOf("effects" to listOf("exitFx")),
        ),

        // Stdlib expansion round 3 phase 2 — Regex.Replace. Only the
        // monomorphic Regex builtin gets a prelude entry; Regex.Match
        // (returns Option<String>), Regex.FindAll, and Regex.Split
        // (both return List<String>) stay explicit at the use site
        // per the documented Option-returning and polymorphic-list
        // exceptions. Name `reReplace` to avoid clash with the
        // existing `replace` (String.Replace).
        "reReplaceT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("stringT", "stringT", "stringT")),
            refFields = mapOf("result" to "stringT"),
        ),
        "reReplace" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Regex.Replace"),
            refFields = mapOf("foreignType" to "reReplaceT"),
        ),

        // ===== Q-037 Phase 1 — agent-native LLM ForeignNodes =====
        // Per-provider Generate + Embed under the operation-shaped
        // E-035 LLM.Generate / E-036 LLM.Embed effect categories.
        //
        // The Strand-side request / result types are agent-built
        // ProductV / SumV towers (proposal § 3.3) — they don't fit
        // any monomorphic prelude FunctionType cleanly. Following the
        // Map.* surface-type convention, the prelude uses `bytesT` as
        // an opaque placeholder for the request and result types; the
        // runtime checks shape at dispatch and produces a structured
        // error if the value isn't a ProductV with the expected fields.
        // Agents that want a precise FunctionType emit explicit FNT +
        // FRN at the use site. The prelude exists primarily so the
        // EffectCategory entries (`llmGenerateFx`, `llmEmbedFx`) and
        // the ForeignNode targets are easy to reach by short name.

        "llmGenerateFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "LLM.Generate"),
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
        ),
        "llmEmbedFx" to ReservedNodeSpec(
            jsonType = "EffectCategory",
            stringFields = mapOf("categoryName" to "LLM.Embed"),
            refListFields = mapOf("parameters" to listOf("stringT", "stringT")),
        ),

        // Function types — opaque (request: Bytes) -> Bytes placeholder.
        // Agents construct the real GenerateRequest / EmbedRequest /
        // GenerateResult products at the call site and either use a
        // local FNT to type the call precisely or pass the prelude
        // shape through the runtime's structural dispatch.
        "llmGenerateT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),
        "llmEmbedT" to ReservedNodeSpec(
            jsonType = "FunctionType",
            refListFields = mapOf("parameters" to listOf("bytesT")),
            refFields = mapOf("result" to "bytesT"),
        ),

        // ForeignNode entries — six per-provider bindings. Each pins
        // its `provider` refinement parameter via the EffectCategory
        // it declares; the verifier sees provider identity in the
        // effect closure even though the category is operation-shaped.
        "anthropicGenerate" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Anthropic.Messages.Create"),
            refFields = mapOf("foreignType" to "llmGenerateT"),
            refListFields = mapOf("effects" to listOf("llmGenerateFx")),
        ),
        "anthropicEmbed" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Anthropic.Embeddings.Create"),
            refFields = mapOf("foreignType" to "llmEmbedT"),
            refListFields = mapOf("effects" to listOf("llmEmbedFx")),
        ),
        "openaiGenerate" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:OpenAI.Chat.Completions"),
            refFields = mapOf("foreignType" to "llmGenerateT"),
            refListFields = mapOf("effects" to listOf("llmGenerateFx")),
        ),
        "openaiEmbed" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:OpenAI.Embeddings.Create"),
            refFields = mapOf("foreignType" to "llmEmbedT"),
            refListFields = mapOf("effects" to listOf("llmEmbedFx")),
        ),
        "geminiGenerate" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Gemini.GenerateContent"),
            refFields = mapOf("foreignType" to "llmGenerateT"),
            refListFields = mapOf("effects" to listOf("llmGenerateFx")),
        ),
        "geminiEmbed" to ReservedNodeSpec(
            jsonType = "ForeignNode",
            stringFields = mapOf("target" to "strand-builtin:Gemini.EmbedContent"),
            refFields = mapOf("foreignType" to "llmEmbedT"),
            refListFields = mapOf("effects" to listOf("llmEmbedFx")),
        ),
    )

    /**
     * All Layer A codes recognized in this first slice. Codes not listed
     * here surface as [AuthoringError.UnknownCode] at emit time.
     */
    val codes: Map<String, CodeSchema> = mapOf(
        // Literals
        "ILT" to CodeSchema(
            jsonType = "IntLit",
            required = listOf(FieldSpec("value", ArgKind.INT, "value")),
            producesValue = true,
        ),
        "FLT" to CodeSchema(
            jsonType = "FloatLit",
            required = listOf(FieldSpec("value", ArgKind.FLOAT, "value")),
            producesValue = true,
        ),
        "STR" to CodeSchema(
            jsonType = "StringLit",
            required = listOf(FieldSpec("value", ArgKind.STRING, "value")),
            producesValue = true,
        ),
        "BLT" to CodeSchema(
            jsonType = "BoolLit",
            required = listOf(FieldSpec("value", ArgKind.BOOL, "value")),
            producesValue = true,
        ),
        "ULT" to CodeSchema(
            jsonType = "UnitLit",
            required = emptyList(),
            producesValue = true,
        ),
        "BYT" to CodeSchema(
            jsonType = "BytesLit",
            // Base64-encoded payload as a string. Matches the JSON ingest's
            // BytesLit convention (a single base64 string field).
            required = listOf(FieldSpec("value", ArgKind.STRING, "value")),
            producesValue = true,
        ),

        // Types
        "PRM" to CodeSchema(
            jsonType = "PrimitiveType",
            required = listOf(FieldSpec("kind", ArgKind.KEYWORD, "kind")),
            producesType = true,
        ),
        "PRD" to CodeSchema(
            jsonType = "ProductType",
            required = listOf(FieldSpec("fields", ArgKind.LIST_REF, "fields")),
            producesType = true,
        ),
        "PRF" to CodeSchema(
            jsonType = "ProductTypeField",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("fieldType", ArgKind.REFERENCE, "fieldType", acceptsType = true),
            ),
            // PRF is structural (not directly a type), but it's used as a
            // type component; leave producesType=false so nesting bare PRF
            // is still rejected — the agent should nest the field type
            // (PRM/RS/...) rather than the field declaration itself.
        ),
        "SUM" to CodeSchema(
            jsonType = "SumType",
            required = listOf(FieldSpec("cases", ArgKind.LIST_REF, "cases")),
            producesType = true,
        ),
        "SCS" to CodeSchema(
            jsonType = "SumTypeCase",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("caseType", ArgKind.NULLABLE_REF, "caseType", acceptsType = true),
            ),
        ),
        "FNT" to CodeSchema(
            jsonType = "FunctionType",
            required = listOf(
                FieldSpec("parameters", ArgKind.LIST_REF, "parameters", acceptsType = true),
                FieldSpec("result", ArgKind.REFERENCE, "result", acceptsType = true),
            ),
            optional = listOf(FieldSpec("effects", ArgKind.LIST_REF, "effects")),
            producesType = true,
        ),
        "TPM" to CodeSchema(
            jsonType = "TypeParameter",
            required = listOf(FieldSpec("name", ArgKind.STRING, "name")),
            // bound is rarely used and goes here when present
            optional = listOf(FieldSpec("bound", ArgKind.REFERENCE, "bound", acceptsType = true)),
            producesType = true,
        ),

        // Functions and binding.
        // LAM's `parameters` slot accepts PARAM_LIST entries — either bare
        // refs to existing PRCs (legacy form) or `name:typeRef` compact
        // form. See Slice 5 (Layer A density v2) in LayerAGrammar.ArgKind.
        "LAM" to CodeSchema(
            jsonType = "Lambda",
            required = listOf(
                FieldSpec("parameters", ArgKind.PARAM_LIST, "parameters"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            optional = listOf(FieldSpec("effects", ArgKind.LIST_REF, "effects")),
            producesValue = true,
        ),
        "PRC" to CodeSchema(
            jsonType = "ParameterDecl",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
            ),
            // Layer C case (1) — Lambda.paramType inference. When absent,
            // Elaborator fills the slot from:
            //   * the surrounding Application call-site context (v1).
            //   * Layer A density v4 extensions: the enclosing Fixpoint's
            //     recursionType (for the recursion-slot PRC); Match
            //     scrutinee context; ProductFieldValue context; reserved-
            //     name builtin callee parameter types; nested Application
            //     chains via [resolveCalleeChain].
            // Without elaboration, an absent paramType surfaces as a
            // JsonIngest error (the canonical JSON schema still requires
            // the field).
            optional = listOf(
                FieldSpec("paramType", ArgKind.REFERENCE, "paramType", acceptsType = true),
            ),
        ),
        "APP" to CodeSchema(
            jsonType = "Application",
            required = listOf(
                FieldSpec("function", ArgKind.REFERENCE, "function"),
                FieldSpec("arguments", ArgKind.LIST_REF, "arguments"),
            ),
            optional = listOf(
                FieldSpec("typeArguments", ArgKind.LIST_REF, "typeArguments"),
                FieldSpec("effectInstances", ArgKind.LIST_REF, "effectInstances"),
            ),
            producesValue = true,
        ),
        "LET" to CodeSchema(
            jsonType = "Let",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("value", ArgKind.REFERENCE, "value"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesValue = true,
        ),
        "VAR" to CodeSchema(
            jsonType = "VarRef",
            required = listOf(FieldSpec("binder", ArgKind.REFERENCE, "binder")),
            producesValue = true,
        ),

        // References
        "NRF" to CodeSchema(
            jsonType = "NodeRef",
            required = listOf(FieldSpec("target", ArgKind.REFERENCE, "target")),
            producesValue = true,
        ),

        // Type abstraction (N-034) and ForallType (N-035) — explicit System F.
        "TAB" to CodeSchema(
            jsonType = "TypeAbstraction",
            required = listOf(
                FieldSpec("typeParameters", ArgKind.LIST_REF, "typeParameters"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesValue = true,
        ),
        "FAL" to CodeSchema(
            jsonType = "ForallType",
            required = listOf(
                FieldSpec("typeParameters", ArgKind.LIST_REF, "typeParameters"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesType = true,
        ),

        // Effects and capabilities (Layer 3)
        "EFC" to CodeSchema(
            jsonType = "EffectCategory",
            required = listOf(FieldSpec("categoryName", ArgKind.STRING, "categoryName")),
            optional = listOf(FieldSpec("parameters", ArgKind.LIST_REF, "parameters")),
        ),
        "EFD" to CodeSchema(
            jsonType = "EffectDecl",
            required = listOf(
                FieldSpec("effectType", ArgKind.REFERENCE, "effectType"),
                FieldSpec("parameters", ArgKind.LIST_REF, "parameters"),
            ),
        ),
        "CAP" to CodeSchema(
            jsonType = "CapabilityScope",
            required = listOf(
                FieldSpec("capabilities", ArgKind.LIST_REF, "capabilities"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesValue = true,
        ),

        // Foreign function interface (Layer 4)
        "FN" to CodeSchema(
            jsonType = "ForeignNode",
            required = listOf(
                FieldSpec("target", ArgKind.STRING, "target"),
                FieldSpec("foreignType", ArgKind.REFERENCE, "foreignType", acceptsType = true),
            ),
            optional = listOf(FieldSpec("effects", ArgKind.LIST_REF, "effects")),
            producesValue = true,
        ),

        // Layer A density v3 (Slice 9) — WHEN/constructor-pattern sugar.
        // Expands at emit time to a Match plus per case {PCN [+ PVR] + MC}
        // tower. Internal author ids are `__when<n>_*`.
        //
        // Signature: `WHEN scrutinee sumType "<cases>"` where <cases> is
        // a STRING (parsed at emit time) of the form:
        //     CaseName -> body | CaseName(binder) -> body | ...
        // Cases are separated by ` | `. Each case body is a single token —
        // an identifier (a declared node, the case's own binder, or any
        // PRC binder in scope) or an inline literal (Int/Float/Bool/Str).
        //
        // Plan deviation worth recording: §Slice 9 sketched `[CaseName(b)
        // -> body | ...]` inside brackets with `|`, `->`, `(`, `)` as
        // separator tokens. The implementation uses a STRING arg instead,
        // because the existing tokenizer doesn't recognize `|` / `->` /
        // parens as tokens. The user-facing trade-off: the case-list
        // content sits inside `"..."` quotes; the dag-json output is
        // identical to the explicit form. ConstraintGrammar overapproximates
        // by allowing any string content; tighter LLM-side constraint
        // requires either a separate WHEN sub-grammar or the bracketed
        // form's parser work.
        "WHEN" to CodeSchema(
            jsonType = "Match",
            required = listOf(
                FieldSpec("scrutinee", ArgKind.REFERENCE, "scrutinee"),
                FieldSpec("sumType", ArgKind.REFERENCE, "sumType"),
                FieldSpec("cases", ArgKind.STRING, "cases"),
            ),
            producesValue = true,
        ),

        // Layer A density v1.5 (Slice 4) — IF/Match-on-Bool sugar.
        // Expands at emit time to 7 dag-json nodes (2 BoolLit, 2 Pattern
        // literal, 2 MatchCase, 1 Match). The Match takes the user's
        // author id; the other 6 nodes get `__if<n>_*` internal ids in
        // [DagJsonEmitter]. The jsonType "Match" here is informational —
        // the emitter dispatches IF to a dedicated expansion path that
        // synthesizes the wrapper tower, so this schema's `required`
        // list drives only arg-shape validation. The "boolT" referenced
        // by the synthesized Patterns resolves through Slice 1's
        // implicit prelude unless the user declares their own.
        "IF" to CodeSchema(
            jsonType = "Match",
            required = listOf(
                FieldSpec("scrutinee", ArgKind.REFERENCE, "scrutinee"),
                FieldSpec("then", ArgKind.REFERENCE, "then"),
                FieldSpec("else", ArgKind.REFERENCE, "else"),
            ),
            producesValue = true,
        ),

        // Control flow (Layer 5 steps 1, 2)
        "MAT" to CodeSchema(
            jsonType = "Match",
            required = listOf(
                FieldSpec("scrutinee", ArgKind.REFERENCE, "scrutinee"),
                FieldSpec("cases", ArgKind.LIST_REF, "cases"),
            ),
            producesValue = true,
        ),
        "MC" to CodeSchema(
            jsonType = "MatchCase",
            required = listOf(
                FieldSpec("pattern", ArgKind.REFERENCE, "pattern"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),

        // Pattern variants — each gets its own Layer A code; the JSON's
        // `kind` discriminator is supplied via [CodeSchema.discriminator].
        "PLT" to CodeSchema(
            jsonType = "Pattern",
            required = listOf(
                FieldSpec("patternType", ArgKind.REFERENCE, "patternType"),
                FieldSpec("literal", ArgKind.REFERENCE, "literal"),
            ),
            discriminator = "kind" to "literal",
        ),
        "PVR" to CodeSchema(
            jsonType = "Pattern",
            required = listOf(
                FieldSpec("patternType", ArgKind.REFERENCE, "patternType"),
                FieldSpec("name", ArgKind.STRING, "name"),
            ),
            discriminator = "kind" to "variable",
        ),
        "PWC" to CodeSchema(
            jsonType = "Pattern",
            required = listOf(
                FieldSpec("patternType", ArgKind.REFERENCE, "patternType"),
            ),
            discriminator = "kind" to "wildcard",
        ),
        "PCN" to CodeSchema(
            jsonType = "Pattern",
            required = listOf(
                FieldSpec("patternType", ArgKind.REFERENCE, "patternType"),
                FieldSpec("caseName", ArgKind.STRING, "caseName"),
            ),
            optional = listOf(FieldSpec("payloadPattern", ArgKind.NULLABLE_REF, "payloadPattern")),
            discriminator = "kind" to "constructor",
        ),

        // Fixpoint (Layer 5 step 2)
        "FIX" to CodeSchema(
            jsonType = "Fixpoint",
            required = listOf(
                FieldSpec("recursionType", ArgKind.REFERENCE, "recursionType", acceptsType = true),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesValue = true,
        ),

        // Composite values (Layer 5 steps 3a, 3b).
        // PV's `fields` slot accepts FIELD_LIST entries — either bare refs
        // to existing PFVs (legacy form) or `name=ref` compact form. See
        // Slice 8 (Layer A density v2.5) in [LayerAGrammar.ArgKind].
        "PV" to CodeSchema(
            jsonType = "ProductValue",
            required = listOf(
                FieldSpec("ofType", ArgKind.REFERENCE, "ofType", acceptsType = true),
                FieldSpec("fields", ArgKind.FIELD_LIST, "fields"),
            ),
            producesValue = true,
        ),
        "PFV" to CodeSchema(
            jsonType = "ProductFieldValue",
            required = listOf(
                FieldSpec("fieldName", ArgKind.STRING, "fieldName"),
                FieldSpec("value", ArgKind.REFERENCE, "value"),
            ),
        ),
        "PFG" to CodeSchema(
            jsonType = "ProductFieldGet",
            required = listOf(
                FieldSpec("target", ArgKind.REFERENCE, "target"),
                FieldSpec("fieldName", ArgKind.STRING, "fieldName"),
            ),
            producesValue = true,
        ),
        "SV" to CodeSchema(
            jsonType = "SumValue",
            required = listOf(
                FieldSpec("ofType", ArgKind.REFERENCE, "ofType", acceptsType = true),
                FieldSpec("caseName", ArgKind.STRING, "caseName"),
                FieldSpec("payload", ArgKind.NULLABLE_REF, "payload"),
            ),
            producesValue = true,
        ),

        // Recursive types
        "RT" to CodeSchema(
            jsonType = "RecursiveType",
            required = listOf(FieldSpec("body", ArgKind.REFERENCE, "body")),
            producesType = true,
        ),
        "RS" to CodeSchema(
            jsonType = "RecursiveSelf",
            required = emptyList(),
            // RS is type-producing but CANNOT be safely nested via the
            // `(CODE args...)` form: the synthesized standalone RS node
            // loses its lexical RT binder context (the canonical encoder
            // walks RT's body in source position to compute the de Bruijn
            // depth; a RS reachable only via cross-reference reads as
            // unbound). Authors must declare RS as a standalone node and
            // reference it by id from inside the lexical RT body.
            producesType = false,
        ),

        // Handler (Layer 3 step 3)
        "H" to CodeSchema(
            jsonType = "Handler",
            required = listOf(
                FieldSpec("intercept", ArgKind.REFERENCE, "intercept"),
                FieldSpec("handle", ArgKind.REFERENCE, "handle"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
            producesValue = true,
        ),

        // State machines (Layer 6)
        "SM" to CodeSchema(
            jsonType = "StateMachine",
            required = listOf(
                FieldSpec("transitionFn", ArgKind.REFERENCE, "transitionFn"),
                FieldSpec("initialState", ArgKind.REFERENCE, "initialState"),
                FieldSpec("inputStreams", ArgKind.LIST_REF, "inputStreams"),
            ),
            optional = listOf(
                FieldSpec("outputStreams", ArgKind.LIST_REF, "outputStreams"),
                FieldSpec("effects", ArgKind.LIST_REF, "effects"),
            ),
        ),

        // EventStream variants — discriminator on streamKind. The three
        // optional content fields (bufferSize, overflowPolicy, consumerMode)
        // were added across Layer 6 step 3 slices 3.1 and 3.6; they take
        // the same positional shape in each EventStream variant.
        //
        // overflowPolicy and consumerMode use ArgKind.KEYWORD because they
        // emit as bare strings ("DropOldest", "Broadcast"). The Sample(n)
        // object form is not expressible in Layer A in this slice — corpus
        // programs that need it must use canonical JSON.
        "ESE" to CodeSchema(
            jsonType = "EventStream",
            required = listOf(FieldSpec("eventType", ArgKind.REFERENCE, "eventType")),
            optional = listOf(
                FieldSpec("bufferSize", ArgKind.INT, "bufferSize"),
                FieldSpec("overflowPolicy", ArgKind.KEYWORD, "overflowPolicy"),
                FieldSpec("consumerMode", ArgKind.KEYWORD, "consumerMode"),
            ),
            discriminator = "streamKind" to "external",
        ),
        "ESI" to CodeSchema(
            jsonType = "EventStream",
            required = listOf(FieldSpec("eventType", ArgKind.REFERENCE, "eventType")),
            optional = listOf(
                FieldSpec("bufferSize", ArgKind.INT, "bufferSize"),
                FieldSpec("overflowPolicy", ArgKind.KEYWORD, "overflowPolicy"),
                FieldSpec("consumerMode", ArgKind.KEYWORD, "consumerMode"),
            ),
            discriminator = "streamKind" to "internal",
        ),
        "ESO" to CodeSchema(
            jsonType = "EventStream",
            required = listOf(FieldSpec("eventType", ArgKind.REFERENCE, "eventType")),
            optional = listOf(
                FieldSpec("bufferSize", ArgKind.INT, "bufferSize"),
                FieldSpec("overflowPolicy", ArgKind.KEYWORD, "overflowPolicy"),
                FieldSpec("consumerMode", ArgKind.KEYWORD, "consumerMode"),
            ),
            discriminator = "streamKind" to "output",
        ),
        "TR" to CodeSchema(
            jsonType = "Transition",
            required = listOf(
                FieldSpec("guard", ArgKind.NULLABLE_REF, "guard"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),

        // Schema + Invariant (Layer 7 step 1)
        "SCH" to CodeSchema(
            jsonType = "Schema",
            required = listOf(
                FieldSpec("schemaName", ArgKind.STRING, "schemaName"),
                FieldSpec("valueType", ArgKind.REFERENCE, "valueType", acceptsType = true),
                FieldSpec("invariants", ArgKind.LIST_REF, "invariants"),
            ),
        ),
        "INV" to CodeSchema(
            jsonType = "Invariant",
            required = listOf(
                FieldSpec("invariantName", ArgKind.STRING, "invariantName"),
                FieldSpec("targetSchema", ArgKind.REFERENCE, "targetSchema"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),
    )
}
