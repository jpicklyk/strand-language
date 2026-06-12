"""Python parallel of task 20.

Strand probes the Q-039 projection family: an explicit EffectDecl at
the netConnect call site must bind the exact argument nodes in
category parameter order (ProjectionMismatch / arity / type
mismatches otherwise). Python has no effect-instance discipline; the
parallel is a stubbed connect.
"""


def connect(host: str, port: int) -> int:
    # Stands in for the intercepted Network.Connect; returns the
    # fixed handle the Strand Handler supplies.
    return 7


def main() -> None:
    handle = connect("db.internal", 5432)
    print(handle + 1)


if __name__ == "__main__":
    main()
