"""Python parallel of task 11.

Strand probes `UncoveredEffects` when a Lambda's body uses an
effectful builtin without declaring the effect on the Lambda itself.
Python has no effect system; the parallel demonstrates the
helper-function shape with a deterministic `write_log()` stub.
"""


def write_log(path: str, contents: bytes) -> int:
    # Strand's Filesystem.Write stubs to 0 bytes-written in the eval
    # environment. The Python parallel hard-codes the same stub here.
    return 0


def append_log() -> int:
    return write_log("/tmp/strand-eval.log", b"") + 1


def main() -> None:
    print(append_log() + 10)


if __name__ == "__main__":
    main()
