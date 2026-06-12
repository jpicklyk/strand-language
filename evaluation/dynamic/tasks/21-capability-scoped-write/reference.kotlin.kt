// Strand probes CapabilityScopeUnsatisfiable: the CAP grant must
// cover the clock read that derives the audit path, not just the
// write. Kotlin has no capability scopes; the parallel always runs.
fun writeAudit(path: String): Int = 0

fun main() {
    val path = "audit-" + System.currentTimeMillis()
    println(writeAudit(path) + 7)
}
