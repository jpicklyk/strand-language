# Containment-demonstration tenant programs

Hand-authored Strand programs that stand in for agent submissions to the
untrusted-agent-program host demonstrated by
`runtime/src/test/.../ContainmentDemo.kt` and its companion
`ContainmentDemoTest`. The narrative is `evaluation/containment-demo.md`.

Each program is kept as Layer A source (`<name>.layer-a`) plus the canonical
dag-json it compiles to (`<name>.json`), so the artifact the host admits is the
content-addressed graph, not the human-facing projection. The JSON is produced
by the built CLI:

    cli/build/install/cli/bin/cli.bat author demo/programs/<name>.layer-a --emit-json > demo/programs/<name>.json

(Run `:cli:installDist` first if the distribution is stale.) The demo driver
loads the `.json` files from the classpath, so regenerating them is only needed
when the `.layer-a` source changes.

## Programs

- `benign-sum` — pure `40 + 2`; empty effect closure; admits under any policy
  and runs to `42`. Used by S4 (run-by-hash) and as the surviving co-tenant in S3.
- `overreach-projection-drift` — declares a write to `/workspace/out.txt` but
  the call argument is `/etc/shadow`; rejected at admission with
  `ProjectionMismatch`. Used by S1.
- `runtime-denial-write` — declares and writes `/tenant/secret.log`; verifies
  clean but is denied at runtime by a refinement check when the host grants a
  different path. Used by S3.
- `tenant-a-cross-read` / `tenant-b-cross-read` — mutually distrusting tenants,
  each attempting to read the other's workspace via a relative escape; denied by
  each tenant's own workspace sandbox. Used by S2.

The location `impl-kotlin/demo/` was chosen so the programs sit beside the
demonstration code that consumes them and stay out of the `corpus/` golden-hash
regression net (these are demonstration fixtures, not corpus conformance
programs).
