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
         * True for sugar codes that expand at emit time to multiple
         * canonical nodes (Slice 4 IF, Slice 9 WHEN). The reverse
         * translator [LayerATranslator] skips these — there's no
         * unambiguous reverse mapping (not every Match is an IF; not
         * every Match-on-sum is a WHEN) and the canonical-form-shaped
         * code (MAT) is the right pick for the no-discriminator fallback.
         */
        val sugarOnly: Boolean = false,
    )

    /**
     * One positional field. [kind] is the expected [Arg] subclass; [jsonField]
     * is the dag-json field name the value flows into; [emit] is the
     * arg → JsonElement transformation.
     */
    data class FieldSpec(
        val name: String,
        val kind: ArgKind,
        val jsonField: String,
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
        ),
        "FLT" to CodeSchema(
            jsonType = "FloatLit",
            required = listOf(FieldSpec("value", ArgKind.FLOAT, "value")),
        ),
        "STR" to CodeSchema(
            jsonType = "StringLit",
            required = listOf(FieldSpec("value", ArgKind.STRING, "value")),
        ),
        "BLT" to CodeSchema(
            jsonType = "BoolLit",
            required = listOf(FieldSpec("value", ArgKind.BOOL, "value")),
        ),
        "ULT" to CodeSchema(
            jsonType = "UnitLit",
            required = emptyList(),
        ),
        "BYT" to CodeSchema(
            jsonType = "BytesLit",
            // Base64-encoded payload as a string. Matches the JSON ingest's
            // BytesLit convention (a single base64 string field).
            required = listOf(FieldSpec("value", ArgKind.STRING, "value")),
        ),

        // Types
        "PRM" to CodeSchema(
            jsonType = "PrimitiveType",
            required = listOf(FieldSpec("kind", ArgKind.KEYWORD, "kind")),
        ),
        "PRD" to CodeSchema(
            jsonType = "ProductType",
            required = listOf(FieldSpec("fields", ArgKind.LIST_REF, "fields")),
        ),
        "PRF" to CodeSchema(
            jsonType = "ProductTypeField",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("fieldType", ArgKind.REFERENCE, "fieldType"),
            ),
        ),
        "SUM" to CodeSchema(
            jsonType = "SumType",
            required = listOf(FieldSpec("cases", ArgKind.LIST_REF, "cases")),
        ),
        "SCS" to CodeSchema(
            jsonType = "SumTypeCase",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("caseType", ArgKind.NULLABLE_REF, "caseType"),
            ),
        ),
        "FNT" to CodeSchema(
            jsonType = "FunctionType",
            required = listOf(
                FieldSpec("parameters", ArgKind.LIST_REF, "parameters"),
                FieldSpec("result", ArgKind.REFERENCE, "result"),
            ),
            optional = listOf(FieldSpec("effects", ArgKind.LIST_REF, "effects")),
        ),
        "TPM" to CodeSchema(
            jsonType = "TypeParameter",
            required = listOf(FieldSpec("name", ArgKind.STRING, "name")),
            // bound is rarely used and goes here when present
            optional = listOf(FieldSpec("bound", ArgKind.REFERENCE, "bound")),
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
        ),
        "PRC" to CodeSchema(
            jsonType = "ParameterDecl",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
            ),
            // Layer C case (1) — Lambda.paramType inference. When absent,
            // Elaborator fills the slot from the surrounding Application
            // call-site context. Without --elaborate, an absent paramType
            // surfaces as a JsonIngest error (the canonical JSON schema
            // still requires the field).
            optional = listOf(
                FieldSpec("paramType", ArgKind.REFERENCE, "paramType"),
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
        ),
        "LET" to CodeSchema(
            jsonType = "Let",
            required = listOf(
                FieldSpec("name", ArgKind.STRING, "name"),
                FieldSpec("value", ArgKind.REFERENCE, "value"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),
        "VAR" to CodeSchema(
            jsonType = "VarRef",
            required = listOf(FieldSpec("binder", ArgKind.REFERENCE, "binder")),
        ),

        // References
        "NRF" to CodeSchema(
            jsonType = "NodeRef",
            required = listOf(FieldSpec("target", ArgKind.REFERENCE, "target")),
        ),

        // Type abstraction (N-034) and ForallType (N-035) — explicit System F.
        "TAB" to CodeSchema(
            jsonType = "TypeAbstraction",
            required = listOf(
                FieldSpec("typeParameters", ArgKind.LIST_REF, "typeParameters"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),
        "FAL" to CodeSchema(
            jsonType = "ForallType",
            required = listOf(
                FieldSpec("typeParameters", ArgKind.LIST_REF, "typeParameters"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
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
        ),

        // Foreign function interface (Layer 4)
        "FN" to CodeSchema(
            jsonType = "ForeignNode",
            required = listOf(
                FieldSpec("target", ArgKind.STRING, "target"),
                FieldSpec("foreignType", ArgKind.REFERENCE, "foreignType"),
            ),
            optional = listOf(FieldSpec("effects", ArgKind.LIST_REF, "effects")),
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
            sugarOnly = true,
        ),

        // Control flow (Layer 5 steps 1, 2)
        "MAT" to CodeSchema(
            jsonType = "Match",
            required = listOf(
                FieldSpec("scrutinee", ArgKind.REFERENCE, "scrutinee"),
                FieldSpec("cases", ArgKind.LIST_REF, "cases"),
            ),
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
                FieldSpec("recursionType", ArgKind.REFERENCE, "recursionType"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
        ),

        // Composite values (Layer 5 steps 3a, 3b)
        "PV" to CodeSchema(
            jsonType = "ProductValue",
            required = listOf(
                FieldSpec("ofType", ArgKind.REFERENCE, "ofType"),
                FieldSpec("fields", ArgKind.LIST_REF, "fields"),
            ),
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
        ),
        "SV" to CodeSchema(
            jsonType = "SumValue",
            required = listOf(
                FieldSpec("ofType", ArgKind.REFERENCE, "ofType"),
                FieldSpec("caseName", ArgKind.STRING, "caseName"),
                FieldSpec("payload", ArgKind.NULLABLE_REF, "payload"),
            ),
        ),

        // Recursive types
        "RT" to CodeSchema(
            jsonType = "RecursiveType",
            required = listOf(FieldSpec("body", ArgKind.REFERENCE, "body")),
        ),
        "RS" to CodeSchema(
            jsonType = "RecursiveSelf",
            required = emptyList(),
        ),

        // Handler (Layer 3 step 3)
        "H" to CodeSchema(
            jsonType = "Handler",
            required = listOf(
                FieldSpec("intercept", ArgKind.REFERENCE, "intercept"),
                FieldSpec("handle", ArgKind.REFERENCE, "handle"),
                FieldSpec("body", ArgKind.REFERENCE, "body"),
            ),
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
                FieldSpec("valueType", ArgKind.REFERENCE, "valueType"),
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
