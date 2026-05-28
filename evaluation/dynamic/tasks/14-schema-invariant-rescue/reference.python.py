"""Python parallel of task 14.

Strand probes `SchemaInvariantViolation` when a literal flowing into
a schema-typed parameter violates the schema's invariant. The probe
targets the boundary distinction: `x > 0` vs `x >= 0` — `0` is the
common "default" agents reach for when asked for the smallest
positive integer, and it violates the strict invariant.

Python uses a `PositiveInt` dataclass with a `__post_init__`
invariant check raising on violation.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class PositiveInt:
    value: int

    def __post_init__(self) -> None:
        if self.value <= 0:
            raise ValueError(f"PositiveInt requires value > 0, got {self.value}")


def identity(p: PositiveInt) -> PositiveInt:
    return p


def main() -> None:
    # The smallest integer satisfying x > 0 is 1.
    p = identity(PositiveInt(1))
    print(p)


if __name__ == "__main__":
    main()
