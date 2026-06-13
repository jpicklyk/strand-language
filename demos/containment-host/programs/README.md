# Containment-demonstration tenant programs

Hand-authored Strand programs that stand in for agent submissions to the
untrusted-agent-program host demonstrated by the `:runtime` test-source-set
driver `ContainmentDemo.kt`
(`impl-kotlin/runtime/src/test/kotlin/org/strand/runtime/`) and its companion
`ContainmentDemoTest`. The narrative is the parent [`README.md`](../README.md).

Each program is kept as Layer A source (`<name>.layer-a`) plus the canonical
dag-json it compiles to (`<name>.json`), so the artifact the host admits is the
content-addressed graph, not the human-facing projection. The JSON is produced
by the built CLI, run from `impl-kotlin/`:

    cli/build/install/cli/bin/cli.bat author ../demos/containment-host/programs/<name>.layer-a --emit-json > ../demos/containment-host/programs/<name>.json

(Run `:cli:installDist` first if the distribution is stale.) The demo driver
loads the `.json` files from the test classpath, so regenerating them is only
needed when the `.layer-a` source changes.

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

These are demonstration fixtures, not corpus conformance programs, so they sit
under the top-level `demos/` tree rather than in `corpus/` and stay out of the
golden-hash regression net. The `:runtime` build copies them onto the test
classpath so the driver and its assertion test load them without a fragile
working-directory dependency.
