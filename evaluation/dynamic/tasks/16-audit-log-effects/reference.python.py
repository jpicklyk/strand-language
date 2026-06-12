"""Python parallel of task 16.

Strand probes `UncoveredEffects` when the helper Lambda's explicit
effect row omits the clock-read effect its body exercises. Python has
no effect system; logging plus time.time() always run, so the Python
parallel cannot fail the equivalent way. Audit output goes to stderr
via the logging module so stdout carries only the checked value.
"""

import logging
import time


def audited_double(x: int) -> int:
    logging.info("audit:%d", int(time.time() * 1000))
    return x * 2


def main() -> None:
    print(audited_double(21))


if __name__ == "__main__":
    main()
