"""Python parallel of task 12.

Strand probes `EffectDeclArityMismatch` / `EffectDeclParameterTypeMismatch`
when the agent emits an `EffectDecl` with the wrong shape against a
multi-parameter `EffectCategory`. Python has no effect-decl
discipline; the parallel just calls the stub.
"""


def connect(host: str, port: int) -> int:
    # Strand's Net.Connect stubs to socket handle 42 in the eval
    # environment. The Python parallel hard-codes the same stub.
    return 42


def main() -> None:
    handle = connect("localhost", 8080)
    print(handle + 1)


if __name__ == "__main__":
    main()
