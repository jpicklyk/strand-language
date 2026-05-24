# File-by-file change templates for adding a node category

Use these as starting points for each file touched in step 5 of the main workflow. Patterns are taken from how Match, Fixpoint, ProductValue, SumValue, and RecursiveType were added.

## `impl/core/src/main/kotlin/org/strand/core/Node.kt`

Add the data class (or `object` for content-free nodes) to the sealed `Node` hierarchy, in the appropriate group section. Group order matches the N-NNN ranges roughly.

**Data class with content fields and edges:**

```kotlin
/**
 * N-NNN. <One-paragraph description: what the node represents, what its
 * fields mean, what its edges point at, any semantic constraints the
 * verifier will enforce, any encoding notes such as alpha-equivalence
 * behavior or sort-by-name canonicalization.>
 */
data class NodeName(
    val structuralField: String,    // structural content fields with comments
    val edgeChild: NodeId,          // each edge with target type in comment
    val edgeList: List<NodeId> = emptyList()  // optional or multi-edge
) : Node()
```

**Object (no content):**

```kotlin
/**
 * N-NNN. <Description.>
 */
object NodeName : Node() {
    override fun toString(): String = "NodeName"
}
```

Sealed sub-hierarchies (like the `Pattern` variants) follow the same shape but nest inside an intermediate `sealed class`.

## `impl/core/src/main/kotlin/org/strand/core/Json.kt`

Add an ingest case in `buildNode`'s `when (type)` block, alphabetically near related nodes.

**Field-extraction helpers:**
- `obj.requireString("fieldName", ctx)` — required string content
- `obj.requireRef("edgeName", ctx, resolve)` — required edge to one node
- `obj.optionalRef("edgeName", ctx, resolve)` — optional edge (null when absent)
- `obj.requireRefList("edgeName", ctx, resolve)` — required edge list (may be empty)
- `obj.optionalRefList("edgeName", ctx, resolve)` — optional edge list (defaults to empty)
- `obj.requireLong("fieldName", ctx)`, `requireDouble`, `requireBoolean` — for primitives

**Example:**

```kotlin
"NodeName" -> Node.NodeName(
    structuralField = obj.requireString("fieldName", ctx),
    edgeChild = obj.requireRef("edgeName", ctx, resolve),
    edgeList = obj.optionalRefList("edges", ctx, resolve)
)
```

Then update two things:
1. The "Unknown node type" rejection message's identifier range at the end of the `when` block
2. The schema documentation comment block at the top of the file — if the new node is user-visible, add a one-line schema entry under the appropriate section header

## `impl/hashing/src/main/kotlin/org/strand/hashing/CategoryTag.kt`

Add the tag constant matching the N-NNN, grouped with related tags. Comment with the N-NNN identifier.

```kotlin
// Composite values (N-037..N-040)
val ProductValue = CategoryTag(37)
val ProductFieldValue = CategoryTag(38)
val ProductFieldGet = CategoryTag(39)
val SumValue = CategoryTag(40)
// Add the new entry here
val NodeName = CategoryTag(NNN)
```

## `impl/hashing/src/main/kotlin/org/strand/hashing/CanonicalEncoder.kt`

Two changes: add to the dispatch table, then add a per-node encoder.

**Dispatch (in `encodeDispatch`):**

```kotlin
is Node.NodeName -> encodeNodeName(node, stack)
```

**Per-node encoder — patterns by node shape:**

*Simple leaf (no children):*

```kotlin
private fun encodeNodeName(node: Node.NodeName, stack: BinderStack): ByteArray {
    return encodeWithTag(CategoryTag.NodeName, listOf(
        CanonicalCbor.encodeBytes(node.contentField.toByteArray(Charsets.UTF_8))
    ))
}
```

*Node with type-position children:*

```kotlin
private fun encodeNodeName(node: Node.NodeName, stack: BinderStack): ByteArray {
    return encodeWithTag(CategoryTag.NodeName, listOf(
        encodeTypePositionChild(node.targetType, stack)  // handles inline TypeParameter refs
    ))
}
```

*Node with expression-position children:*

```kotlin
private fun encodeNodeName(node: Node.NodeName, stack: BinderStack): ByteArray {
    return encodeWithTag(CategoryTag.NodeName, listOf(
        encodeExpressionChild(node.target, stack)  // emits hash reference
    ))
}
```

*Binder-introducing node (extends the de Bruijn stack):*

```kotlin
private fun encodeNodeName(node: Node.NodeName, stack: BinderStack): ByteArray {
    val newStack = stack + listOf(node.parameters)  // or listOf(listOf(id)) for single binder
    return encodeWithTag(CategoryTag.NodeName, listOf(
        // ... children encoded in extended stack
        encodeExpressionChild(node.body, newStack)
    ))
}
```

*Set-like edge list (canonical-order by hash, not by declaration):*

```kotlin
val edgeHashes = node.edges
    .map { hash(it, stack) }
    .sortedWith(byteArrayLexicographicComparator)
    .map { CanonicalCbor.encodeBytes(it) }
// then emit CanonicalCbor.encodeArray(edgeHashes) as a field
```

*Structural-identifier list (sort by name, not by hash, when names participate in identity):*

```kotlin
val sortedFieldIds = node.fields.sortedBy { fieldId ->
    requireSomeStructuralNode(fieldId).fieldName
}
val fieldHashes = sortedFieldIds.map { fieldId ->
    CanonicalCbor.encodeBytes(hash(fieldId, stack))
}
```

*Optional payload (presence-prefixed):*

```kotlin
val payloadFields = if (node.payload == null) {
    listOf(CanonicalCbor.encodeUint(0L))
} else {
    listOf(
        CanonicalCbor.encodeUint(1L),
        encodeExpressionChild(node.payload, stack)
    )
}
return encodeWithTag(CategoryTag.NodeName, listOf(/* other fields */) + payloadFields)
```

*Recursive-binder node (RecursiveType pattern):*

```kotlin
private fun encodeNodeName(node: Node.NodeName, stack: BinderStack): ByteArray {
    pushRecursiveBinder()
    try {
        val bodyEncoding = encodeTypePositionChild(node.body, stack)
        return encodeWithTag(CategoryTag.NodeName, listOf(bodyEncoding))
    } finally {
        popRecursiveBinder()
    }
}
```

## `impl/hashing/src/main/kotlin/org/strand/hashing/Hasher.kt`

Two changes possible: the early-return list at the top of `walk`, and a case in the main `when`.

**Bound/intrinsic nodes (no standalone hash entry):** add to the early-return guard at the top:

```kotlin
if (node is Node.ParameterDecl
    || node is Node.TypeParameter
    || node is Node.RecursiveSelf
    || node is Node.NodeName) {  // your bound node here
    return
}
```

**All other nodes:** add a case to the main `when (node)`. Recurse into children, pushing the binder stack if the node introduces a binder. For RecursiveType-style nodes, use `encoder.pushRecursiveBinder()` and `encoder.popRecursiveBinder()` in a try/finally so the encoder's depth stays in sync.

For MatchCase-style nodes that introduce binders via patterns, use `collectPatternBinders(store, ...)` to gather all VariablePatterns reachable from the pattern tree into a single binder frame.

## `impl/verifier/src/main/kotlin/org/strand/verifier/Verifier.kt`

### Expression nodes

1. Add a dispatch case in the main `when (node)` inside `infer`:

```kotlin
is Node.NodeName -> inferNodeName(id, node, scope, typeParams)
```

2. Write the inference helper:

```kotlin
private fun inferNodeName(
    id: NodeId,
    node: Node.NodeName,
    scope: Map<NodeId, TypeExpr>,
    typeParams: Set<NodeId>,
): TypeExpr {
    // 1. Validate edges and resolve sub-types
    val targetType = resolveType(node.target, typeParams)
    // OR for an expression child:
    val childType = infer(node.child, scope, typeParams)

    // 2. Check semantic constraints, reporting errors via report() or reportAndAbort()
    if (/* some constraint fails */) {
        report(VerifyError.SomeNewError(at = id, ...))
        throw VerifyAbort()
    }

    // 3. Compute effect closure
    recordClosure(id, /* union of child closures, possibly + own declared effects */)

    // 4. Return the node's TypeExpr
    return /* the inferred type */
}
```

### Type nodes

Extend `resolveType`'s `when (node)`:

```kotlin
is Node.NodeName -> {
    // For binders: increment recursiveDepth or push typeParams
    val body = resolveType(node.body, typeParams)
    // For contractivity or other static checks
    if (/* not contractive */) {
        report(VerifyError.NonContractiveX(at = typeId))
        throw VerifyAbort()
    }
    TypeExpr.SomeNew(body)
}
```

### Structural pieces (MatchCase-like, ProductFieldValue-like)

Add to the catch-all "<expression position>" rejection list:

```kotlin
is Node.PrimitiveType,
// ... existing entries ...
is Node.NodeName ->  // your structural node here
    reportFatal(VerifyError.CategoryMismatch(
        at = id,
        field = "<expression position>",
        expectedCategory = "Expression",
        actualCategory = categoryName(node)
    ))
```

## `impl/verifier/src/main/kotlin/org/strand/verifier/VerifyError.kt`

Add sealed-class data variants for each new error condition. Group with related errors by feature.

```kotlin
/**
 * <One-sentence description of the error condition.>
 */
data class NewErrorName(
    override val at: NodeId,
    val expected: TypeExpr,
    val actual: TypeExpr,
    // any other relevant fields
) : VerifyError()
```

Then update `categoryName` at the bottom of the file with the new node:

```kotlin
is Node.NodeName -> "NodeName"
// For nested sealed sub-hierarchies:
is Node.Pattern.NewPatternVariant -> "NewPatternVariant"
```

## `impl/interpreter/src/main/kotlin/org/strand/interpreter/Interpreter.kt`

### Value-producing expression

Add a dispatch case in `eval`:

```kotlin
is Node.NodeName -> evalNodeName(id, node, env, context)
```

Write the eval helper:

```kotlin
private fun evalNodeName(
    id: NodeId,
    node: Node.NodeName,
    env: Map<NodeId, Value>,
    context: Set<NodeId>,
): Value {
    val sub = eval(node.child, env, context)
    // compute and return a Value
    return Value.SomeV(...)
}
```

### Callable value

If the node creates a callable (Lambda-like), add a new `Value` variant in `Value.kt`:

```kotlin
data class NodeNameFn(
    val node: Node.NodeName,
    val env: Map<NodeId, Value>,
    val self: NodeId,
) : Value()
```

Then dispatch in `applyCall`:

```kotlin
is Value.NodeNameFn -> applyNodeName(id, app, fn, env, context)
```

Write `applyNodeName` that handles arity, capability check, env extension, and body evaluation.

### Structural piece

Add to the catch-all rejection in the main `when`:

```kotlin
is Node.NodeName ->
    throw InterpretException(InterpretError.NotCallable(at = id, gotKind = node::class.simpleName ?: "Type"))
```

## `impl/interpreter/src/main/kotlin/org/strand/interpreter/InterpretError.kt`

Add sealed-class variants for new runtime errors:

```kotlin
/**
 * <Description.>
 */
data class NewRuntimeError(
    override val at: NodeId,
    // relevant fields
) : InterpretError()
```

## `impl/corpus/src/test/kotlin/org/strand/corpus/CorpusTest.kt`

Register each new program in the `cases` list:

```kotlin
Case("/corpus/NN-program-name.json", Value.IntV(expected),
    "One-paragraph natural-language description of what the program demonstrates."),
```

If the program requires a capability context (declared effects), also add to `capabilitiesFor`:

```kotlin
"/corpus/NN-program-name.json" to listOf("authorIdOfEffectCategory1", "authorIdOfEffectCategory2"),
```

## `impl/corpus/src/test/kotlin/org/strand/corpus/CorpusHashingTest.kt`

Register the program in the `corpusResources` list — that's it. The hashing test exercises every listed resource.
