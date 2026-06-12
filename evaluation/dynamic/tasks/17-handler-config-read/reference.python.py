"""Python parallel of task 17.

Strand probes `HandlerSignatureMismatch` when the handle lambda does
not match fsRead's (String) -> Bytes signature. The natural Python
slip (returning str instead of bytes from the stub) is INVISIBLE at
runtime because len() accepts both; mypy --strict only catches it if
the annotation contradicts the body. The reference returns bytes.
"""


def read_config(path: str) -> bytes:
    # Stands in for the intercepted filesystem read.
    return b"debug=true"


def main() -> None:
    config = read_config("/etc/app.conf")
    print(len(config))


if __name__ == "__main__":
    main()
