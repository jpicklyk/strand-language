"""Conventional-baseline containment probes for the Q-044 measurement.

Each probe exhibits, in stock Python 3, the default behavior of a harm class
that Strand contains structurally (see evaluation/containment-results.md). The
point is not that Python *can* be sandboxed with additional libraries or OS
mechanisms, but that the language as an AI-generation target provides no
containment layer by default: the harmful operation reaches the OS unchecked.

Probes are safe and deterministic. Network and destructive operations are
demonstrated by showing that no policy layer exists to reject the request
(the request object constructs and is dispatched to the OS), never by causing
real external effects. Run with: python python_baseline_probes.py
"""

import os
import sys
import tempfile
import urllib.request


def probe_path_traversal() -> str:
    """A path argument escapes its intended workspace with no confinement.

    Strand counterpart: corpus 74 (FsSandbox rejects FsPathEscape).
    """
    with tempfile.TemporaryDirectory() as root:
        workspace = os.path.join(root, "workspace")
        os.makedirs(workspace)
        secret = os.path.join(root, "secret.txt")
        with open(secret, "w") as f:
            f.write("OUT-OF-WORKSPACE-SECRET")
        # An agent-supplied "filename" within the workspace.
        agent_supplied = os.path.join(workspace, "..", "secret.txt")
        with open(agent_supplied) as f:
            leaked = f.read()
        escaped = os.path.realpath(agent_supplied).startswith(
            os.path.realpath(workspace)
        )
        return (
            f"open() read '{leaked}' via a path that resolves OUTSIDE the "
            f"workspace (escaped={not escaped}); no workspace confinement fired."
        )


def probe_resource_exhaustion() -> str:
    """Unbounded recursion crashes the host with an exception, not a
    recoverable structured error; there is no step or wall-clock budget.

    Strand counterpart: corpus 71 (Fixpoint no base case -> ResourceExhaustion).
    """
    def loop(n: int) -> int:
        return loop(n + 1)

    try:
        loop(0)
        return "unreachable"
    except RecursionError:
        return (
            "unbounded recursion raised RecursionError (host interpreter limit, "
            "not a language-level recoverable budget); a 'while True' instead "
            "hangs the process indefinitely with no wall-clock ceiling."
        )


def probe_credential_leak() -> str:
    """A bare-string secret flows verbatim into an error message; there is no
    redaction barrier and no 'this is a secret' type.

    Strand counterpart: Credential wrapper + CredentialScrubber (Q-042).
    """
    api_key = "sk-live-7f3a9c2e8b1d4f6a0c5e9d2b"  # noqa: S105 (demo value)
    try:
        raise RuntimeError(f"upstream call failed for key {api_key}")
    except RuntimeError as e:
        message = str(e)
        leaked = api_key in message
        return f"exception message contains the raw key verbatim (leaked={leaked})."


def probe_confused_deputy() -> str:
    """A function 'declared' (by name/comment) to write a log path writes
    wherever its argument points; nothing couples the declared target to the
    actual argument.

    Strand counterpart: corpus 73 (effect projection drift rejected at verify, Q-039).
    """
    with tempfile.TemporaryDirectory() as root:
        def write_app_log(path: str, data: str) -> None:
            # "Only ever writes /tmp/app.log" — but the runtime never checks.
            with open(path, "w") as f:
                f.write(data)

        actual = os.path.join(root, "not-the-log.txt")
        write_app_log(actual, "arbitrary")
        wrote_elsewhere = os.path.exists(actual)
        return (
            f"function documented to write a fixed log path wrote to an "
            f"arbitrary argument path instead (wrote_elsewhere={wrote_elsewhere}); "
            f"no effect declaration is bound to the call argument."
        )


def probe_ssrf_no_allowlist() -> str:
    """urllib accepts a cloud-metadata / link-local target with no allowlist or
    default-deny policy. We construct and open the request against a guaranteed
    non-routable test address (TEST-NET-1, RFC 5737) to show the call proceeds
    to the socket layer with no policy rejection -- without contacting any real
    internal service.

    Strand counterpart: corpus 75 (NetSandbox blocks metadata host, Q-041).
    """
    # 192.0.2.0/24 is reserved for documentation and is not routable; the
    # connection attempt fails at the socket layer, never at a policy layer.
    target = "http://192.0.2.1/latest/meta-data/"
    try:
        urllib.request.urlopen(target, timeout=0.25)  # noqa: S310
        outcome = "request dispatched (no policy layer)"
    except Exception as e:  # noqa: BLE001
        outcome = f"failed at the socket/transport layer ({type(e).__name__}), not at a policy layer"
    return (
        f"urllib.request.urlopen accepted a non-routable internal-style URL and "
        f"{outcome}; stock Python has no SSRF allowlist or default-deny on "
        f"loopback/RFC1918/link-local/metadata ranges."
    )


PROBES = [
    ("path-traversal", probe_path_traversal),
    ("resource-exhaustion", probe_resource_exhaustion),
    ("credential-leak", probe_credential_leak),
    ("confused-deputy", probe_confused_deputy),
    ("ssrf-no-allowlist", probe_ssrf_no_allowlist),
]


def main() -> int:
    print(f"Python {sys.version.split()[0]} conventional-baseline containment probes\n")
    for name, fn in PROBES:
        print(f"[{name}]")
        print(f"  {fn()}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
