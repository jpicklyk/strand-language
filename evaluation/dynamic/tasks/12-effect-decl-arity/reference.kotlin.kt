// Strand probes EffectDeclArityMismatch: a multi-parameter
// Network.Connect category with EffectDecl whose parameter list must
// match (host, port). Kotlin has no effect-decl discipline; the
// parallel calls a stub with the host/port args directly.
fun connect(host: String, port: Int): Int = 42

fun main() {
    println(connect("localhost", 8080) + 1)
}
