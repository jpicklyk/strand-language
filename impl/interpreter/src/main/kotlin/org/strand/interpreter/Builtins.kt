package org.strand.interpreter

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

        // Layer 4 step 2 — Json.Parse (Phase 4 #9). Parses a JSON
        // primitive (null, true, false, number, string) into the
        // standard JsonValue sum encoding from the blessed library
        // (corpus 54: JsonNull | JsonBool(Bool) | JsonNumber(Int) |
        // JsonString(String)). Arrays and objects are not currently
        // supported by the JsonValue blessed library because of
        // nested-μ binder limitations — they return Some(JsonNull) as
        // a degraded fallback so the parser never throws on syntactically
        // valid JSON; tests that need arrays/objects should wait for
        // the nested-μ work.
        //
        // Returns Option<JsonValue>: Some on a valid JSON primitive,
        // None on malformed input.

        "strand-builtin:Json.Parse" to Fn { args ->
            require(args.size == 1) { "Json.Parse expects 1 arg (s: String), got ${args.size}" }
            val s = (args[0] as Value.StringV).v.trim()
            try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(s)
                val jsonValue: Value = when (element) {
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
                                    val asLong = element.content.toLongOrNull()
                                    if (asLong != null) Value.SumV("JsonNumber", Value.IntV(asLong))
                                    else return@Fn Value.SumV("None", null)
                                }
                            }
                        }
                    }
                    // Arrays + objects: degrade to JsonNull. The JsonValue
                    // blessed library has no recursive cases for them, so
                    // there's no faithful encoding to produce. Returning
                    // JsonNull rather than None signals "parsed valid JSON,
                    // but structure not representable" — a future
                    // nested-μ JsonArray/JsonObject expansion will replace
                    // this with the proper recursive encoding.
                    else -> Value.SumV("JsonNull", null)
                }
                Value.SumV("Some", jsonValue)
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

    /** Look up a builtin by its target identifier; null if unknown. */
    fun lookup(target: String): Fn? = registry[target]

    /** Snapshot of all registered target identifiers. */
    fun registeredTargets(): Set<String> = registry.keys

    /**
     * A fixed Unix-millis timestamp returned by `strand-builtin:Time.Now`.
     * The exact value is arbitrary — chosen as the project's notional
     * "today" — and is stable across runs to support replay determinism.
     */
    const val FIXED_REPLAY_TIMESTAMP: Long = 1_780_000_000_000L
}
