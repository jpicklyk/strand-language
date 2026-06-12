// Strand probes the Q-039 projection family (effect-instance node
// identity, order, arity at the netConnect call site). Kotlin has no
// effect declarations; the parallel is a stubbed connect.
fun connect(host: String, port: Int): Int = 7

fun main() {
    println(connect("db.internal", 5432) + 1)
}
