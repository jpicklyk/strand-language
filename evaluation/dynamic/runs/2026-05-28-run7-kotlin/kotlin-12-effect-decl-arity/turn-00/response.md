```kotlin
fun connect(host: String, port: Int): Int = 42

fun main() {
    val host = "localhost"
    val port = 8080
    val result = connect(host, port) + 1
    println(result)
}
```
