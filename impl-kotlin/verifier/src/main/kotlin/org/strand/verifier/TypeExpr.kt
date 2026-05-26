package org.strand.verifier

import org.strand.core.NodeId
import org.strand.core.Primitive

/**
 * Internal type expressions used during verification.
 *
 * These are distinct from the user-authored type nodes (PrimitiveType,
 * ProductType, FunctionType, TypeParameter, ForallType, ...). The verifier
 * translates type nodes into [TypeExpr]s, performs explicit-instantiation type
 * checking, and reports back in terms of [TypeExpr]. The Layer 1 verifier
 * does not synthesize new type nodes in the store; it works alongside it.
 *
 * Polymorphism is by explicit type abstraction (System F): a polymorphic term
 * is a [Node.TypeAbstraction] whose type is a [TypeExpr.Forall]. At every
 * Application of a polymorphic value the call site supplies a positional list
 * of type arguments equal in length to the function's type-parameter list.
 * The verifier substitutes the type arguments through the ForallType's body
 * to obtain a FunctionType, then requires structural equality between
 * substituted parameter types and argument types. No unification,
 * occurs-check, or let-generalization is performed.
 */
sealed class TypeExpr {
    /** A monomorphic primitive: Int, Float, String, Bool, Unit, Bytes. */
    data class Prim(val kind: Primitive) : TypeExpr() {
        override fun toString(): String = kind.name
    }

    /**
     * A reference to a user-declared TypeParameter node, identified by node id.
     * Two TypeExpr.Param values with the same [origin] are the same type
     * variable. Explicit instantiation at an Application substitutes a
     * concrete TypeExpr for the Param.
     */
    data class Param(val origin: NodeId, val name: String? = null) : TypeExpr() {
        override fun toString(): String = name?.let { "'$it" } ?: "'param#${origin.value}"
    }

    /**
     * A function type. [effects] is the set of EffectCategory NodeIds the
     * function may exercise; an empty set denotes a pure function. Effects
     * are part of structural type identity — two function types are equal
     * only when their parameters, result, AND effect sets are equal.
     */
    data class Fun(
        val parameters: List<TypeExpr>,
        val result: TypeExpr,
        val effects: Set<NodeId> = emptySet(),
    ) : TypeExpr() {
        override fun toString(): String {
            val effectStr = if (effects.isEmpty()) "" else " ![${effects.joinToString(",") { "fx#${it.value}" }}]"
            return "(${parameters.joinToString(", ")}) ->$effectStr $result"
        }
    }

    /**
     * A product type. The [origin] NodeId is kept for diagnostics (it
     * points back to the ProductType node this TypeExpr was resolved from)
     * but is *not* considered in structural equality: two product types
     * with the same `{name: type}` field set are equal regardless of
     * which node they came from. This is what makes Strand's type system
     * truly structural — and is essential for recursive types, where the
     * same logical product type can be reached via different paths
     * (directly vs. through a Recursive unfold).
     */
    class Product(val origin: NodeId, val fields: List<Field>) : TypeExpr() {
        data class Field(val name: String, val type: TypeExpr)

        override fun equals(other: Any?): Boolean =
            other is Product && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String =
            "{" + fields.joinToString(", ") { "${it.name}: ${it.type}" } + "}"
    }

    /**
     * A sum type. The [origin] NodeId is kept for diagnostics but does
     * not participate in structural equality (see [Product] for the
     * rationale).
     */
    class Sum(val origin: NodeId, val cases: List<Case>) : TypeExpr() {
        data class Case(val name: String, val type: TypeExpr?)

        override fun equals(other: Any?): Boolean =
            other is Sum && cases == other.cases

        override fun hashCode(): Int = cases.hashCode()

        override fun toString(): String =
            "<" + cases.joinToString(" | ") { c -> c.type?.let { "${c.name} ${it}" } ?: c.name } + ">"
    }

    /**
     * A universally quantified type, the type of a [Node.TypeAbstraction] and
     * the resolved form of a [Node.ForallType] in type position. The
     * [typeParameters] are the bound variables, identified by their
     * TypeParameter NodeId; [body] is the quantified type. Instantiation at
     * an Application substitutes positionally into [body].
     */
    data class Forall(val typeParameters: List<NodeId>, val body: TypeExpr) : TypeExpr() {
        override fun toString(): String =
            "forall(${typeParameters.joinToString(", ") { "param#${it.value}" }}). $body"
    }

    /**
     * A recursive type `μ. body`, the resolved form of a [Node.RecursiveType]
     * in type position. The body is a type expression well-formed in a scope
     * extended by one anonymous self-binder at depth 0. References to the
     * binder inside the body are [RecursiveSelf] nodes that resolve
     * positionally (always to the innermost — depth 0).
     *
     * Equirecursive equality: two `Recursive` values are equal if and only
     * if their `body` TypeExprs are equal. The canonical encoder produces
     * byte-identical encodings for structurally identical bodies, so this
     * reduces to hash equality at the implementation level.
     */
    data class Recursive(val body: TypeExpr) : TypeExpr() {
        override fun toString(): String = "μ. $body"
    }

    /**
     * A reference to the innermost enclosing [Recursive] binder. The
     * verifier resolves it from the current recursive-binder depth at the
     * reference's position; a `RecursiveSelf` outside any enclosing
     * `Recursive` is ill-formed (`UnboundRecursiveSelf`).
     *
     * The `depth` field is always 0 in the current design — Strand's
     * RecursiveSelf node has no content and always refers to the innermost
     * binder. The field is present to make positional encoding explicit and
     * to leave room for higher-arity recursive binders later (mutual
     * recursion via positional references is currently encoded by product
     * projection instead).
     */
    data class RecursiveSelf(val depth: Int = 0) : TypeExpr() {
        override fun toString(): String = "μ-self#$depth"
    }

    /**
     * A schema-refined type: a [valueType] (the underlying structural type
     * values must inhabit) plus a list of [invariants] (Invariant NodeIds)
     * the verifier evaluates when a statically-known value flows into this
     * position. The [schemaId] is the originating Schema node, kept for
     * diagnostics and for invariant-evaluation dispatch.
     *
     * Type equality and assignment: a value of type T can be assigned to a
     * position typed `SchemaType(_, T, ...)` when T equals the schema's
     * underlying `valueType` AND the invariants hold on the value (where
     * "hold" is verify-time-checkable on statically-known values;
     * otherwise the check is deferred via `SchemaInvariantDeferred`). The
     * reverse direction — assigning a `SchemaType` value into a plain T
     * position — is permitted by structural compatibility: the verifier
     * already knows the schema-typed value satisfies T structurally.
     *
     * Structural equality keys on [valueType] and the set of [invariants]
     * (treated as an unordered set, matching the canonical encoding); the
     * [schemaId] is ignored for the same reason `origin` is ignored on
     * Product/Sum: two schemas with the same underlying type and the same
     * invariant set are equivalent for type-equality purposes even if
     * authored separately.
     */
    class SchemaType(
        val schemaId: NodeId,
        val valueType: TypeExpr,
        val invariants: List<NodeId>,
    ) : TypeExpr() {
        override fun equals(other: Any?): Boolean =
            other is SchemaType &&
                valueType == other.valueType &&
                invariants.toSet() == other.invariants.toSet()

        override fun hashCode(): Int =
            valueType.hashCode() * 31 + invariants.toSet().hashCode()

        override fun toString(): String =
            "Schema($valueType, invariants=[${invariants.joinToString(",") { "inv#${it.value}" }}])"
    }
}

/**
 * Apply a substitution of TypeParameter NodeIds to TypeExprs across a type
 * expression. Substitution is total: any [TypeExpr.Param] whose origin is not
 * in the map is left as-is.
 */
internal fun substitute(t: TypeExpr, subst: Map<NodeId, TypeExpr>): TypeExpr = when (t) {
    is TypeExpr.Prim -> t
    is TypeExpr.Param -> subst[t.origin] ?: t
    is TypeExpr.Fun -> TypeExpr.Fun(
        parameters = t.parameters.map { substitute(it, subst) },
        result = substitute(t.result, subst),
        effects = t.effects  // EffectCategory NodeIds are stable; substitution does not touch them
    )
    is TypeExpr.Product -> TypeExpr.Product(
        origin = t.origin,
        fields = t.fields.map { TypeExpr.Product.Field(it.name, substitute(it.type, subst)) }
    )
    is TypeExpr.Sum -> TypeExpr.Sum(
        origin = t.origin,
        cases = t.cases.map { TypeExpr.Sum.Case(it.name, it.type?.let { ty -> substitute(ty, subst) }) }
    )
    is TypeExpr.Forall -> {
        // Bound type parameters of this Forall shadow any substitution targeting them.
        val filtered = subst.filterKeys { it !in t.typeParameters }
        TypeExpr.Forall(t.typeParameters, substitute(t.body, filtered))
    }
    is TypeExpr.Recursive -> {
        // The recursive self-binder is positional, not a TypeParameter, so
        // it does not interact with TypeParameter substitution. Simply
        // recurse into the body.
        TypeExpr.Recursive(substitute(t.body, subst))
    }
    is TypeExpr.RecursiveSelf -> t  // positional binder, no substitution
    is TypeExpr.SchemaType -> TypeExpr.SchemaType(
        // The underlying valueType may mention a TypeParameter; substitute
        // through it. Invariants are NodeIds — they are not affected by
        // type substitution (the invariants are monomorphic functions over
        // the schema's `valueType`, enforced by SchemaInvariantBodyMustBeMonomorphic).
        schemaId = t.schemaId,
        valueType = substitute(t.valueType, subst),
        invariants = t.invariants,
    )
}

