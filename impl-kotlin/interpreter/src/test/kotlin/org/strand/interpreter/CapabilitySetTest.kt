package org.strand.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.strand.core.NodeId

/**
 * Unit tests for [CapabilitySet], [covers], [coversArg], and the back-
 * compat seam between `Set<NodeId>` and the new structured
 * representation.
 */
class CapabilitySetTest {

    private val filesystemWrite = NodeId(1)
    private val networkConnect = NodeId(2)
    private val timeNow = NodeId(3)

    // ----- covers() -----

    @Test
    fun `wildcard slot covers any value`() {
        val pattern = CapabilityPattern(listOf(CapabilityArgument.Wildcard))
        assertTrue(coversArg(pattern.arguments[0], Value.StringV("anything")))
        assertTrue(coversArg(pattern.arguments[0], Value.IntV(42)))
    }

    @Test
    fun `concrete slot covers by value equality`() {
        val slot = CapabilityArgument.Concrete(Value.StringV("/var/log/app.log"))
        assertTrue(coversArg(slot, Value.StringV("/var/log/app.log")))
        assertFalse(coversArg(slot, Value.StringV("/etc/passwd")))
    }

    @Test
    fun `two-slot pattern with both wildcards covers any pair`() {
        val pattern = CapabilityPattern(listOf(
            CapabilityArgument.Wildcard, CapabilityArgument.Wildcard
        ))
        assertTrue(covers(pattern, listOf(Value.StringV("api.com"), Value.IntV(443))))
        assertTrue(covers(pattern, listOf(Value.StringV("anywhere"), Value.IntV(80))))
    }

    @Test
    fun `mixed wildcard plus concrete pattern matches when concrete matches`() {
        // {host: *, port: 443}
        val pattern = CapabilityPattern(listOf(
            CapabilityArgument.Wildcard,
            CapabilityArgument.Concrete(Value.IntV(443))
        ))
        assertTrue(covers(pattern, listOf(Value.StringV("api.com"), Value.IntV(443))))
        assertFalse(covers(pattern, listOf(Value.StringV("api.com"), Value.IntV(80))))
    }

    @Test
    fun `arity mismatch fails covers`() {
        val pattern = CapabilityPattern(listOf(
            CapabilityArgument.Wildcard, CapabilityArgument.Wildcard
        ))
        assertFalse(covers(pattern, listOf(Value.StringV("only-one-arg"))))
    }

    @Test
    fun `empty-arity pattern covers an empty requirement`() {
        // Time.Now is parameterless: the requirement is an empty list.
        val pattern = CapabilityPattern(emptyList())
        assertTrue(covers(pattern, emptyList()))
        assertFalse(covers(pattern, listOf(Value.IntV(1))))
    }

    @Test
    fun `back-compat single-Wildcard pattern covers any-arity requirement`() {
        // The sentinel pattern produced by CapabilitySet.ofCategories has
        // arguments = listOf(Wildcard). It must match a requirement of
        // ANY arity — this is the seam that keeps pre-Q-031 call sites
        // (Set<NodeId> → ofCategories) working with the new runtime check.
        val sentinel = CapabilityPattern(listOf(CapabilityArgument.Wildcard))
        assertTrue(covers(sentinel, emptyList()))
        assertTrue(covers(sentinel, listOf(Value.StringV("anything"))))
        assertTrue(covers(sentinel, listOf(Value.StringV("a"), Value.IntV(1), Value.BoolV(true))))
    }

    // ----- CapabilitySet -----

    @Test
    fun `EMPTY CapabilitySet covers nothing`() {
        assertTrue(CapabilitySet.EMPTY.isEmpty())
        assertNull(CapabilitySet.EMPTY.findCovering(filesystemWrite, emptyList()))
    }

    @Test
    fun `ofCategories produces wildcards-everywhere grants for the listed categories`() {
        val cs = CapabilitySet.ofCategories(setOf(filesystemWrite, timeNow))
        assertEquals(2, cs.grants.size)
        // Both categories present; both grant lists are single-pattern.
        for (cat in setOf(filesystemWrite, timeNow)) {
            val patterns = cs.grants[cat]!!
            assertEquals(1, patterns.size)
            assertEquals(1, patterns[0].arguments.size)
            assertTrue(patterns[0].arguments[0] is CapabilityArgument.Wildcard)
        }
        // Categories not in the set have no grants.
        assertNull(cs.findCovering(networkConnect, emptyList()))
    }

    @Test
    fun `findCovering returns the matching pattern when a grant covers the requirement`() {
        // Logger holds {path: "/var/log/app.log"} only.
        val logPath = Value.StringV("/var/log/app.log")
        val pattern = CapabilityPattern(listOf(CapabilityArgument.Concrete(logPath)))
        val cs = CapabilitySet(mapOf(filesystemWrite to listOf(pattern)))

        assertNotNull(cs.findCovering(filesystemWrite, listOf(logPath)))
        assertNull(cs.findCovering(filesystemWrite, listOf(Value.StringV("/etc/passwd"))))
    }

    @Test
    fun `multiple patterns for one category give disjunction semantics`() {
        // Logger holds {path: "/var/log/app.log"} AND {path: "/var/log/audit.log"}.
        val appLog = Value.StringV("/var/log/app.log")
        val auditLog = Value.StringV("/var/log/audit.log")
        val cs = CapabilitySet(mapOf(filesystemWrite to listOf(
            CapabilityPattern(listOf(CapabilityArgument.Concrete(appLog))),
            CapabilityPattern(listOf(CapabilityArgument.Concrete(auditLog))),
        )))
        assertNotNull(cs.findCovering(filesystemWrite, listOf(appLog)))
        assertNotNull(cs.findCovering(filesystemWrite, listOf(auditLog)))
        assertNull(cs.findCovering(filesystemWrite, listOf(Value.StringV("/tmp/other"))))
    }

    @Test
    fun `intersect retains per-category patterns for retained categories`() {
        val appLog = Value.StringV("/var/log/app.log")
        val fsPattern = CapabilityPattern(listOf(CapabilityArgument.Concrete(appLog)))
        val timePattern = CapabilityPattern(emptyList())
        val cs = CapabilitySet(mapOf(
            filesystemWrite to listOf(fsPattern),
            timeNow to listOf(timePattern),
            networkConnect to listOf(CapabilityPattern(listOf(CapabilityArgument.Wildcard))),
        ))
        val narrowed = cs.intersect(setOf(filesystemWrite, timeNow))
        assertEquals(setOf(filesystemWrite, timeNow), narrowed.grants.keys)
        // Per-category pattern lists carry over verbatim.
        assertEquals(listOf(fsPattern), narrowed.grants[filesystemWrite])
        assertEquals(listOf(timePattern), narrowed.grants[timeNow])
    }

    @Test
    fun `intersect to empty produces an empty set`() {
        val cs = CapabilitySet(mapOf(
            filesystemWrite to listOf(CapabilityPattern(listOf(CapabilityArgument.Wildcard)))
        ))
        val narrowed = cs.intersect(emptySet())
        assertTrue(narrowed.isEmpty())
    }
}
