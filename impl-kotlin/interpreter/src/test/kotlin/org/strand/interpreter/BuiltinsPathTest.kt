package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stdlib expansion round 4 — Path.* pure path-string manipulation.
 * Behavior is platform-aware (java.nio.file.Paths uses the JVM's
 * default separator). Tests assert OS-independent invariants —
 * "the basename of `foo/bar.txt` ends with `bar.txt`", "Normalize
 * collapses `..` lexically" — rather than literal exact-string
 * equality where the separator might differ across OSes.
 */
class BuiltinsPathTest {

    private fun lookup(name: String) = Builtins.lookup(name)!!

    private val sep = java.io.File.separator

    @Test
    fun `Path_Join concatenates with separator`() {
        val fn = lookup("strand-builtin:Path.Join")
        val result = (fn.invoke(listOf(Value.StringV("foo"), Value.StringV("bar"))) as Value.StringV).v
        assertEquals("foo${sep}bar", result)
    }

    @Test
    fun `Path_Join with absolute second segment replaces first`() {
        // POSIX-style absolute. On Windows this becomes a root-relative
        // path; we just assert that the second component is preserved.
        val fn = lookup("strand-builtin:Path.Join")
        val result = (fn.invoke(listOf(Value.StringV("foo"), Value.StringV("/abs/path"))) as Value.StringV).v
        assertTrue(result.endsWith("abs${sep}path") || result.endsWith("abs/path")) {
            "expected ending with 'abs/path', got $result"
        }
    }

    @Test
    fun `Path_Basename returns last component`() {
        val fn = lookup("strand-builtin:Path.Basename")
        assertEquals("bar.txt", (fn.invoke(listOf(Value.StringV("foo/bar.txt"))) as Value.StringV).v)
        assertEquals("bar.txt", (fn.invoke(listOf(Value.StringV("bar.txt"))) as Value.StringV).v)
        assertEquals("bar", (fn.invoke(listOf(Value.StringV("foo/baz/bar"))) as Value.StringV).v)
    }

    @Test
    fun `Path_Basename of empty or root is empty`() {
        val fn = lookup("strand-builtin:Path.Basename")
        // Empty path → empty basename (Paths.get("") has null fileName on JVM).
        assertEquals("", (fn.invoke(listOf(Value.StringV("/"))) as Value.StringV).v)
    }

    @Test
    fun `Path_Dirname returns parent`() {
        val fn = lookup("strand-builtin:Path.Dirname")
        val result = (fn.invoke(listOf(Value.StringV("foo/bar/baz.txt"))) as Value.StringV).v
        // Should contain foo and bar regardless of separator.
        assertTrue(result.contains("foo") && result.contains("bar")) {
            "expected dirname to contain foo and bar, got $result"
        }
    }

    @Test
    fun `Path_Dirname of bare name is empty`() {
        val fn = lookup("strand-builtin:Path.Dirname")
        assertEquals("", (fn.invoke(listOf(Value.StringV("bar.txt"))) as Value.StringV).v)
    }

    @Test
    fun `Path_Extension returns ext without leading dot`() {
        val fn = lookup("strand-builtin:Path.Extension")
        assertEquals("txt", (fn.invoke(listOf(Value.StringV("foo.txt"))) as Value.StringV).v)
        assertEquals("gz", (fn.invoke(listOf(Value.StringV("archive.tar.gz"))) as Value.StringV).v)
        assertEquals("kt", (fn.invoke(listOf(Value.StringV("path/to/File.kt"))) as Value.StringV).v)
    }

    @Test
    fun `Path_Extension empty for no-extension, hidden-file, and trailing-dot`() {
        val fn = lookup("strand-builtin:Path.Extension")
        assertEquals("", (fn.invoke(listOf(Value.StringV("Makefile"))) as Value.StringV).v)
        // Hidden file (POSIX convention): no extension.
        assertEquals("", (fn.invoke(listOf(Value.StringV(".bashrc"))) as Value.StringV).v)
        // Trailing dot: empty extension.
        assertEquals("", (fn.invoke(listOf(Value.StringV("foo."))) as Value.StringV).v)
    }

    @Test
    fun `Path_Normalize collapses dot and dotdot lexically`() {
        val fn = lookup("strand-builtin:Path.Normalize")
        // `foo/./bar` → `foo/bar`
        val r1 = (fn.invoke(listOf(Value.StringV("foo/./bar"))) as Value.StringV).v
        assertTrue(r1.endsWith("foo${sep}bar") || r1.endsWith("foo/bar")) {
            "expected normalized foo/bar, got $r1"
        }
        // `foo/baz/../bar` → `foo/bar`
        val r2 = (fn.invoke(listOf(Value.StringV("foo/baz/../bar"))) as Value.StringV).v
        assertTrue(r2.endsWith("foo${sep}bar") || r2.endsWith("foo/bar")) {
            "expected normalized foo/bar, got $r2"
        }
    }

    @Test
    fun `Path_Normalize is lexical only, does not check filesystem`() {
        // Even a path to a non-existent file normalizes; this distinguishes
        // it from Fs.* which would require real existence (and an effect).
        val fn = lookup("strand-builtin:Path.Normalize")
        val r = (fn.invoke(listOf(Value.StringV("/does/not/exist/./file.txt"))) as Value.StringV).v
        assertTrue(r.contains("file.txt")) { "expected file.txt in $r" }
    }
}
