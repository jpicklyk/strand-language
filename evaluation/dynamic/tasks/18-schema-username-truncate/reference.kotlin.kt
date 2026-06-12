// Strand probes the runtime SchemaInvariantViolation on a dynamic
// value. The Kotlin parallel enforces the invariant with
// `init { require() }`; the equivalent slip surfaces as a runtime
// IllegalArgumentException after kotlinc accepts the program.
data class ShortName(val value: String) {
    init {
        require(value.length <= 8) { "ShortName invariant violated: $value" }
    }
}

fun identity(p: ShortName): ShortName = p

fun main() {
    val raw = "strand" + "_agent"
    val username = ShortName(raw.substring(0, 8))
    println(identity(username).value.length)
}
