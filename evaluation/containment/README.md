# Containment probes

Conventional-baseline probes for the Q-044 containment measurement. They
ground the conventional-baseline rows of [`../containment-results.md`](../containment-results.md)
in executed behavior rather than assertion.

`python_baseline_probes.py` exhibits, in stock Python 3 with no dependencies,
the default behavior of each measured harm class: path traversal, resource
exhaustion, credential leak, confused deputy, and SSRF. Each probe is safe and
deterministic — network and destructive operations are demonstrated by showing
that no policy layer exists to reject them, never by causing real external
effects.

```sh
python python_baseline_probes.py
```

The Strand side of the matrix is not reproduced here: it is the corpus programs
(70–75) and unit tests named in `../containment-results.md`, exercised by
`./gradlew test` in `impl-kotlin/`.
