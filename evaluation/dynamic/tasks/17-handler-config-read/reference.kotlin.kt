// Strand probes HandlerSignatureMismatch: the handle lambda must
// match fsRead's (String) -> Bytes signature. The Kotlin parallel
// uses a stub returning ByteArray; returning String would be caught
// by kotlinc only where an annotation disagrees with the body.
fun readConfig(path: String): ByteArray = "debug=true".toByteArray()

fun main() {
    println(readConfig("/etc/app.conf").size)
}
