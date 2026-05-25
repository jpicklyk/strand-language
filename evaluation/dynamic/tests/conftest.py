"""Shared pytest configuration.

Adds the evaluation/dynamic root to sys.path so `import strand_eval`
works without `pip install -e`. This keeps the test suite runnable
out of the box on a fresh clone.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
