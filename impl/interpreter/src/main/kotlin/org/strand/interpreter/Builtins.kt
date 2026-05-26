package org.strand.interpreter

import kotlin.math.pow

/**
 * Registry of trusted in-process builtins that [ForeignNode][org.strand.core.Node.ForeignNode]
 * declarations may target. Layer 4 step 1 ships a small fixed set; later
 * layers will add WebAssembly-sandboxed bindings (Milestone 2.4 per the
 * research plan).
 *
 * Each builtin is a host-language function from a list of argument [Value]s
 * to a result [Value]. The runtime invokes the builtin only after the
 * verifier has type-checked the ForeignNode and the interpreter has
 * confirmed the calling capability context covers the ForeignNode's
 * declared effects (the same check applied to ordinary Lambdas).
 *
 * Builtins are pure functions from the interpreter's perspective even when
 * the foreign effect declaration says otherwise: a builtin like
 * `strand-builtin:Time.Now` returns a fixed timestamp in this reference
 * implementation so that replay determinism is preserved during testing.
 * A production runtime would replace these with real implementations under
 * the same key.
 *
 * Targets are namespaced strings; the `strand-builtin:` prefix designates
 * the in-process registry. Other registries (e.g., `wasm:`, `process:`)
 * will be served by separate dispatchers.
 */
object Builtins {

    /** A single foreign callable: takes the evaluated argument list and returns a value. */
    fun interface Fn {
        fun invoke(args: List<Value>): Value
    }

    /**
     * Continuation passed to higher-order builtins (Slice 2 of stdlib
     * expansion round 2). Lets a builtin invoke a user-supplied
     * callable (a Strand [Value.Closure], [Value.FixpointFn], or
     * [Value.ForeignFn]) with a list of pre-evaluated arguments,
     * returning the result Value.
     *
     * The interpreter constructs an [ApplyFn] that closes over the
     * current capability context and handler stack at the
     * higher-order builtin's call site, so callback evaluations see
     * the same environment the surrounding code sees.
     *
     * Closures must have exactly `args.size` parameters; fixpoints
     * have `args.size + 1` (the first is the recursive self-slot).
     */
    fun interface ApplyFn {
        fun apply(fn: Value, args: List<Value>): Value
    }

    /**
     * Higher-order foreign callable: receives an [ApplyFn] alongside
     * the standard argument list so it can call user-supplied
     * callables. Distinct from [Fn] so the dispatcher knows to thread
     * the callback through.
     */
    fun interface FnH {
        fun invoke(args: List<Value>, apply: ApplyFn): Value
    }

    /**
     * Pluggable clock for the `Time.*` builtins. Default is [SystemClock]
     * (real wall-clock time and real sleep). Tests that need
     * deterministic timestamps install [FixedClock] in setup and reset
     * to [SystemClock] in teardown. Thread safety: this is a global
     * mutable field; tests that mutate it must not run in parallel
     * with each other or with code that reads it. Per-interpreter
     * clock injection is a future refactor if parallelism becomes a
     * concern.
     */
    interface Clock {
        fun nowMillis(): Long
        fun sleep(millis: Long)
    }

    object SystemClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun sleep(millis: Long) {
            if (millis > 0) Thread.sleep(millis)
        }
    }

    class FixedClock(private val time: Long) : Clock {
        override fun nowMillis(): Long = time
        override fun sleep(millis: Long) = Unit  // no-op in replay mode
    }

    /** The active clock. Production default: real system time. */
    @Volatile
    var clock: Clock = SystemClock

    /**
     * Pluggable randomness source for the `Random.*` builtins. Default
     * is a [java.security.SecureRandom] (cryptographically secure
     * non-deterministic). Tests that need reproducible random sequences
     * install a seeded [java.util.Random] in setup and reset in
     * teardown. Thread safety mirrors [clock]: tests that mutate this
     * must not run in parallel.
     *
     * Both `Random.Int` / `Random.Float` / `Random.Bytes` declare
     * effect category `E-024 Crypto.RandomBytes` (the existing
     * registry entry covering cryptographically-secure entropy).
     */
    @Volatile
    var random: java.util.Random = java.security.SecureRandom()

    /**
     * Pluggable log sink for the `Log.*` builtins (round 3). Default
     * routes log lines to `System.err` so they don't tangle with
     * stdout-bound program output. Tests install a captured sink to
     * assert on the emitted lines. Effect category: E-032 Log.Write.
     */
    interface LogSink {
        fun println(line: String)
    }
    object StderrLogSink : LogSink {
        override fun println(line: String) = System.err.println(line)
    }
    @Volatile
    var logSink: LogSink = StderrLogSink

    /**
     * Pluggable host-environment source for the `OS.*` builtins
     * (round 3). Default reads from the live JVM / OS state. Tests
     * install a fixed-value source for replay determinism. Effect
     * category: E-033 OS.Read.
     */
    interface OsEnv {
        fun hostname(): String
        fun platform(): String
        fun cwd(): String
    }
    object SystemOsEnv : OsEnv {
        override fun hostname(): String =
            try { java.net.InetAddress.getLocalHost().hostName }
            catch (_: java.net.UnknownHostException) { "unknown" }
        override fun platform(): String =
            System.getProperty("os.name", "unknown").lowercase().let { name ->
                when {
                    "windows" in name -> "windows"
                    "linux" in name -> "linux"
                    "mac" in name || "darwin" in name -> "macos"
                    "bsd" in name -> "bsd"
                    else -> name
                }
            }
        override fun cwd(): String = System.getProperty("user.dir", ".")
    }
    @Volatile
    var osEnv: OsEnv = SystemOsEnv

    /**
     * Pluggable exit handler for `System.Exit` (round 3, E-034).
     * Default calls `kotlin.system.exitProcess(code)` which
     * terminates the JVM. Tests install [TestExitHandler] which
     * captures the code and throws [SystemExitInvoked] so the test
     * framework observes the call without the JVM actually
     * terminating.
     */
    interface ExitHandler {
        fun exit(code: Int)
    }
    object RealExitHandler : ExitHandler {
        override fun exit(code: Int): Unit = kotlin.system.exitProcess(code)
    }
    class SystemExitInvoked(val code: Int) : RuntimeException("System.Exit($code)")
    class TestExitHandler : ExitHandler {
        var lastCode: Int? = null
        override fun exit(code: Int) {
            lastCode = code
            throw SystemExitInvoked(code)
        }
    }
    @Volatile
    var exitHandler: ExitHandler = RealExitHandler

    private val registry: Map<String, Fn> = mapOf(
        // Pure arithmetic (no declared effects expected).
        "strand-builtin:Int.Add" to Fn { args ->
            require(args.size == 2) { "Int.Add expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a + b)
        },
        "strand-builtin:Int.Sub" to Fn { args ->
            require(args.size == 2) { "Int.Sub expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a - b)
        },
        "strand-builtin:Int.Mul" to Fn { args ->
            require(args.size == 2) { "Int.Mul expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            Value.IntV(a * b)
        },
        "strand-builtin:Int.Div" to Fn { args ->
            require(args.size == 2) { "Int.Div expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Div division by zero" }
            Value.IntV(a / b)
        },
        "strand-builtin:Int.Mod" to Fn { args ->
            require(args.size == 2) { "Int.Mod expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Int.Mod division by zero" }
            Value.IntV(a % b)
        },
        "strand-builtin:Int.Neg" to Fn { args ->
            require(args.size == 1) { "Int.Neg expects 1 arg, got ${args.size}" }
            Value.IntV(-(args[0] as Value.IntV).v)
        },
        "strand-builtin:Bool.Not" to Fn { args ->
            require(args.size == 1) { "Bool.Not expects 1 arg, got ${args.size}" }
            Value.BoolV(!(args[0] as Value.BoolV).v)
        },
        "strand-builtin:Bool.And" to Fn { args ->
            require(args.size == 2) { "Bool.And expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v && (args[1] as Value.BoolV).v)
        },
        "strand-builtin:Bool.Or" to Fn { args ->
            require(args.size == 2) { "Bool.Or expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.BoolV).v || (args[1] as Value.BoolV).v)
        },

        // Pure Int comparisons: pair with Match on a BoolLit pattern to give
        // conditional logic.
        "strand-builtin:Int.Eq" to Fn { args ->
            require(args.size == 2) { "Int.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v == (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Lt" to Fn { args ->
            require(args.size == 2) { "Int.Lt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v < (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Le" to Fn { args ->
            require(args.size == 2) { "Int.Le expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v <= (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Gt" to Fn { args ->
            require(args.size == 2) { "Int.Gt expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v > (args[1] as Value.IntV).v)
        },
        "strand-builtin:Int.Ge" to Fn { args ->
            require(args.size == 2) { "Int.Ge expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.IntV).v >= (args[1] as Value.IntV).v)
        },

        // String operations.
        "strand-builtin:String.Concat" to Fn { args ->
            require(args.size == 2) { "String.Concat expects 2 args, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v + (args[1] as Value.StringV).v)
        },
        "strand-builtin:String.Eq" to Fn { args ->
            require(args.size == 2) { "String.Eq expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.StringV).v == (args[1] as Value.StringV).v)
        },

        // Effectful: Time.Now reads from the active [clock]. Default
        // is [SystemClock] (System.currentTimeMillis()). Tests install
        // [FixedClock] to get deterministic timestamps; the existing
        // FIXED_REPLAY_TIMESTAMP constant remains as the canonical
        // fixed value for tests that compare against a known Now.
        "strand-builtin:Time.Now" to Fn { args ->
            require(args.isEmpty()) { "Time.Now expects 0 args, got ${args.size}" }
            Value.IntV(clock.nowMillis())
        },

        // Effectful: Time.Sleep(millis) suspends the current thread for
        // the requested duration via [clock.sleep]. Default SystemClock
        // calls Thread.sleep; FixedClock is a no-op so tests don't
        // actually wait. Returns UnitV.
        "strand-builtin:Time.Sleep" to Fn { args ->
            require(args.size == 1) { "Time.Sleep expects 1 arg (millis: Int), got ${args.size}" }
            val millis = (args[0] as Value.IntV).v
            require(millis >= 0) { "Time.Sleep millis must be non-negative, got $millis" }
            clock.sleep(millis)
            Value.UnitV
        },

        // Legacy stub kept for backwards-compat with the seed corpus
        // (programs 35, 39 and the test fixtures in InterpreterTest /
        // ElaboratorTest). Returns IntV(0) for any single StringV arg.
        // Real filesystem writes use the `strand-builtin:Fs.Write`
        // target (Layer 4 step 2 — see below). A future cleanup pass
        // may unify the namespaces once the corpus is updated.
        "strand-builtin:Filesystem.Write" to Fn { args ->
            require(args.size == 1) { "Filesystem.Write expects 1 arg (path: String), got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Filesystem.Write expects a StringV path argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)  // bytes written; legacy stub semantics
        },

        // Layer 4 step 2 — Filesystem builtins under the `Fs.*` target
        // namespace. Real OS calls via java.nio.file.Files. IO failures
        // throw IoFailure, which the interpreter translates to
        // InterpretError.IoFailure carrying the call-site NodeId.
        //
        // Effect category: all of these declare E-007 Filesystem.Write
        // (for Write/Append/Delete) or E-006 Filesystem.Read (for Read/
        // Exists/List). Programs that use them must declare the
        // appropriate EffectCategory and grant a CapabilitySet that
        // covers the call site.

        "strand-builtin:Fs.Write" to Fn { args ->
            require(args.size == 2) {
                "Fs.Write expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-write", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-write", "expected BytesV content, got ${args[1]::class.simpleName}")
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(path), bytes)
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-write", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Read" to Fn { args ->
            require(args.size == 1) {
                "Fs.Read expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-read", "expected StringV path, got ${args[0]::class.simpleName}")
            try {
                Value.BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
            } catch (e: java.nio.file.NoSuchFileException) {
                throw IoFailure("filesystem-read", "$path: file does not exist")
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-read", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Append" to Fn { args ->
            require(args.size == 2) {
                "Fs.Append expects 2 args (path: String, bytes: Bytes), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-append", "expected StringV path, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("filesystem-append", "expected BytesV content, got ${args[1]::class.simpleName}")
            try {
                java.nio.file.Files.write(
                    java.nio.file.Paths.get(path),
                    bytes,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-append", "$path: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("filesystem-append", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.Exists" to Fn { args ->
            require(args.size == 1) {
                "Fs.Exists expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-exists", "expected StringV path, got ${args[0]::class.simpleName}")
            Value.BoolV(java.nio.file.Files.exists(java.nio.file.Paths.get(path)))
        },

        "strand-builtin:Fs.Delete" to Fn { args ->
            require(args.size == 1) {
                "Fs.Delete expects 1 arg (path: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-delete", "expected StringV path, got ${args[0]::class.simpleName}")
            try {
                Value.BoolV(java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path)))
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-delete", "$path: ${e.message}")
            }
        },

        "strand-builtin:Fs.List" to Fn { args ->
            require(args.size == 1) {
                "Fs.List expects 1 arg (dir: String), got ${args.size}"
            }
            val path = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("filesystem-list", "expected StringV dir path, got ${args[0]::class.simpleName}")
            try {
                val entries = java.nio.file.Files.list(java.nio.file.Paths.get(path)).use { stream ->
                    stream.map { it.fileName.toString() }.sorted().toList()
                }
                // Build a SumV-encoded list using the same Cons/Nil
                // convention as the corpus (μ. Cons({head, tail}) | Nil).
                // The Strand-side type is the standard recursive String
                // list; the runtime produces a chain of SumV values
                // terminated by Nil.
                var listValue: Value = Value.SumV(case = "Nil", payload = null)
                for (entry in entries.reversed()) {
                    val payload = Value.ProductV(
                        mapOf("head" to Value.StringV(entry), "tail" to listValue)
                    )
                    listValue = Value.SumV(case = "Cons", payload = payload)
                }
                listValue
            } catch (e: java.io.IOException) {
                throw IoFailure("filesystem-list", "$path: ${e.message}")
            }
        },

        // Legacy stub kept for backwards-compat with the seed corpus
        // (programs 33, 34 and test fixtures). Real network sockets use
        // the `strand-builtin:Net.*` target namespace (Phase 2 #5).
        "strand-builtin:Network.Connect" to Fn { args ->
            require(args.size == 2) {
                "Network.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            require(args[0] is Value.StringV && args[1] is Value.IntV) {
                "Network.Connect expects StringV host + IntV port, got " +
                    "(${args[0]::class.simpleName}, ${args[1]::class.simpleName})"
            }
            Value.IntV(1)  // connection id; legacy stub semantics
        },

        // Layer 4 step 2 — Network builtins under the `Net.*` target
        // namespace. Synchronous JVM Socket-based I/O. Async wrapping
        // into the state-machine actor loop is a follow-up.
        //
        // Effect categories: Net.Connect → E-001 Network.Connect;
        // Net.Send → E-003 Network.Send; Net.Receive → E-004
        // Network.Receive. Net.Close has no specific effect (closing
        // an opened resource is the dual of opening it).

        "strand-builtin:Net.Connect" to Fn { args ->
            // (host: String, port: Int) -> SocketHandle
            require(args.size == 2) {
                "Net.Connect expects 2 args (host: String, port: Int), got ${args.size}"
            }
            val host = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("network-connect", "expected StringV host, got ${args[0]::class.simpleName}")
            val port = (args[1] as? Value.IntV)?.v
                ?: throw IoFailure("network-connect", "expected IntV port, got ${args[1]::class.simpleName}")
            try {
                val socket = java.net.Socket(host, port.toInt())
                ResourceTable.register("socket", socket)
            } catch (e: java.io.IOException) {
                throw IoFailure("network-connect", "$host:$port: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("network-connect", "$host:$port: ${e.message}")
            }
        },

        "strand-builtin:Net.Send" to Fn { args ->
            // (handle: SocketHandle, bytes: Bytes) -> Int (bytes written)
            require(args.size == 2) {
                "Net.Send expects 2 args (handle: SocketHandle, bytes: Bytes), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-send", "expected Resource handle, got ${args[0]::class.simpleName}")
            val bytes = (args[1] as? Value.BytesV)?.v
                ?: throw IoFailure("network-send", "expected BytesV content, got ${args[1]::class.simpleName}")
            val socket = ResourceTable.get(handle, "socket") as java.net.Socket
            try {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
                Value.IntV(bytes.size.toLong())
            } catch (e: java.io.IOException) {
                throw IoFailure("network-send", "socket #${handle.id}: ${e.message}")
            }
        },

        "strand-builtin:Net.Receive" to Fn { args ->
            // (handle: SocketHandle, maxBytes: Int) -> Bytes
            // Reads up to maxBytes; returns empty Bytes on EOF.
            require(args.size == 2) {
                "Net.Receive expects 2 args (handle: SocketHandle, maxBytes: Int), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-receive", "expected Resource handle, got ${args[0]::class.simpleName}")
            val maxBytes = (args[1] as? Value.IntV)?.v?.toInt()
                ?: throw IoFailure("network-receive", "expected IntV maxBytes, got ${args[1]::class.simpleName}")
            require(maxBytes >= 0) { "Net.Receive maxBytes must be non-negative, got $maxBytes" }
            val socket = ResourceTable.get(handle, "socket") as java.net.Socket
            try {
                val buf = ByteArray(maxBytes)
                val n = socket.getInputStream().read(buf)
                if (n <= 0) Value.BytesV(ByteArray(0))
                else Value.BytesV(buf.copyOf(n))
            } catch (e: java.io.IOException) {
                throw IoFailure("network-receive", "socket #${handle.id}: ${e.message}")
            }
        },

        "strand-builtin:Net.Close" to Fn { args ->
            // (handle: SocketHandle) -> Unit
            require(args.size == 1) {
                "Net.Close expects 1 arg (handle: SocketHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("network-close", "expected Resource handle, got ${args[0]::class.simpleName}")
            // Idempotent: remove from table even if close throws.
            val obj = ResourceTable.remove(handle)
            if (obj is java.net.Socket) {
                try { obj.close() } catch (_: java.io.IOException) { /* ignore — already closed */ }
            }
            Value.UnitV
        },

        // Http.Request convenience builtin (Phase 2 #6). Wraps
        // java.net.URL + HttpURLConnection so simple HTTP/HTTPS
        // calls don't require manual socket framing. HTTP/1.1 only;
        // HTTPS via the JVM's default truststore. Returns a
        // ProductV with fields: {status: Int, body: Bytes}.
        // Response headers are not exposed in this initial slice —
        // most use cases need just status + body. A follow-up can
        // add headers as a SumV-encoded List<{name, value}>.
        //
        // Effect categories: declares E-001 Network.Connect, E-003
        // Network.Send, E-004 Network.Receive when used. Programs
        // typically combine the three under one CapabilityScope.

        "strand-builtin:Http.Request" to Fn { args ->
            // (method: String, url: String, body: Bytes) -> {status: Int, body: Bytes}
            // body may be empty Bytes for GET/DELETE etc.
            require(args.size == 3) {
                "Http.Request expects 3 args (method: String, url: String, body: Bytes), got ${args.size}"
            }
            val method = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV method, got ${args[0]::class.simpleName}")
            val urlStr = (args[1] as? Value.StringV)?.v
                ?: throw IoFailure("http-request", "expected StringV url, got ${args[1]::class.simpleName}")
            val body = (args[2] as? Value.BytesV)?.v
                ?: throw IoFailure("http-request", "expected BytesV body, got ${args[2]::class.simpleName}")
            try {
                val url = java.net.URI(urlStr).toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = method.uppercase()
                conn.doInput = true
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                val responseBody = try {
                    (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.readBytes() ?: ByteArray(0)
                } catch (_: java.io.IOException) {
                    ByteArray(0)
                }
                conn.disconnect()
                Value.ProductV(mapOf(
                    "status" to Value.IntV(status.toLong()),
                    "body" to Value.BytesV(responseBody),
                ))
            } catch (e: java.net.MalformedURLException) {
                throw IoFailure("http-request", "$urlStr: malformed URL: ${e.message}")
            } catch (e: java.net.URISyntaxException) {
                throw IoFailure("http-request", "$urlStr: URI syntax: ${e.message}")
            } catch (e: java.io.IOException) {
                throw IoFailure("http-request", "$urlStr: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("http-request", "$urlStr: ${e.message}")
            }
        },

        // Layer 4 step 2 — Process + env builtins. Spawn/Wait use
        // java.lang.ProcessBuilder; inherited stdio (child's stdout/
        // stderr go to the runtime's stdout/stderr). Captured output
        // (via Process.SpawnCapture returning Bytes) is a follow-up.

        "strand-builtin:Process.Spawn" to Fn { args ->
            // (cmd: String, args: List<String>) -> ProcessHandle
            // The args parameter is a SumV-encoded Cons/Nil chain of
            // StringV — matches the convention used by Fs.List.
            require(args.size == 2) {
                "Process.Spawn expects 2 args (cmd: String, args: List<String>), got ${args.size}"
            }
            val cmd = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("process-spawn", "expected StringV cmd, got ${args[0]::class.simpleName}")
            val argList = mutableListOf<String>()
            var cur = args[1]
            while (cur is Value.SumV && cur.case == "Cons") {
                val payload = cur.payload as? Value.ProductV
                    ?: throw IoFailure("process-spawn",
                        "Process.Spawn args list Cons payload not a ProductV (kind=${cur.payload?.let { it::class.simpleName }})")
                val head = payload.fields["head"] as? Value.StringV
                    ?: throw IoFailure("process-spawn",
                        "Process.Spawn args list head not StringV (kind=${payload.fields["head"]?.let { it::class.simpleName }})")
                argList += head.v
                cur = payload.fields["tail"]
                    ?: throw IoFailure("process-spawn", "Process.Spawn args list missing tail")
            }
            try {
                val builder = ProcessBuilder(listOf(cmd) + argList).inheritIO()
                val proc = builder.start()
                ResourceTable.register("process", proc)
            } catch (e: java.io.IOException) {
                throw IoFailure("process-spawn", "$cmd: ${e.message}")
            } catch (e: SecurityException) {
                throw IoFailure("process-spawn", "$cmd: ${e.message}")
            }
        },

        "strand-builtin:Process.Wait" to Fn { args ->
            // (handle: ProcessHandle) -> Int (exit code)
            require(args.size == 1) {
                "Process.Wait expects 1 arg (handle: ProcessHandle), got ${args.size}"
            }
            val handle = args[0] as? Value.Resource
                ?: throw IoFailure("process-wait", "expected Resource handle, got ${args[0]::class.simpleName}")
            val proc = ResourceTable.get(handle, "process") as Process
            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IoFailure("process-wait", "interrupted waiting for process #${handle.id}")
            }
            ResourceTable.remove(handle)
            Value.IntV(exitCode.toLong())
        },

        "strand-builtin:Process.EnvVar" to Fn { args ->
            // (name: String) -> Option<String>
            // Returns SumV "Some" payload=StringV when set, SumV "None"
            // payload=null when missing. Standard Option convention.
            require(args.size == 1) {
                "Process.EnvVar expects 1 arg (name: String), got ${args.size}"
            }
            val name = (args[0] as? Value.StringV)?.v
                ?: throw IoFailure("process-envvar", "expected StringV name, got ${args[0]::class.simpleName}")
            val value = System.getenv(name)
            if (value != null) Value.SumV(case = "Some", payload = Value.StringV(value))
            else Value.SumV(case = "None", payload = null)
        },

        // Layer 4 step 2 — String stdlib builtins. Pure (no declared
        // effects). All UTF-8 semantics; ParseInt/ParseFloat return
        // the standard Option<T> sum encoding (SumV "Some" / "None").

        "strand-builtin:String.Length" to Fn { args ->
            require(args.size == 1) { "String.Length expects 1 arg, got ${args.size}" }
            Value.IntV((args[0] as Value.StringV).v.length.toLong())
        },

        "strand-builtin:String.Substring" to Fn { args ->
            // (s: String, start: Int, end: Int) -> String
            // start inclusive, end exclusive. Clamped to [0, length].
            require(args.size == 3) { "String.Substring expects 3 args (s, start, end), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val start = (args[1] as Value.IntV).v.toInt().coerceIn(0, s.length)
            val end = (args[2] as Value.IntV).v.toInt().coerceIn(start, s.length)
            Value.StringV(s.substring(start, end))
        },

        "strand-builtin:String.IndexOf" to Fn { args ->
            // (haystack: String, needle: String) -> Int (-1 if not found)
            require(args.size == 2) { "String.IndexOf expects 2 args, got ${args.size}" }
            val haystack = (args[0] as Value.StringV).v
            val needle = (args[1] as Value.StringV).v
            Value.IntV(haystack.indexOf(needle).toLong())
        },

        "strand-builtin:String.Contains" to Fn { args ->
            require(args.size == 2) { "String.Contains expects 2 args, got ${args.size}" }
            Value.BoolV((args[0] as Value.StringV).v.contains((args[1] as Value.StringV).v))
        },

        "strand-builtin:String.Replace" to Fn { args ->
            // (s: String, find: String, replace: String) -> String. Literal, not regex.
            require(args.size == 3) { "String.Replace expects 3 args, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v
                .replace((args[1] as Value.StringV).v, (args[2] as Value.StringV).v))
        },

        "strand-builtin:String.Split" to Fn { args ->
            // (s: String, sep: String) -> List<String>
            // Returns the SumV-encoded Cons/Nil list. Empty separator
            // is rejected (would otherwise loop indefinitely).
            require(args.size == 2) { "String.Split expects 2 args, got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            val sep = (args[1] as Value.StringV).v
            require(sep.isNotEmpty()) { "String.Split separator must be non-empty" }
            val parts = s.split(sep)
            var listValue: Value = Value.SumV(case = "Nil", payload = null)
            for (part in parts.reversed()) {
                listValue = Value.SumV(case = "Cons", payload = Value.ProductV(
                    mapOf("head" to Value.StringV(part), "tail" to listValue)
                ))
            }
            listValue
        },

        "strand-builtin:String.Join" to Fn { args ->
            // (parts: List<String>, sep: String) -> String
            require(args.size == 2) { "String.Join expects 2 args, got ${args.size}" }
            val sep = (args[1] as Value.StringV).v
            val parts = mutableListOf<String>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                parts += (payload.fields.getValue("head") as Value.StringV).v
                cur = payload.fields.getValue("tail")
            }
            Value.StringV(parts.joinToString(sep))
        },

        "strand-builtin:String.ToUpper" to Fn { args ->
            require(args.size == 1) { "String.ToUpper expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.uppercase())
        },

        "strand-builtin:String.ToLower" to Fn { args ->
            require(args.size == 1) { "String.ToLower expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.lowercase())
        },

        "strand-builtin:String.Trim" to Fn { args ->
            require(args.size == 1) { "String.Trim expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.StringV).v.trim())
        },

        "strand-builtin:String.ParseInt" to Fn { args ->
            // (s: String) -> Option<Int>
            require(args.size == 1) { "String.ParseInt expects 1 arg, got ${args.size}" }
            val n = (args[0] as Value.StringV).v.toLongOrNull()
            if (n != null) Value.SumV("Some", Value.IntV(n))
            else Value.SumV("None", null)
        },

        "strand-builtin:String.ParseFloat" to Fn { args ->
            // (s: String) -> Option<Float>
            require(args.size == 1) { "String.ParseFloat expects 1 arg, got ${args.size}" }
            val n = (args[0] as Value.StringV).v.toDoubleOrNull()
            if (n != null) Value.SumV("Some", Value.FloatV(n))
            else Value.SumV("None", null)
        },

        "strand-builtin:String.FromInt" to Fn { args ->
            // (n: Int) -> String. Convenience for formatting.
            require(args.size == 1) { "String.FromInt expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.IntV).v.toString())
        },

        "strand-builtin:String.FromFloat" to Fn { args ->
            require(args.size == 1) { "String.FromFloat expects 1 arg, got ${args.size}" }
            Value.StringV((args[0] as Value.FloatV).v.toString())
        },

        "strand-builtin:String.FromBool" to Fn { args ->
            require(args.size == 1) { "String.FromBool expects 1 arg, got ${args.size}" }
            Value.StringV(if ((args[0] as Value.BoolV).v) "true" else "false")
        },

        // Layer 4 step 2 — Bytes stdlib builtins. Bytes are runtime-
        // opaque ByteArrays; these helpers cover the common
        // serialization tasks (length, slice, concat, UTF-8 round-trip,
        // base64).

        "strand-builtin:Bytes.Length" to Fn { args ->
            require(args.size == 1) { "Bytes.Length expects 1 arg, got ${args.size}" }
            Value.IntV((args[0] as Value.BytesV).v.size.toLong())
        },

        "strand-builtin:Bytes.Slice" to Fn { args ->
            // (b: Bytes, start: Int, end: Int) -> Bytes
            require(args.size == 3) { "Bytes.Slice expects 3 args, got ${args.size}" }
            val b = (args[0] as Value.BytesV).v
            val start = (args[1] as Value.IntV).v.toInt().coerceIn(0, b.size)
            val end = (args[2] as Value.IntV).v.toInt().coerceIn(start, b.size)
            Value.BytesV(b.copyOfRange(start, end))
        },

        "strand-builtin:Bytes.Concat" to Fn { args ->
            require(args.size == 2) { "Bytes.Concat expects 2 args, got ${args.size}" }
            val a = (args[0] as Value.BytesV).v
            val b = (args[1] as Value.BytesV).v
            Value.BytesV(a + b)
        },

        "strand-builtin:Bytes.ParseUtf8" to Fn { args ->
            // (b: Bytes) -> Option<String>. None on invalid UTF-8.
            require(args.size == 1) { "Bytes.ParseUtf8 expects 1 arg, got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                Value.SumV("Some", Value.StringV(text))
            } catch (_: java.nio.charset.CharacterCodingException) {
                Value.SumV("None", null)
            }
        },

        "strand-builtin:Bytes.FromUtf8" to Fn { args ->
            // (s: String) -> Bytes. UTF-8 encoding always succeeds.
            require(args.size == 1) { "Bytes.FromUtf8 expects 1 arg, got ${args.size}" }
            Value.BytesV((args[0] as Value.StringV).v.toByteArray(Charsets.UTF_8))
        },

        "strand-builtin:Bytes.FormatBase64" to Fn { args ->
            require(args.size == 1) { "Bytes.FormatBase64 expects 1 arg, got ${args.size}" }
            Value.StringV(java.util.Base64.getEncoder().encodeToString((args[0] as Value.BytesV).v))
        },

        "strand-builtin:Bytes.ParseBase64" to Fn { args ->
            // (s: String) -> Option<Bytes>. None on invalid base64.
            require(args.size == 1) { "Bytes.ParseBase64 expects 1 arg, got ${args.size}" }
            try {
                Value.SumV("Some", Value.BytesV(java.util.Base64.getDecoder().decode((args[0] as Value.StringV).v)))
            } catch (_: IllegalArgumentException) {
                Value.SumV("None", null)
            }
        },

        // Layer 4 step 2 (round 2 update) — Json.Parse. Parses any
        // valid JSON value (null, true, false, number, string, array,
        // object) into the full JsonValue sum encoding now that the
        // nested-μ blocker is lifted (Slice 3 of stdlib expansion
        // round 2, via the RecursiveSelf depth field).
        //
        // The blessed JsonValue type lives in corpus 66; the four
        // primitive cases match corpus 54's flat predecessor for
        // primitives — programs that only handle primitives continue
        // to work unchanged. Arrays and objects now produce real
        // recursive structure: JsonArray(List<JsonValue>) and
        // JsonObject(List<{key, value}>) using the canonical Cons/Nil
        // sum encoding.
        //
        // Returns Option<JsonValue>: Some on any valid JSON,
        // None on malformed input.

        "strand-builtin:Json.Parse" to Fn { args ->
            require(args.size == 1) { "Json.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v.trim()
            try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(s)
                val converted = jsonElementToValue(element)
                if (converted == null) Value.SumV("None", null)
                else Value.SumV("Some", converted)
            } catch (_: kotlinx.serialization.SerializationException) {
                Value.SumV("None", null)
            } catch (_: IllegalArgumentException) {
                Value.SumV("None", null)
            }
        },

        // Layer 4 step 2 — Markdown.Parse (Phase 4 #9). Stub for
        // symmetry: parses a Markdown source into the MarkdownDocument
        // sum encoding (corpus 61). The corpus encoding is a list of
        // MarkdownBlock variants (Heading, Paragraph, CodeBlock,
        // Quote); full block-level parsing is non-trivial and the
        // blessed library can already represent the result. For now,
        // wrap the entire input as a single Paragraph block. Programs
        // that need real parsing should use a proper markdown lib via
        // a future blessed-library binding.
        "strand-builtin:Markdown.Parse" to Fn { args ->
            require(args.size == 1) { "Markdown.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            // Build: Cons(Paragraph(s), Nil) as the document list.
            val paragraphBlock = Value.SumV("Paragraph", Value.StringV(s))
            val singleton = Value.SumV("Cons", Value.ProductV(mapOf(
                "head" to paragraphBlock,
                "tail" to Value.SumV("Nil", null),
            )))
            Value.SumV("Some", singleton)
        },

        // Stdlib expansion round 2 — Math.* builtins. Pure.
        // Int-typed (Abs/Sign/Min/Max/Mod) compose with the existing
        // Int arithmetic surface; Float-typed (Sqrt/Pow/Log/Exp/Sin/
        // Cos/Tan) are irreducibly real-valued; Floor/Ceil/Round take
        // a Float and return an Int. Math.Mod is the *always-positive*
        // mathematical modulo, distinct from Int.Mod (which follows
        // JVM `%` sign-of-dividend semantics).

        "strand-builtin:Math.Abs" to Fn { args ->
            require(args.size == 1) { "Math.Abs expects 1 arg (n: Int), got ${args.size}" }
            Value.IntV(kotlin.math.abs((args[0] as Value.IntV).v))
        },

        "strand-builtin:Math.Sign" to Fn { args ->
            // -1, 0, or 1 depending on the sign of the input.
            require(args.size == 1) { "Math.Sign expects 1 arg (n: Int), got ${args.size}" }
            val n = (args[0] as Value.IntV).v
            Value.IntV(when {
                n > 0 -> 1L
                n < 0 -> -1L
                else -> 0L
            })
        },

        "strand-builtin:Math.Min" to Fn { args ->
            require(args.size == 2) { "Math.Min expects 2 args (a, b: Int), got ${args.size}" }
            Value.IntV(kotlin.math.min((args[0] as Value.IntV).v, (args[1] as Value.IntV).v))
        },

        "strand-builtin:Math.Max" to Fn { args ->
            require(args.size == 2) { "Math.Max expects 2 args (a, b: Int), got ${args.size}" }
            Value.IntV(kotlin.math.max((args[0] as Value.IntV).v, (args[1] as Value.IntV).v))
        },

        "strand-builtin:Math.Mod" to Fn { args ->
            // True mathematical modulo: result has the sign of the
            // divisor (always non-negative for positive divisors).
            // Distinct from Int.Mod, which follows JVM `%` semantics.
            require(args.size == 2) { "Math.Mod expects 2 args (a, b: Int), got ${args.size}" }
            val a = (args[0] as Value.IntV).v
            val b = (args[1] as Value.IntV).v
            require(b != 0L) { "Math.Mod division by zero" }
            Value.IntV(((a % b) + b) % b)
        },

        "strand-builtin:Math.Floor" to Fn { args ->
            // (f: Float) -> Int. Largest Int <= f.
            require(args.size == 1) { "Math.Floor expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.floor((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Ceil" to Fn { args ->
            // (f: Float) -> Int. Smallest Int >= f.
            require(args.size == 1) { "Math.Ceil expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.ceil((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Round" to Fn { args ->
            // (f: Float) -> Int. Banker's rounding (round-half-to-even)
            // to avoid the asymmetric bias of round-half-up.
            require(args.size == 1) { "Math.Round expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV(kotlin.math.round((args[0] as Value.FloatV).v).toLong())
        },

        "strand-builtin:Math.Sqrt" to Fn { args ->
            // (f: Float) -> Float. NaN for negative inputs (matches IEEE 754).
            require(args.size == 1) { "Math.Sqrt expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.sqrt((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Pow" to Fn { args ->
            // (base: Float, exp: Float) -> Float.
            require(args.size == 2) { "Math.Pow expects 2 args (base, exp: Float), got ${args.size}" }
            Value.FloatV((args[0] as Value.FloatV).v.pow((args[1] as Value.FloatV).v))
        },

        "strand-builtin:Math.Log" to Fn { args ->
            // (f: Float) -> Float. Natural log. NaN for non-positive inputs.
            require(args.size == 1) { "Math.Log expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.ln((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Exp" to Fn { args ->
            // (f: Float) -> Float. e^f.
            require(args.size == 1) { "Math.Exp expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.exp((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Sin" to Fn { args ->
            require(args.size == 1) { "Math.Sin expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.sin((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Cos" to Fn { args ->
            require(args.size == 1) { "Math.Cos expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.cos((args[0] as Value.FloatV).v))
        },

        "strand-builtin:Math.Tan" to Fn { args ->
            require(args.size == 1) { "Math.Tan expects 1 arg (f: Float), got ${args.size}" }
            Value.FloatV(kotlin.math.tan((args[0] as Value.FloatV).v))
        },

        // Int <-> Float coercion helpers. Strand has no implicit
        // numeric coercion; these make Math.Sqrt(Float.FromInt(n))
        // ergonomic without round-tripping through String.
        "strand-builtin:Float.FromInt" to Fn { args ->
            require(args.size == 1) { "Float.FromInt expects 1 arg (n: Int), got ${args.size}" }
            Value.FloatV((args[0] as Value.IntV).v.toDouble())
        },

        "strand-builtin:Int.FromFloatTrunc" to Fn { args ->
            // Truncation toward zero (drops the fractional part).
            // Distinct from Math.Floor (which rounds toward -infinity).
            require(args.size == 1) { "Int.FromFloatTrunc expects 1 arg (f: Float), got ${args.size}" }
            Value.IntV((args[0] as Value.FloatV).v.toLong())
        },

        // Stdlib expansion round 2 — Hash.* builtins. Pure. All take
        // Bytes and return Bytes; programs that want hex output
        // compose with Bytes.FormatHex. Blake3 uses the same library
        // and prefix-free output as the project's content-addressing
        // hasher; Sha256 and Md5 use java.security.MessageDigest.

        "strand-builtin:Hash.Blake3" to Fn { args ->
            // (b: Bytes) -> Bytes (32-byte BLAKE3 digest, no multi-hash prefix)
            require(args.size == 1) { "Hash.Blake3 expects 1 arg (b: Bytes), got ${args.size}" }
            val hasher = io.github.rctcwyvrn.blake3.Blake3.newInstance()
            hasher.update((args[0] as Value.BytesV).v)
            Value.BytesV(hasher.digest())
        },

        "strand-builtin:Hash.Sha256" to Fn { args ->
            // (b: Bytes) -> Bytes (32-byte SHA-256 digest)
            require(args.size == 1) { "Hash.Sha256 expects 1 arg (b: Bytes), got ${args.size}" }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Value.BytesV(md.digest((args[0] as Value.BytesV).v))
        },

        "strand-builtin:Hash.Md5" to Fn { args ->
            // (b: Bytes) -> Bytes (16-byte MD5 digest). Not cryptographically
            // secure — included for integrity/identity use cases where SHA-256
            // is overkill (cache keys, content fingerprinting on trusted input).
            require(args.size == 1) { "Hash.Md5 expects 1 arg (b: Bytes), got ${args.size}" }
            val md = java.security.MessageDigest.getInstance("MD5")
            Value.BytesV(md.digest((args[0] as Value.BytesV).v))
        },

        // Stdlib expansion round 2 — List.* primitives (no lambdas).
        // Walk and produce the canonical Cons(head, tail) / Nil SumV
        // encoding (corpus 31/32, also produced by Fs.List, String.Split,
        // Process.Spawn arg lists). Polymorphic in the element type —
        // the runtime never inspects head values, so any Value type
        // works for the head. Empty-list construction is convenient
        // because Strand has no literal syntax for it (otherwise an
        // agent emits a 3-node Sum-construct boilerplate).
        //
        // Higher-order operations (Map, Filter, Fold, Find, Any, All)
        // are deferred to round 3, which needs the interpreter-callback
        // infrastructure for builtins to invoke Strand lambdas.

        "strand-builtin:List.Empty" to Fn { args ->
            require(args.isEmpty()) { "List.Empty expects 0 args, got ${args.size}" }
            Value.SumV("Nil", null)
        },

        "strand-builtin:List.IsEmpty" to Fn { args ->
            require(args.size == 1) { "List.IsEmpty expects 1 arg (list), got ${args.size}" }
            Value.BoolV(args[0] is Value.SumV && (args[0] as Value.SumV).case == "Nil")
        },

        "strand-builtin:List.Length" to Fn { args ->
            require(args.size == 1) { "List.Length expects 1 arg (list), got ${args.size}" }
            var n = 0L
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                n++
                cur = (sumV.payload as Value.ProductV).fields.getValue("tail")
            }
            Value.IntV(n)
        },

        "strand-builtin:List.Reverse" to Fn { args ->
            require(args.size == 1) { "List.Reverse expects 1 arg (list), got ${args.size}" }
            var result: Value = Value.SumV("Nil", null)
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to head, "tail" to result)))
                cur = payload.fields.getValue("tail")
            }
            result
        },

        "strand-builtin:List.Take" to Fn { args ->
            // (list, n: Int) -> list. Negative or zero n yields Nil.
            // n larger than list length yields the whole list.
            require(args.size == 2) { "List.Take expects 2 args (list, n: Int), got ${args.size}" }
            val take = (args[1] as Value.IntV).v
            if (take <= 0) return@Fn Value.SumV("Nil", null)
            val heads = mutableListOf<Value>()
            var cur: Value = args[0]
            var remaining = take
            while (remaining > 0) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                heads += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
                remaining--
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in heads.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Drop" to Fn { args ->
            // (list, n: Int) -> list. Negative or zero n yields the
            // original list. n larger than list length yields Nil.
            require(args.size == 2) { "List.Drop expects 2 args (list, n: Int), got ${args.size}" }
            var drop = (args[1] as Value.IntV).v
            var cur: Value = args[0]
            while (drop > 0) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                cur = (sumV.payload as Value.ProductV).fields.getValue("tail")
                drop--
            }
            cur
        },

        "strand-builtin:List.Concat" to Fn { args ->
            // (a, b) -> list. Append b to the end of a.
            require(args.size == 2) { "List.Concat expects 2 args (a, b), got ${args.size}" }
            val heads = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                heads += payload.fields.getValue("head")
                cur = payload.fields.getValue("tail")
            }
            var result: Value = args[1]
            for (h in heads.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Nth" to Fn { args ->
            // (list, i: Int) -> Option<T>. Some(elem) for in-range
            // 0-based index, None otherwise.
            require(args.size == 2) { "List.Nth expects 2 args (list, i: Int), got ${args.size}" }
            var i = (args[1] as Value.IntV).v
            if (i < 0) return@Fn Value.SumV("None", null)
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if (i == 0L) {
                    return@Fn Value.SumV("Some", payload.fields.getValue("head"))
                }
                cur = payload.fields.getValue("tail")
                i--
            }
            Value.SumV("None", null)
        },

        // Stdlib expansion round 2 — Json.Stringify. Inverse of
        // Json.Parse, handling all six JsonValue cases (the four
        // primitives plus JsonArray and JsonObject from the Slice-3
        // nested-μ JsonValue). Recurses through Cons/Nil chains
        // inside array/object payloads.

        "strand-builtin:Json.Stringify" to Fn { args ->
            require(args.size == 1) { "Json.Stringify expects 1 arg (json: JsonValue), got ${args.size}" }
            Value.StringV(jsonValueToText(args[0] as Value.SumV))
        },

        // Stdlib expansion round 2 — Bytes hex codecs (Phase 4 #2).
        // Mirrors Bytes.FormatBase64 / Bytes.ParseBase64. Lowercase
        // hex on output; case-insensitive on input. Round-trips byte
        // arrays of any length.

        "strand-builtin:Bytes.FormatHex" to Fn { args ->
            require(args.size == 1) { "Bytes.FormatHex expects 1 arg (b: Bytes), got ${args.size}" }
            val bytes = (args[0] as Value.BytesV).v
            Value.StringV(bytes.joinToString("") { "%02x".format(it) })
        },

        "strand-builtin:Bytes.ParseHex" to Fn { args ->
            // (s: String) -> Option<Bytes>. None on odd length or non-hex.
            require(args.size == 1) { "Bytes.ParseHex expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v
            if (s.length % 2 != 0) return@Fn Value.SumV("None", null)
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[i * 2], 16)
                val lo = Character.digit(s[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return@Fn Value.SumV("None", null)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            Value.SumV("Some", Value.BytesV(out))
        },

        // Stdlib expansion round 2 — Random.* builtins (Phase 5).
        // Effectful: declare E-024 Crypto.RandomBytes at the call
        // site. Reads from the active [random] (default SecureRandom).
        // Tests install a seeded java.util.Random for reproducibility.

        "strand-builtin:Random.Int" to Fn { args ->
            // (min: Int, max: Int) -> Int. Inclusive min, exclusive max.
            // Returns a uniformly-distributed Long in [min, max).
            require(args.size == 2) {
                "Random.Int expects 2 args (min: Int, max: Int), got ${args.size}"
            }
            val min = (args[0] as Value.IntV).v
            val max = (args[1] as Value.IntV).v
            require(max > min) { "Random.Int requires max > min, got min=$min max=$max" }
            val range = max - min
            val sample = if (range <= Int.MAX_VALUE.toLong()) {
                random.nextInt(range.toInt()).toLong()
            } else {
                // 64-bit range: use nextLong and modulo. Slight bias
                // for non-power-of-2 ranges but acceptable for any
                // realistic application range.
                val raw = random.nextLong()
                val unbiased = if (raw == Long.MIN_VALUE) 0L else kotlin.math.abs(raw)
                unbiased % range
            }
            Value.IntV(min + sample)
        },

        "strand-builtin:Random.Float" to Fn { args ->
            // () -> Float. Uniformly distributed in [0.0, 1.0).
            require(args.isEmpty()) { "Random.Float expects 0 args, got ${args.size}" }
            Value.FloatV(random.nextDouble())
        },

        "strand-builtin:Random.Bytes" to Fn { args ->
            // (n: Int) -> Bytes. Exactly n random bytes.
            require(args.size == 1) { "Random.Bytes expects 1 arg (n: Int), got ${args.size}" }
            val n = (args[0] as Value.IntV).v.toInt()
            require(n >= 0) { "Random.Bytes n must be non-negative, got $n" }
            val out = ByteArray(n)
            random.nextBytes(out)
            Value.BytesV(out)
        },

        // Stdlib expansion round 3 — Log.* / OS.* / System.Exit
        // diagnostic and host-environment builtins.
        // Effect categories E-032 Log.Write, E-033 OS.Read,
        // E-034 System.Exit per design/effects-and-capabilities.md.

        // Log.* writes to the host's log sink. Default sink is
        // System.err so log lines don't tangle with stdout-bound
        // program output; tests install a captured StringBuilder
        // via the injectable [logSink] for assertion.
        "strand-builtin:Log.Info" to Fn { args ->
            require(args.size == 1) { "Log.Info expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[INFO] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },
        "strand-builtin:Log.Warn" to Fn { args ->
            require(args.size == 1) { "Log.Warn expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[WARN] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },
        "strand-builtin:Log.Error" to Fn { args ->
            require(args.size == 1) { "Log.Error expects 1 arg (msg: String), got ${args.size}" }
            logSink.println("[ERROR] " + (args[0] as Value.StringV).v)
            Value.UnitV
        },

        // OS.* observes stable host-environment state. All return
        // String; injectable [osEnv] lets tests pin values.
        "strand-builtin:OS.Hostname" to Fn { args ->
            require(args.isEmpty()) { "OS.Hostname expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.hostname())
        },
        "strand-builtin:OS.Platform" to Fn { args ->
            require(args.isEmpty()) { "OS.Platform expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.platform())
        },
        "strand-builtin:OS.Cwd" to Fn { args ->
            require(args.isEmpty()) { "OS.Cwd expects 0 args, got ${args.size}" }
            Value.StringV(osEnv.cwd())
        },

        // System.Exit terminates the host process via
        // [exitHandler]. Default invokes kotlin.system.exitProcess;
        // tests install a captured handler that records the code
        // and throws SystemExitInvoked so the test framework sees it
        // without actually terminating the JVM.
        "strand-builtin:System.Exit" to Fn { args ->
            require(args.size == 1) { "System.Exit expects 1 arg (code: Int), got ${args.size}" }
            val code = (args[0] as Value.IntV).v.toInt()
            exitHandler.exit(code)
            // Production handler never returns; the test handler throws.
            // If a custom handler returns normally we still produce UnitV
            // so the evaluator can continue (mirrors a no-op in tests
            // that don't want termination semantics).
            Value.UnitV
        },

        // Test-only no-op effectful builtin. Returns IntV(0) for any
        // single StringV argument. Used by tests that want to exercise
        // the effect-handler / capability machinery without touching
        // real IO. Not for production use. Target name reserved under
        // `strand-builtin:Test.*`.
        "strand-builtin:Test.EffectfulNoOp" to Fn { args ->
            require(args.size == 1) { "Test.EffectfulNoOp expects 1 arg, got ${args.size}" }
            require(args[0] is Value.StringV) {
                "Test.EffectfulNoOp expects a StringV argument, got ${args[0]::class.simpleName}"
            }
            Value.IntV(0)
        },
    )

    /**
     * Higher-order builtins. Separate registry from the standard
     * [registry] so the dispatcher can branch on which lookup path
     * found the target. A given target name appears in at most one
     * registry — the lookups are checked in order (higher-order
     * first) by callers.
     *
     * Populated by Slice 2 of stdlib expansion round 2 (List.Map,
     * List.Filter, List.Fold, List.Find, List.Any, List.All).
     */
    private val higherOrderRegistry: Map<String, FnH> = mapOf(
        // Stdlib expansion round 2, Slice 2.2 — higher-order List ops.
        // Each takes the canonical Cons/Nil SumV-encoded list as the
        // first arg and a callable (Closure / FixpointFn / ForeignFn)
        // as the lambda. Lambda arity matches the operation
        // (Map/Filter/Find/Any/All take a 1-arg fn; Fold takes a 2-arg
        // fn over (accumulator, element)). The interpreter's
        // [ApplyFn] callback closes over the surrounding capability
        // context, so lambdas inherit the caller's effects.

        "strand-builtin:List.Map" to FnH { args, apply ->
            // (list, fn: A -> B) -> List<B>
            require(args.size == 2) { "List.Map expects 2 args (list, fn), got ${args.size}" }
            val fn = args[1]
            val transformed = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                transformed += apply.apply(fn, listOf(payload.fields.getValue("head")))
                cur = payload.fields.getValue("tail")
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in transformed.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Filter" to FnH { args, apply ->
            // (list, predicate: A -> Bool) -> List<A>
            require(args.size == 2) { "List.Filter expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            val kept = mutableListOf<Value>()
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                val include = apply.apply(pred, listOf(head)) as Value.BoolV
                if (include.v) kept += head
                cur = payload.fields.getValue("tail")
            }
            var result: Value = Value.SumV("Nil", null)
            for (h in kept.reversed()) {
                result = Value.SumV("Cons", Value.ProductV(mapOf("head" to h, "tail" to result)))
            }
            result
        },

        "strand-builtin:List.Fold" to FnH { args, apply ->
            // (list, init, fn: (acc, elem) -> acc) -> acc
            require(args.size == 3) { "List.Fold expects 3 args (list, init, fn), got ${args.size}" }
            val fn = args[2]
            var acc: Value = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                acc = apply.apply(fn, listOf(acc, head))
                cur = payload.fields.getValue("tail")
            }
            acc
        },

        "strand-builtin:List.Find" to FnH { args, apply ->
            // (list, predicate: A -> Bool) -> Option<A>
            require(args.size == 2) { "List.Find expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                val head = payload.fields.getValue("head")
                val match = apply.apply(pred, listOf(head)) as Value.BoolV
                if (match.v) return@FnH Value.SumV("Some", head)
                cur = payload.fields.getValue("tail")
            }
            Value.SumV("None", null)
        },

        "strand-builtin:List.Any" to FnH { args, apply ->
            // (list, predicate: A -> Bool) -> Bool. Short-circuits on first true.
            require(args.size == 2) { "List.Any expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if ((apply.apply(pred, listOf(payload.fields.getValue("head"))) as Value.BoolV).v) {
                    return@FnH Value.BoolV(true)
                }
                cur = payload.fields.getValue("tail")
            }
            Value.BoolV(false)
        },

        "strand-builtin:List.All" to FnH { args, apply ->
            // (list, predicate: A -> Bool) -> Bool. Short-circuits on first false.
            require(args.size == 2) { "List.All expects 2 args (list, predicate), got ${args.size}" }
            val pred = args[1]
            var cur: Value = args[0]
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "Cons") break
                val payload = sumV.payload as Value.ProductV
                if (!(apply.apply(pred, listOf(payload.fields.getValue("head"))) as Value.BoolV).v) {
                    return@FnH Value.BoolV(false)
                }
                cur = payload.fields.getValue("tail")
            }
            Value.BoolV(true)
        },
    )

    /**
     * Convert a kotlinx-serialization [kotlinx.serialization.json.JsonElement]
     * into the corresponding Strand `JsonValue` SumV encoding. Returns null
     * if a number primitive cannot be represented as a Long (the current
     * JsonNumber payload type) — the caller wraps the result as
     * `Some(...)` on success or `None` otherwise.
     *
     * The output shape matches the post-Slice-3 nested-μ JsonValue
     * (corpus 66). The encoding uses **spliced variants** rather than
     * separate inner-μ types: arrays produce a chain of
     * `JsonArrayCons(head, tail)` cells terminated by `JsonArrayNil`,
     * with `head` and `tail` both directly typed as `JsonValue`
     * (depth-0 RecursiveSelf to the single enclosing μ). Objects use
     * `JsonObjectCons(key, value, tail)` and `JsonObjectNil`
     * analogously. This sidesteps the value-construction constraint
     * that nested μ-types fail (the inner RT can't be resolved
     * standalone) while still producing readable JSON-shaped values.
     */
    private fun jsonElementToValue(element: kotlinx.serialization.json.JsonElement): Value? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull ->
                Value.SumV("JsonNull", null)
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (element.isString) {
                    Value.SumV("JsonString", Value.StringV(element.content))
                } else {
                    val b = element.content.lowercase()
                    when {
                        b == "true" -> Value.SumV("JsonBool", Value.BoolV(true))
                        b == "false" -> Value.SumV("JsonBool", Value.BoolV(false))
                        else -> {
                            val asLong = element.content.toLongOrNull() ?: return null
                            Value.SumV("JsonNumber", Value.IntV(asLong))
                        }
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                var chain: Value = Value.SumV("JsonArrayNil", null)
                for (entry in element.reversed()) {
                    val converted = jsonElementToValue(entry) ?: return null
                    chain = Value.SumV("JsonArrayCons", Value.ProductV(mapOf(
                        "head" to converted, "tail" to chain,
                    )))
                }
                chain
            }
            is kotlinx.serialization.json.JsonObject -> {
                var chain: Value = Value.SumV("JsonObjectNil", null)
                for ((key, value) in element.entries.reversed()) {
                    val convertedValue = jsonElementToValue(value) ?: return null
                    chain = Value.SumV("JsonObjectCons", Value.ProductV(mapOf(
                        "key" to Value.StringV(key),
                        "value" to convertedValue,
                        "tail" to chain,
                    )))
                }
                chain
            }
        }
    }

    /**
     * Render a Strand `JsonValue` SumV back to canonical JSON text.
     * Inverse of [jsonElementToValue]. Recurses through Cons/Nil
     * chains inside JsonArray / JsonObject payloads.
     */
    private fun jsonValueToText(v: Value.SumV): String = when (v.case) {
        "JsonNull" -> "null"
        "JsonBool" -> if ((v.payload as Value.BoolV).v) "true" else "false"
        "JsonNumber" -> (v.payload as Value.IntV).v.toString()
        "JsonString" -> {
            val s = (v.payload as Value.StringV).v
            kotlinx.serialization.json.JsonPrimitive(s).toString()
        }
        "JsonArrayCons", "JsonArrayNil" -> {
            val out = StringBuilder("[")
            var first = true
            var cur: Value = v
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "JsonArrayCons") break
                val payload = sumV.payload as Value.ProductV
                if (!first) out.append(",")
                first = false
                out.append(jsonValueToText(payload.fields.getValue("head") as Value.SumV))
                cur = payload.fields.getValue("tail")
            }
            out.append("]")
            out.toString()
        }
        "JsonObjectCons", "JsonObjectNil" -> {
            val out = StringBuilder("{")
            var first = true
            var cur: Value = v
            while (true) {
                val sumV = cur as? Value.SumV ?: break
                if (sumV.case != "JsonObjectCons") break
                val payload = sumV.payload as Value.ProductV
                if (!first) out.append(",")
                first = false
                val key = (payload.fields.getValue("key") as Value.StringV).v
                out.append(kotlinx.serialization.json.JsonPrimitive(key).toString())
                out.append(":")
                out.append(jsonValueToText(payload.fields.getValue("value") as Value.SumV))
                cur = payload.fields.getValue("tail")
            }
            out.append("}")
            out.toString()
        }
        else -> "null"
    }

    /** Look up a builtin by its target identifier; null if unknown. */
    fun lookup(target: String): Fn? = registry[target]

    /** Look up a higher-order builtin by target identifier; null if unknown. */
    fun lookupHigherOrder(target: String): FnH? = higherOrderRegistry[target]

    /** Snapshot of all registered target identifiers across both registries. */
    fun registeredTargets(): Set<String> = registry.keys + higherOrderRegistry.keys

    /**
     * A fixed Unix-millis timestamp returned by `strand-builtin:Time.Now`.
     * The exact value is arbitrary — chosen as the project's notional
     * "today" — and is stable across runs to support replay determinism.
     */
    const val FIXED_REPLAY_TIMESTAMP: Long = 1_780_000_000_000L
}
