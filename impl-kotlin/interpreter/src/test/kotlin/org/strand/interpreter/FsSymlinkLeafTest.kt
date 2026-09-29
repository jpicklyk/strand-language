package org.strand.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Review H6: a symlink at the LEAF of an `Fs.*` path (dangling or not)
 * must not let a write land outside the workspace. The pre-fix fallback
 * for a non-existent leaf checked only the parents, so `Fs.Write` through
 * a dangling link created the link's target outside the workspace.
 *
 * The decision logic is tested twice: as a pure function over a fake
 * [FsSandbox.PathProbe] (runs everywhere), and end to end over a real
 * symlink (skipped with a reason when the OS refuses to create one, as
 * Windows does without Developer Mode or Administrator rights).
 */
class FsSymlinkLeafTest {

    // ---------- pure decision logic ----------

    private val root: Path = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("strand-fake-ws")
    private val outside: Path = root.parent.resolve("strand-fake-outside")

    /** A fake filesystem: [dirs] exist as real directories, [links] are symlinks (link -> target). */
    private class FakeProbe(val dirs: Set<Path>, val files: Set<Path> = emptySet(), val links: Map<Path, Path> = emptyMap()) :
        FsSandbox.PathProbe {
        override fun isSymlink(p: Path) = p in links
        override fun exists(p: Path): Boolean = exists(p, 0)
        private fun exists(p: Path, hops: Int): Boolean {
            if (hops > 40) return false  // a link loop does not exist, as on a real filesystem
            val target = links[p] ?: return p in dirs || p in files
            return exists(target, hops + 1)
        }
        override fun readLink(p: Path): Path = links.getValue(p)
        override fun realPath(p: Path): Path {
            val target = links[p] ?: return p
            return realPath(target)
        }
    }

    private fun policy(follow: Boolean) = FsPolicy(workspaceRoot = root, escape = EscapePolicy.Deny, followSymlinks = follow)

    @Test
    fun `no-follow rejects a dangling symlink leaf`() {
        val probe = FakeProbe(dirs = setOf(root), links = mapOf(root.resolve("out.txt") to outside.resolve("pwned.txt")))
        val ex = assertThrows<SandboxViolation> { FsSandbox.resolve(policy(follow = false), "out.txt", probe) }
        assertEquals(SandboxViolationKind.FsSymlinkRejected, ex.kind)
    }

    @Test
    fun `no-follow rejects a symlink leaf whose target is inside the workspace too`() {
        val probe = FakeProbe(
            dirs = setOf(root), files = setOf(root.resolve("real.txt")),
            links = mapOf(root.resolve("alias.txt") to root.resolve("real.txt")),
        )
        val ex = assertThrows<SandboxViolation> { FsSandbox.resolve(policy(follow = false), "alias.txt", probe) }
        assertEquals(SandboxViolationKind.FsSymlinkRejected, ex.kind)
    }

    @Test
    fun `follow resolves a dangling leaf and denies a target outside the workspace`() {
        val probe = FakeProbe(dirs = setOf(root, outside), links = mapOf(root.resolve("out.txt") to outside.resolve("pwned.txt")))
        val ex = assertThrows<SandboxViolation> { FsSandbox.resolve(policy(follow = true), "out.txt", probe) }
        assertEquals(SandboxViolationKind.FsPathEscape, ex.kind)
    }

    @Test
    fun `follow admits a dangling leaf whose target is inside the workspace and returns the target`() {
        val probe = FakeProbe(dirs = setOf(root), links = mapOf(root.resolve("alias.txt") to root.resolve("new.txt")))
        assertEquals(root.resolve("new.txt"), FsSandbox.resolve(policy(follow = true), "alias.txt", probe))
    }

    @Test
    fun `follow bounds symlink loops`() {
        val a = root.resolve("a")
        val b = root.resolve("b")
        val probe = FakeProbe(dirs = setOf(root), links = mapOf(a to b, b to a))
        val ex = assertThrows<SandboxViolation> { FsSandbox.resolve(policy(follow = true), "a", probe) }
        assertEquals(SandboxViolationKind.FsSymlinkRejected, ex.kind)
    }

    @Test
    fun `plain new file inside the workspace is admitted in both modes`() {
        val probe = FakeProbe(dirs = setOf(root))
        assertEquals(root.resolve("new.txt"), FsSandbox.resolve(policy(follow = false), "new.txt", probe))
        assertEquals(root.resolve("new.txt"), FsSandbox.resolve(policy(follow = true), "new.txt", probe))
    }

    // ---------- end to end over a real symlink ----------

    private fun trySymlink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (_: java.nio.file.FileSystemException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: SecurityException) {
        false
    }

    @Test
    fun `Fs_Write through a real dangling symlink does not create the outside target`(@TempDir tmp: Path) {
        val ws = Files.createDirectories(tmp.resolve("ws"))
        val out = Files.createDirectories(tmp.resolve("outside"))
        val target = out.resolve("pwned.txt")
        assumeTrue(
            trySymlink(ws.resolve("out.txt"), target),
            "symlink creation is not permitted on this host (Windows needs Developer Mode or Administrator)",
        )
        val write = Builtins.lookup("strand-builtin:Fs.Write")!!
        for (follow in listOf(false, true)) {
            val ctx = HostContext.processDefault().copy(
                sandboxPolicy = SandboxPolicy(
                    fs = FsPolicy(workspaceRoot = ws, escape = EscapePolicy.Deny, followSymlinks = follow),
                    net = SandboxPolicy.OPEN_DEFAULT.net,
                ),
            )
            assertThrows<SandboxViolation>("followSymlinks=$follow") {
                write.invoke(ctx, listOf(Value.StringV("out.txt"), Value.BytesV("x".toByteArray())))
            }
            assertFalse(Files.exists(target), "the write escaped the workspace (followSymlinks=$follow)")
        }
    }
}
