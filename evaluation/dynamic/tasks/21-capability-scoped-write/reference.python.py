"""Python parallel of task 21.

Strand probes `CapabilityScopeUnsatisfiable` when the CAP grant
covers only the write but not the clock read that derives the path.
Python has no capability scopes; the parallel derives the path and
calls a stub, succeeding unconditionally.
"""

import time


def write_audit(path: str) -> int:
    # Stands in for the Filesystem.Write stub: no IO, returns 0.
    return 0


def main() -> None:
    path = "audit-" + str(int(time.time() * 1000))
    print(write_audit(path) + 7)


if __name__ == "__main__":
    main()
