// Strand probes UncoveredEffects: a Lambda whose body calls
// Filesystem.Write without declaring the effect. Kotlin has no effect
// system; the parallel is a regular helper function that calls a stub.
fun writeLog(path: String, contents: ByteArray): Int = 0

fun appendLog(): Int = writeLog("/tmp/strand-eval.log", byteArrayOf()) + 1

fun main() {
    println(appendLog() + 10)
}
