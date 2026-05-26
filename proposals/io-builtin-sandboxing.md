# I/O Builtin Sandboxing

**Document:** `proposals/io-builtin-sandboxing.md`
**Status:** Draft proposal
**Date:** 2026-05-26
**Concerns:** [`design/security-model.md`](../design/security-model.md), [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md), [`proposals/foreign-effect-projections.md`](foreign-effect-projections.md), [Q-006](../open-questions.md#Q-006), [Q-039](../open-questions.md#Q-039), [Q-041](../open-questions.md#Q-041), [`security-index.md`](../security-index.md) § Finding 3
**Scope:** Medium

This proposal closes the I/O sandboxing gap recorded as Finding 3 in the 2026-05-26 audit ([`security-index.md`](../security-index.md)). It adds a host-configured `SandboxPolicy` that mediates every `Fs.*`, `Net.Connect`, and `Http.Request` call at the foreign-call boundary, and redesigns the `Http.Request` binding so its (host, port) refinement values are positional arguments accessible to [Q-039](#Q-039)'s projection mechanism. Q-039 and Q-041 together form the foreign-call security boundary: Q-039 binds capability-check values to actual arguments; Q-041 independently constrains what those argument values are allowed to be.

## 1. Problem statement

The Layer 4 step 2 builtins wired real OS calls into the interpreter. Path arguments pass straight to `java.nio.file.Paths.get(path)` ([`Builtins.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) lines ~395–501); `Net.Connect` accepts any `(host, port)` pair (~line 506); `Http.Request` accepts any URL through `URI(urlStr).toURL().openConnection()` (~line 625). No path traversal check, no SSRF guard, no workspace root.

The threats are concrete. **Path traversal** — `../../../../etc/passwd` or `C:\Windows\System32\config\SAM` reaches the filesystem outside any intended working area. **SSRF against cloud metadata** — `Http.Request("GET", "http://169.254.169.254/latest/meta-data/iam/security-credentials/", _)` returns AWS instance-role credentials; `metadata.google.internal` and `169.254.169.254/metadata/v1/` expose GCP and Azure equivalents. **Loopback and RFC1918 scanning** — `Net.Connect("127.0.0.1", 6379)` reaches in-process Redis; `Net.Connect("10.0.0.1", 22)` probes the LAN. **Link-local and `file://`** — `169.254.0.0/16` and `file:///etc/shadow` reach further surface. **DNS rebinding** — `attacker.example.com` resolves to a public IP at policy-check time and to `127.0.0.1` at connect time, defeating naive resolve-then-check.

[Q-039](#Q-039) closes the EffectDecl-drift attack: once `Fs.Write` carries `effectProjections=[{Filesystem.Write, [ArgRef(0)]}]`, the capability check is provably against `arguments[0]`. But the capability set may grant wildcard refinements (the `--grant-all` demo mode, tests, permissive policies), in which case Q-039's structural binding still admits whatever path the agent supplies. And legacy ForeignNodes without `effectProjections` retain the original drift. Q-039 closes *one* attack class (capability spoofing); Q-041 closes the orthogonal class — the capability genuinely permits the operation but the runtime should still refuse because the argument names an out-of-policy resource. The two together form the foreign-call security boundary.

[`security-model.md`](../design/security-model.md) § Foreign binding trust enumerates four defences: signed provenance, reproducible builds, curated registry, and *runtime sandbox observation*. The last is the mechanism this proposal advances. The foreign-call boundary is structurally narrow — every effectful operation eventually reaches a `Fn.invoke` — which is the right place to enforce per-resource policy.

## 2. Prior art

- **Cloudflare Workers `fetch()` egress policy.** Outbound `fetch` from a Worker enforces a configurable allowlist of destinations; cloud-metadata endpoints and private ranges are blocked by default. Policy is host-configured, not script-controlled.
- **AWS Lambda VPC egress filtering.** A Lambda placed in a VPC inherits the VPC's network ACLs and security groups; metadata-service access requires explicit IAM and a NAT path. Filter is below the language, at the network layer.
- **Docker `--no-new-privileges` + seccomp profiles.** Kernel allowlist of permitted syscalls; the default `seccomp` profile blocks ~50 (mount, ptrace, kexec, etc.). Argument-aware filtering at the syscall boundary.
- **Deno `--allow-net`, `--allow-read`, `--allow-write`.** Granular permission flags name specific hosts and paths the script may touch (`--allow-net=api.openai.com` is narrower than `--allow-net`). Default-deny without flags.
- **Java `SecurityManager` (deprecated, removed in JDK 24).** Per-permission policy file (`FilePermission`, `SocketPermission`). Deprecation reasons (API complexity, perf overhead, incomplete native coverage) are instructive — per-resource grants are the right shape; per-stack-frame check semantics are not.
- **OWASP SSRF Prevention Cheat Sheet.** Default IP blocklist (RFC1918, loopback, link-local, multicast, broadcast, IPv6 ULA `fc00::/7`), URL-allowlist by hostname, DNS-pinning to prevent rebinding.

Cross-cut: every system with a security story for outbound I/O either filters below the language (network/kernel) or maintains per-resource policy at the language runtime. Strand's foreign-call boundary is the right hook for the second — and is naturally argument-aware, which seccomp-bpf must reconstruct from raw syscall integers.

## 3. Recommended approach

Add a `SandboxPolicy` host-configured object in the runtime context, sibling to `CapabilitySet`:

```kotlin
data class SandboxPolicy(val fs: FsPolicy, val net: NetPolicy)

data class FsPolicy(
    val workspaceRoot: java.nio.file.Path?,   // null = no workspace constraint
    val escape: EscapePolicy,                 // Deny by default
    val followSymlinks: Boolean,              // false by default
)
enum class EscapePolicy { Allow, Deny }

data class NetPolicy(
    val defaultDeny: Boolean,                 // true by default
    val allowedHosts: List<HostPattern>,      // globs like "*.example.com"
    val blockedRanges: List<IpRange>,         // RFC1918, loopback, link-local, ULA, multicast, broadcast, cloud-metadata
    val dnsPolicy: DnsPolicy,                 // PinAtCheck by default
)
enum class DnsPolicy { PinAtCheck, RecheckAtConnect, RequireIpLiteral }
```

Enforcement happens at the foreign-call boundary, inside the relevant builtin, after Q-039's capability check and before the OS call. Failure mode is a new `InterpretError.SandboxViolation` variant carrying enough detail for an agent to learn what was rejected and retry within policy.

| # | Decision | Recommendation |
|---|----------|----------------|
| D1 | Where does policy live? | Singleton `Builtins.sandboxPolicy` (mirrors `clock`, `credentialProvider`, `verifierNodeTypes`); set by the interpreter at program startup. |
| D2 | When is policy checked? | Inside each I/O builtin's `Fn`, after argument unpacking, before the OS call. Independent of Q-039's projection check (which fires earlier). |
| D3 | Default disposition | Default-secure: filesystem requires a `workspaceRoot` (unset denies file I/O); network defaults deny on loopback, RFC1918, link-local, multicast, broadcast, IPv6 ULA, cloud metadata. |
| D4 | DNS rebinding mitigation | `DnsPolicy.PinAtCheck` (default): resolve once, pass the resolved IP to the JVM connect call. `RecheckAtConnect` and `RequireIpLiteral` for stricter workloads. |
| D5 | `Http.Request` binding shape | Split into `(host, port, scheme, path, method, headers, body)`. Legacy single-URL form preserved as a wrapper that pre-parses and dispatches. The split lets Q-039 bind host/port via `ArgRef(0)`/`ArgRef(1)`. |
| D6 | New verifier rules | None. Sandboxing is runtime policy, not a graph property. |
| D7 | New error variant | `InterpretError.SandboxViolation(at, kind, detail)`. Distinct from `IoFailure` (OS-level) and `CapabilityViolation` / `RefinementViolation` (capability denials). |

## 4. Detailed mechanism

### 4.1 Filesystem path sandboxing

Each `Fs.*` builtin (`Read`, `Write`, `Append`, `Exists`, `Delete`, `List`) calls into `FsSandbox.resolve(policy: FsPolicy, supplied: String): Path` before invoking the JVM:

1. If `policy.workspaceRoot` is `null`, return `Paths.get(supplied)` — the host has opted out of fs sandboxing.
2. Construct `candidate = policy.workspaceRoot.resolve(supplied)`. (`resolve` against an absolute `supplied` discards `workspaceRoot`, which the next step catches.)
3. Compute the canonical form: `candidate.toRealPath(NOFOLLOW_LINKS)` if `followSymlinks=false`, else `candidate.toRealPath()`. On a non-existent target, fall back to `candidate.normalize().toAbsolutePath()`.
4. If the canonical form is not lexically prefixed by `policy.workspaceRoot.toRealPath()`, the path escapes. `escape == Deny` raises `SandboxViolation(FsPathEscape)`; `Allow` logs and proceeds.
5. If `followSymlinks=false` and any component is a symlink, raise `SandboxViolation(FsSymlinkRejected)`.
6. Return the canonical path. The per-builtin diff is mechanical: `Files.write(Paths.get(path), bytes)` becomes `Files.write(FsSandbox.resolve(policy.fs, path), bytes)`.

### 4.2 Network host filtering

`Net.Connect(host, port)` runs through `NetSandbox.checkConnect(policy.net, host, port): InetAddress`:

1. Determine whether `host` is an IP literal or a hostname.
2. If hostname and `dnsPolicy == RequireIpLiteral`, raise `SandboxViolation(NetHostnameRejected)`. Otherwise resolve via `InetAddress.getAllByName(host)` and apply the IP-range check to *every* returned address — if any is blocked, deny (multi-A-record SSRF defence).
3. If IP literal, decode directly.
4. IP-range check rejects on any `blockedRange`. Default `blockedRanges`: IPv4 loopback `127.0.0.0/8`, IPv6 loopback `::1/128`, RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local IPv4 `169.254.0.0/16`, link-local IPv6 `fe80::/10`, IPv6 ULA `fc00::/7`, multicast (`224.0.0.0/4`, `ff00::/8`), IPv4 broadcast `255.255.255.255/32`, and cloud-metadata `169.254.169.254/32` (AWS/GCP/Azure IMDS), `169.254.170.2/32` (AWS ECS task-role), `100.100.100.200/32` (Alibaba).
5. Hostname allowlist: if `allowedHosts` is non-empty, the *original* `host` argument (not the resolved IP) must match at least one glob. Pre-resolution for human-readable policy; the IP check at step 4 defeats rebinding.
6. Return the resolved `InetAddress`. The builtin uses `Socket(resolvedAddress, port)` — passing the IP literal, not the hostname — so connect goes to the IP that policy approved (DNS pin-at-check).

`dnsPolicy == RecheckAtConnect` additionally re-resolves after the socket opens; mismatch with the pinned set raises `SandboxViolation(NetDnsRebindingDetected)`.

### 4.3 `Http.Request` binding redesign

Today's signature `Http.Request(method: String, url: String, body: Bytes) -> {status: Int, body: Bytes}` embeds (host, port) inside the URL string — invisible to Q-039's projection vocabulary, which only sees positional arguments. Two options:

**Option A — split into components.** New canonical signature:

```
Http.Request(
    host: String, port: Int, scheme: String, path: String,
    method: String, headers: List<Header>, body: Bytes,
) -> {status: Int, body: Bytes, headers: List<Header>}
```

with projection `effectProjections=[{Network.Connect, [ArgRef(0), ArgRef(1)]}, {Network.Send, [ArgRef(0), ArgRef(1)]}, {Network.Receive, [ArgRef(0), ArgRef(1)]}]`. Refinement values are positional arguments; Q-039's machinery applies unchanged. The seccomp / WIT pattern — name your arguments, filter on them.

**Option B — extend Q-039 with derived sources.** Add `HostOfUrl(arg: Int)` and `PortOfUrl(arg: Int)` to `ProjectionSource`, encoding the URL-parse step as canonical projection metadata. Keeps the legacy single-URL signature.

**Recommendation: Option A.** (1) Cleaner projection vocabulary — Q-039 restricted sources to `ArgRef` and `LiteralNode` (§ 4 D2); parsed-URL sources open a slippery slope (`PathOfUrl`, `SchemeOfUrl`, `QueryParamOfUrl(arg, name)`) and force runtime/verifier to mirror a URL parser exactly. (2) Pattern alignment with seccomp / WIT — both require the security-relevant value to be a named function parameter. (3) Sandbox check is simpler — no URL re-parse, no edge cases (userinfo, IDN, percent-encoding, IPv6 `[::1]:80`). (4) Headers gain first-class shape — the current binding drops response headers.

A legacy wrapper `Http.RequestFromUrl(method, url, body)` is preserved for backward compatibility; it parses host-side and dispatches to the split binding so the sandbox runs uniformly. The wrapper has no projection of its own and inherits Q-039's URL-opacity gap until programs migrate. System-prompt update recommends the split form as canonical.

### 4.4 SandboxPolicy threading

`Builtins.sandboxPolicy: SandboxPolicy` is a `@Volatile` field on the `Builtins` singleton, set by the interpreter at program startup and cleared at completion. Mirrors how `credentialProvider`, `llmHttpClient`, `clock`, and `random` are wired today ([`Builtins.kt`](../impl-kotlin/interpreter/src/main/kotlin/org/strand/interpreter/Builtins.kt) lines ~90–230). Per-`Fn` lookup cost is one volatile read. A future refactor could thread the policy explicitly through `applyForeign` alongside `CapabilitySet` and `List<ActiveHandler>`; flagged as non-blocking cleanup.

CLI flags on `strand run`, `strand machine`, `strand group`:

```
--workspace-root <path>       # FsPolicy.workspaceRoot
--allow-fs-escape             # FsPolicy.escape = Allow
--allow-host <pattern>        # appended to NetPolicy.allowedHosts (repeatable)
--allow-net-internal          # NetPolicy.defaultDeny = false, no blocked ranges
```

Default (no flags): `FsPolicy(workspaceRoot = cwd, escape = Deny, followSymlinks = false)`, `NetPolicy(defaultDeny = true, allowedHosts = [], blockedRanges = SECURE_DEFAULT, dnsPolicy = PinAtCheck)`. The `--grant-all` debug flag does *not* relax the sandbox — capability grants and sandbox policy are orthogonal.

### 4.5 Cloud-metadata blocklist (literal endpoints)

`NetPolicy.blockedRanges` ships hard-coded entries: `169.254.169.254/32` (AWS EC2 / GCP / Azure IMDS), `169.254.170.2/32` (AWS ECS task-role), `100.100.100.200/32` (Alibaba), and the corresponding hostname blocklist (`metadata.google.internal`, `metadata.azure.com`, bare `metadata`). The two checks compose: hostname blocklist denies *any* DNS query to a blocked name (an agent that knows the hostname but not the IP); IP-range blocklist denies *any* connect to a blocked address regardless of how it was named.

## 5. Verifier rules

**None.** Sandboxing is runtime policy, not a graph property. The graph never knows whether it runs under workspace-rooted or full-fs policy; nothing in the canonical encoding records it. The verifier ensures every effect declaration is consistent; the sandbox enforces policy on top.

The separation is deliberate. Per [ADR-005](../decisions/ADR-005-foreign-nodes.md)'s trust model, the foreign call site is the trust boundary; the verifier reasons about graph topology and effect closure; the runtime reasons about per-call resource policy. The layers compose: an agent that crafts a path-traversal payload runs into the verifier (must declare `Filesystem.Write` and obtain a grant), then Q-039's projection check (path argument must be the capability-check value), then Q-041's sandbox check (path argument must resolve inside workspace). All three must pass.

## 6. Interpreter / runtime semantics

The new check at `Fs.Write` (replacing `Builtins.kt` line ~395):

```kotlin
"strand-builtin:Fs.Write" to Fn { args ->
    val pathArg = (args[0] as Value.StringV).v
    val bytes   = (args[1] as Value.BytesV).v
    val resolved = FsSandbox.resolve(Builtins.sandboxPolicy.fs, pathArg)
    try {
        java.nio.file.Files.write(resolved, bytes)
        Value.IntV(bytes.size.toLong())
    } catch (e: java.io.IOException) {
        throw IoFailure("filesystem-write", "${resolved}: ${e.message}")
    }
}
```

At `Net.Connect`:

```kotlin
"strand-builtin:Net.Connect" to Fn { args ->
    val host = (args[0] as Value.StringV).v
    val port = (args[1] as Value.IntV).v.toInt()
    val resolved = NetSandbox.checkConnect(Builtins.sandboxPolicy.net, host, port)
    val socket = java.net.Socket(resolved, port)
    ResourceTable.register("socket", socket)
}
```

At the new `Http.Request`:

```kotlin
"strand-builtin:Http.Request" to Fn { args ->
    val host    = (args[0] as Value.StringV).v
    val port    = (args[1] as Value.IntV).v.toInt()
    val scheme  = (args[2] as Value.StringV).v
    val path    = (args[3] as Value.StringV).v
    val method  = (args[4] as Value.StringV).v
    val headers = decodeHeaderList(args[5])
    val body    = (args[6] as Value.BytesV).v
    if (scheme !in setOf("http", "https"))
        throw SandboxViolation(HttpSchemeRejected, "$scheme://...")
    val resolved = NetSandbox.checkConnect(Builtins.sandboxPolicy.net, host, port)
    val url = java.net.URI(scheme, null, resolved.hostAddress, port, path, null, null).toURL()
    // ... existing HttpURLConnection flow with explicit headers ...
}
```

`SandboxViolation` propagates like `IoFailure`: caught at the two `applyForeign` sites in `Interpreter.kt` and translated to `InterpretError.SandboxViolation(at = callSite, kind, detail)`. The new error and enum:

```kotlin
data class SandboxViolation(
    override val at: NodeId,
    val kind: SandboxViolationKind,
    val detail: String,
) : InterpretError()

enum class SandboxViolationKind {
    FsPathEscape, FsSymlinkRejected, FsWorkspaceNotConfigured,
    NetHostBlocked, NetHostNotAllowlisted, NetHostnameRejected,
    NetDnsRebindingDetected, HttpSchemeRejected,
}
```

## 7. Test scenarios

1. **Happy-path filesystem write within workspace.** Policy `FsPolicy(workspaceRoot = "/work", escape = Deny)`. `Fs.Write("data/log.txt", bytes)` writes to `/work/data/log.txt`. Expected: success.
2. **Path traversal via `..` rejected.** Same policy. `Fs.Write("../../etc/passwd", bytes)`. Expected: `SandboxViolation(FsPathEscape)`.
3. **Absolute path outside workspace rejected.** Same policy. `Fs.Write("/etc/passwd", bytes)`. Expected: `SandboxViolation(FsPathEscape)`.
4. **Symlink escape rejected.** Workspace `/work` contains symlink `/work/link → /etc`. `followSymlinks=false`. `Fs.Read("link/passwd")`. Expected: `SandboxViolation(FsSymlinkRejected)`.
5. **SSRF to AWS metadata rejected by default.** `NetPolicy.SECURE_DEFAULT`. `Http.Request(host="169.254.169.254", port=80, scheme="http", path="/latest/meta-data/", ...)`. Expected: `SandboxViolation(NetHostBlocked)`.
6. **SSRF to `metadata.google.internal` rejected.** Same policy. Expected: `SandboxViolation(NetHostBlocked)` — hostname is in the default blocklist regardless of resolution.
7. **Loopback rejected.** `Net.Connect("127.0.0.1", 6379)`. Expected: `SandboxViolation(NetHostBlocked)`.
8. **Allowlisted public host accepted.** `NetPolicy(allowedHosts = ["api.openai.com"], blockedRanges = SECURE_DEFAULT)`. Expected: success — the resolved public IP is not in any blocked range.
9. **Allowlisted hostname rebinding to internal IP.** `allowedHosts = ["evil.example.com"]`, `dnsPolicy = PinAtCheck`; `evil.example.com` resolves to `10.0.0.1`. Expected: `SandboxViolation(NetHostBlocked)` — IP-range check fires post-resolution. With `RecheckAtConnect`: rejected if a second resolution diverges.
10. **Legacy `Http.RequestFromUrl` still works.** `Http.RequestFromUrl("GET", "https://api.example.com/v1/users", empty)`. The wrapper parses to `(api.example.com, 443, https, /v1/users, GET, [], empty)` and dispatches. Expected: success if allowlisted; otherwise `NetHostNotAllowlisted` from the underlying check.
11. **New component-style `Http.Request` projects correctly under Q-039.** Ships `effectProjections=[{Network.Connect, [ArgRef(0), ArgRef(1)]}, ...]`; caller's `effectInstances` parameters are the same NodeIds as `arguments[0]` and `arguments[1]`. Expected: verifier accepts; runtime synthesises capability-check values from projection; sandbox validates the resolved IP.
12. **`file://` scheme rejected.** `Http.Request(scheme="file", path="/etc/shadow", ...)`. Expected: `SandboxViolation(HttpSchemeRejected)`.

## 8. Tradeoffs and open questions

**Deferred intentionally:**

- **Per-process OS isolation.** WebAssembly bindings (Milestone 2.4, Q-006), seccomp for native foreign code, AppArmor / SELinux, container constraints. Stronger defences than this proposal's language-level policy; they compose with it.
- **Per-connection rate limiting.** A `NetPolicy.maxRequestsPerSecond` knob; deferred alongside Q-040's resource-limit work.
- **Egress monitoring and telemetry.** A `NetPolicy.observe: (Destination, Result) -> Unit` callback for structured audit logs. Deferred — operationally useful but orthogonal to the security property.
- **Policy as a graph-level node.** A `SandboxScope` analogous to `CapabilityScope`. Rejected for V1: graph-level policy is *ambient authority* (the graph can lie about what policy applies), precisely what the trust model rules out. Policy is host-configured.
- **Hostname IDN canonicalisation.** Punycode normalisation for allowlist matching. Default glob matches the literal `host` argument; admits IDN bypass. Flagged for follow-up.

**Real research questions:**

- *Performance cost of canonical-path resolution.* `Path.toRealPath` is one syscall per call site (~50µs in casual JVM benchmarks). Tight `Fs.Read` loops will observe the cost. A future cache keyed on `(workspaceRoot, suppliedPath)` with invalidation on `Fs.Write` / `Fs.Delete` to the same prefix is the optimisation. V1 ships without it.
- *DNS rebinding correctness across timing windows.* `PinAtCheck` is not airtight — a determined attacker can rebind between `getAllByName` and `Socket(InetAddress, port)` (microsecond window). `RecheckAtConnect` closes this with per-call cost; `RequireIpLiteral` is strongest but breaks friendly-hostname use. Default selection is an operational call.
- *Hostname-vs-IP edge cases.* IPv6 zone identifiers (`fe80::1%eth0`), IPv4-mapped IPv6 (`::ffff:127.0.0.1`), bracketed IPv6 literals in URLs. Recommend `InetAddress.getByName` semantics; per-case behaviour needs a clarifying test pass.
- *Legacy `Http.RequestFromUrl` — warn or silent?* The wrapper inherits Q-039's URL-opacity gap. Recommend silent in V1; deprecation-tracked for a verifier-warning follow-up once the corpus has migrated.

## 9. Implementation sketch

| File | Change | Size |
|------|--------|------|
| `interpreter/SandboxPolicy.kt` | New file. `SandboxPolicy`, `FsPolicy`, `NetPolicy`, `EscapePolicy`, `DnsPolicy`, `HostPattern` (glob), `IpRange` (CIDR), `FsSandbox.resolve`, `NetSandbox.checkConnect`, `SECURE_DEFAULT` constants. | Medium |
| `interpreter/InterpretError.kt` | New `SandboxViolation(at, kind, detail)` + `SandboxViolationKind` enum. | Small |
| `interpreter/Builtins.kt` | Add `@Volatile var sandboxPolicy`. Rewrite `Fs.Read`/`Write`/`Append`/`Exists`/`Delete`/`List` to use `FsSandbox.resolve`. Rewrite `Net.Connect` to pass `resolvedAddress` to `Socket`. Replace `Http.Request` with seven-arg split binding; add `Http.RequestFromUrl` legacy wrapper. Add `effectProjections` on new bindings (Q-039 alignment). | Medium |
| `interpreter/Interpreter.kt` | Catch `SandboxViolation` at the two `applyForeign` sites alongside `IoFailure`; translate to `InterpretError.SandboxViolation`. | Small |
| `cli/Main.kt` | Add `--workspace-root`, `--allow-fs-escape`, `--allow-host`, `--allow-net-internal` flags; parse to `SandboxPolicy`; install on `Builtins.sandboxPolicy` before `Interpreter.eval`. | Small-medium |
| `interpreter/test/SandboxPolicyTest.kt` | New file. 12 unit tests from § 7. | Medium |
| `corpus/` | Two new negative corpus programs: 69 (`Fs.Write` of `../escape` rejected) and 70 (`Http.Request` to `169.254.169.254` rejected). | Small |
| `evaluation/dynamic/prompts/strand-system.md` | Document new `Http.Request` split signature as canonical; note `Http.RequestFromUrl` legacy. | Small |

**Order of work.** (1) `SandboxPolicy.kt` skeleton + `SECURE_DEFAULT` + unit tests. (2) `FsSandbox.resolve` + tests. (3) `NetSandbox.checkConnect` + tests. (4) Migrate `Fs.*` builtins. (5) Migrate `Net.Connect`. (6) Migrate `Http.Request` to split form; add `Http.RequestFromUrl` legacy wrapper. (7) Add Q-039 `effectProjections` to new bindings (depends on Q-039 landed). (8) CLI flags. (9) Corpus 69–70. (10) System-prompt update.

**Not in this slice.** Wasm-sandboxed bindings (Q-006, Milestone 2.4); seccomp / AppArmor / SELinux; per-connection rate limiting and egress observability hooks; hostname IDN canonicalisation; deprecation warning for `Http.RequestFromUrl`; Lambda-level projection of sandbox checks (sandboxing is runtime policy, not a graph property; no Lambda interaction).

## References

**Outgoing references:**
- [`design/security-model.md`](../design/security-model.md) — § Foreign binding trust (the trust-model layer this proposal advances), § Threat model (malicious AI agent, compromised foreign code)
- [`decisions/ADR-005-foreign-nodes.md`](../decisions/ADR-005-foreign-nodes.md) — ForeignNode as the security boundary; runtime sandbox observation as one of the four trust mechanisms
- [`proposals/foreign-effect-projections.md`](foreign-effect-projections.md) — Q-039, the defence-in-depth partner; the `Http.Request` redesign is enabled by Q-039's projection vocabulary
- [`security-index.md`](../security-index.md) — § Finding 3, the audit motivation
- [`open-questions.md`](../open-questions.md) — Q-006, Q-039, Q-041

**Incoming references:**
- [`open-questions.md`](../open-questions.md) — Q-041 points at this proposal
- [`proposals/README.md`](README.md)
- [`security-index.md`](../security-index.md) — Q-041 row links here
- [`impl-kotlin/CLAUDE.md`](../impl-kotlin/CLAUDE.md) — Known gaps section
