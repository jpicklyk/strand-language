// Strand probes SchemaInvariantViolation on a boundary literal. Kotlin
// uses init { require(...) } as the invariant; the parallel constructs
// a PositiveInt with value 1 (the smallest x > 0). Constructing with 0
// would throw at runtime.
data class PositiveInt(val value: Int) {
    init { require(value > 0) { "PositiveInt requires value > 0, got $value" } }
}

fun identity(p: PositiveInt): PositiveInt = p

fun main() {
    val p = identity(PositiveInt(1))
    println(p)
}
