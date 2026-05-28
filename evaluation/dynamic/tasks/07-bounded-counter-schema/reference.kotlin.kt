data class PositiveInt(val value: Int) {
    init { require(value > 0) { "PositiveInt requires value > 0, got $value" } }
}

fun main() {
    val p = PositiveInt(5)
    println(p)
}
