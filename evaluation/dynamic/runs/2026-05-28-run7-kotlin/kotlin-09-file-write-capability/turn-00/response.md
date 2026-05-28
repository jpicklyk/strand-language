```kotlin
// Filesystem.Write is a no-op stub that returns 0 bytes-written.
// In Kotlin, there is no first-class effect system, so the "capability"
// is implicit — the function is just called directly. The framework
// treats compile-time acceptance as the effect check.
fun filesystemWrite(path: String): Int {
    // No-op stub mirroring the reference implementation: returns 0 bytes-written.
    return 0
}

fun intAdd(a: Int, b: Int): Int = a + b

fun main() {
    val bytesWritten = filesystemWrite("/tmp/strand-eval.log")
    val result = intAdd(bytesWritten, 1)
    println(result)
}
```
