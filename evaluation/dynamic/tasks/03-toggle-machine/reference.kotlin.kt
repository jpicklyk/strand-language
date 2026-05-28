class Toggle {
    var state: Boolean = false
    fun step(event: Unit) {
        state = !state
    }
}

fun main() {
    val m = Toggle()
    repeat(3) { m.step(Unit) }
    println(m.state)
}
