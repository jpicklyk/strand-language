```kotlin
fun filesystemWrite(path: String): Int = 0

fun appendLog(): Int = filesystemWrite("/var/log/app.log") + 1

fun main() {
    println(appendLog() + 10)
}
```
