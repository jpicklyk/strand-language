package org.strand.authoring.familiar

/**
 * Q-061 Layer F — AST for the familiar-shaped authoring dialect.
 *
 * The shapes mirror the proposal's construct table
 * (proposals/familiar-surface-lowering.md § 4.1) for the step-1 subset:
 * `use` imports (prelude names and hash-pinned references), `type`
 * aliases over product and tagged-union shapes, `declare function`
 * foreign declarations, `const` bindings, fully annotated `function`
 * declarations with `uses` effect clauses, arrow functions, `match`,
 * `attempt`, object literals, field access, and the literal forms.
 *
 * Every node carries its 1-based source line; the lowerer threads the
 * line through to the Layer A document model so the Q-051
 * NodeRefAnnotator can render verifier errors against the Layer F
 * source the agent actually wrote.
 */
internal data class FProgram(val items: List<FItem>)

internal sealed class FItem {
    abstract val line: Int
}

/** `use prelude.{a, b}` or `use prelude.a`. */
internal data class FUsePrelude(val names: List<String>, override val line: Int) : FItem()

/** `use "b3:<hex>" as name` — a hash-pinned NodeRef import (N-019). */
internal data class FUseHash(val hashHex: String, val alias: String, override val line: Int) : FItem()

/**
 * `type Name = { a: T, b: U }` (product) or
 * `type Name = { tag: "A", v: T } | { tag: "B" }` (tagged union).
 * A product alias is a single untagged shape; a union alias is one or
 * more tagged shapes.
 */
internal data class FTypeAlias(val name: String, val shapes: List<FShape>, override val line: Int) : FItem() {
    val isProduct: Boolean get() = shapes.size == 1 && shapes[0].tag == null
}

/** One `{ ... }` shape in a type alias; [tag] is the `tag: "X"` discriminant when present. */
internal data class FShape(val tag: String?, val fields: List<FField>, val line: Int)

internal data class FField(val name: String, val type: FTypeRef, val line: Int)

internal sealed class FTypeRef {
    abstract val line: Int
}

/** `Int`, `String`, `Bool`, ... or a declared type-alias name. */
internal data class FNamedType(val name: String, override val line: Int) : FTypeRef()

/** `(T, U) => R` function type. */
internal data class FFnType(val params: List<FTypeRef>, val result: FTypeRef, override val line: Int) : FTypeRef()

internal data class FParam(val name: String, val type: FTypeRef, val line: Int)

/**
 * One `uses` clause entry: `Filesystem.Write("/tmp/x")` (parameterized
 * — [args] non-null) or `Time.Now` (bare — [args] null). On a
 * `declare function`, parameterized entries bind category parameters
 * to the declared value parameters by name (a surface-level Q-039
 * projection); on a `function`, they license effect-instance synthesis
 * at the body's application sites.
 */
internal data class FUsesEntry(val category: String, val args: List<FExpr>?, val line: Int)

/**
 * `declare function f(a: T): R from "strand-builtin:X" uses C(a)` —
 * an ambient foreign-function declaration lowering to a ForeignNode +
 * FunctionType pair with the declared effect row on both (the corpus
 * convention for hand-declared builtins).
 */
internal data class FDeclareFn(
    val name: String,
    val params: List<FParam>,
    val result: FTypeRef,
    val target: String,
    val uses: List<FUsesEntry>,
    override val line: Int,
) : FItem()

/** `const x = expr` / `const x: T = expr` (top level or inside a function body). */
internal data class FConst(
    val name: String,
    val annotation: FTypeRef?,
    val value: FExpr,
    override val line: Int,
) : FItem()

internal data class FFunction(
    val name: String,
    val params: List<FParam>,
    val result: FTypeRef,
    val uses: List<FUsesEntry>,
    val body: FBlock,
    override val line: Int,
) : FItem()

/** A function body: zero or more `const` statements then a single `return`. */
internal data class FBlock(val consts: List<FConst>, val result: FExpr, val line: Int)

internal sealed class FExpr {
    abstract val line: Int
}

internal data class FIntLit(val value: Long, override val line: Int) : FExpr()
internal data class FFloatLit(val value: Double, override val line: Int) : FExpr()
internal data class FStringLit(val value: String, override val line: Int) : FExpr()
internal data class FBoolLit(val value: Boolean, override val line: Int) : FExpr()
internal data class FIdent(val name: String, override val line: Int) : FExpr()

/** `target.field` — field access, or a segment of a dotted builtin name (resolved at lowering). */
internal data class FFieldGet(val target: FExpr, val field: String, override val line: Int) : FExpr()

/** `callee(args)`. The callee is an expression; identifier/dotted-name callees resolve at lowering. */
internal data class FCall(val callee: FExpr, val args: List<FExpr>, override val line: Int) : FExpr()

/**
 * `{ a: e1, b: e2 }` or `{ tag: "Cons", head: e1, tail: e2 }`. The
 * `tag` field (a string literal value) selects sum construction; an
 * untagged literal is a product construction.
 */
internal data class FObjectLit(val tag: String?, val fields: List<FObjField>, override val line: Int) : FExpr()

internal data class FObjField(val name: String, val value: FExpr, val line: Int)

internal data class FMatch(val scrutinee: FExpr, val arms: List<FArm>, override val line: Int) : FExpr()

internal data class FArm(val pattern: FPattern, val body: FExpr, val line: Int)

internal sealed class FPattern {
    abstract val line: Int
}

/** Literal pattern: `true`, `0`, `"x"`. */
internal data class FLitPattern(val literal: FExpr, override val line: Int) : FPattern()

/** `_` wildcard. */
internal data class FWildPattern(override val line: Int) : FPattern()

/**
 * Bare-identifier pattern: a payload-less constructor when the name is
 * a case of the resolved sum, otherwise a variable binder. Resolved at
 * lowering.
 */
internal data class FNamePattern(val name: String, override val line: Int) : FPattern()

/** `Ctor(binder)` / `Ctor(_)` constructor pattern with payload binding. */
internal data class FCtorPattern(
    val ctor: String,
    val binder: String?,
    val binderIsWild: Boolean,
    override val line: Int,
) : FPattern()

internal data class FAttempt(val body: FExpr, override val line: Int) : FExpr()

internal data class FArrow(val params: List<FParam>, val body: FExpr, override val line: Int) : FExpr()
