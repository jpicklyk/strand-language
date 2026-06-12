"""Python parallel of task 18.

Strand probes the runtime SchemaInvariantViolation (Q-047 deferred
obligation) when the 12-character concatenation flows into the
ShortName-typed parameter untruncated. The Python parallel enforces
the same invariant in __post_init__, so the equivalent slip surfaces
as a raised ValueError traceback at runtime — after mypy passes.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class ShortName:
    value: str

    def __post_init__(self) -> None:
        if len(self.value) > 8:
            raise ValueError(f"ShortName invariant violated: {self.value!r}")


def identity(p: ShortName) -> ShortName:
    return p


def main() -> None:
    raw = "strand" + "_agent"
    username = ShortName(raw[0:8])
    print(len(identity(username).value))


if __name__ == "__main__":
    main()
