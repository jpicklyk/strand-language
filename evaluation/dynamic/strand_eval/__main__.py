"""``python -m strand_eval`` entry point.

Delegates to the argparse CLI in :mod:`strand_eval.cli`, so
``python -m strand_eval lookup List.Map`` and
``python -m strand_eval step --session <dir>`` work without the
installed ``strand-eval`` console script (``python -m strand_eval.cli``
remains equivalent).
"""

from strand_eval.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
