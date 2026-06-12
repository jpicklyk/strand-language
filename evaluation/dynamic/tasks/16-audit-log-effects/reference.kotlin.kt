// Strand probes UncoveredEffects: a Lambda whose explicit effect row
// omits the clock-read effect its body exercises. Kotlin has no
// effect system; the parallel logs to stderr and prints the value.
fun auditedDouble(x: Int): Int {
    System.err.println("audit:" + System.currentTimeMillis())
    return x * 2
}

fun main() {
    println(auditedDouble(21))
}
