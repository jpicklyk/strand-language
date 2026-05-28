sealed class JsonValue {
    object JsonNull : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    data class JsonNumber(val value: Int) : JsonValue()
    data class JsonString(val value: String) : JsonValue()
}

fun main() {
    val v: JsonValue = JsonValue.JsonNumber(42)
    println(v)
}
