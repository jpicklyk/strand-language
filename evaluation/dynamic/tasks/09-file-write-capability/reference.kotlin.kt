// Kotlin has no first-class effect system. The Strand task declares an
// EffectCategory and threads it through; the Kotlin parallel just calls
// a stubbed function returning 0 bytes-written.
fun fileWrite(path: String): Int = 0

fun main() {
    println(fileWrite("/tmp/strand-eval.log") + 1)
}
