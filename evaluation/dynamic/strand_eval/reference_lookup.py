"""Reference lookup for the Q-060 M-2 minimal-core prompt.

The minimal core system prompt (``prompts/strand-system.md``) teaches the
highest-frequency Layer A subset and indexes named reference sections
under ``prompts/references/``. An agent requests the rest on demand: a
response whose first non-blank line is ``strand:need <name> [<name>...]``
makes the harness reply with only the requested reference text. This
module is that lookup; ``strand_eval.step`` wires it into step mode and
``strand-eval lookup`` / ``python -m strand_eval lookup`` exposes it as a
standalone CLI.

Three name kinds resolve, in precedence order:

1. **Topic** — a reference-section stem (``prelude``, ``builtins``,
   ``errors``, ...). Serves the whole section file.
2. **Builtin** — a dotted registry name (``List.Map``, ``Fs.Write``,
   optionally prefixed ``strand-builtin:``). Serves the builtin's
   signature block, located verbatim in the reference sections (whose
   per-builtin text is preserved from the pre-split monolith — the
   authority document).
3. **Prelude name** — a reserved short name (``fsWrite``, ``addT``,
   ``writeFx``). Serves the catalog line from the prelude section.

An unknown name NEVER yields silence or a fabricated signature: the
result is an explicit miss carrying nearest-match suggestions (stdlib
``difflib`` over every known topic, builtin, and prelude name).
"""

from __future__ import annotations

import difflib
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# Default location: evaluation/dynamic/prompts/references, resolved
# relative to this package so the lookup works from any cwd.
DEFAULT_REFERENCES_DIR = (
    Path(__file__).resolve().parent.parent / "prompts" / "references"
)

# A signature line: "strand-builtin:<DottedName>(" with the open paren
# (possibly after whitespace). Prose mentions like
# `"strand-builtin:Fs.Write",` or `FN "strand-builtin:Int.Add" addT`
# lack the paren and do not match.
_SIGNATURE_RE = re.compile(r"strand-builtin:([A-Za-z0-9_.]+)\s*\(")

# A prelude catalog line: four-space indent, one or more reserved names,
# two-plus spaces, an em-dash, then the description.
_PRELUDE_LINE_RE = re.compile(r"^ {4}([A-Za-z_][A-Za-z0-9_ ]*?)\s{2,}— ")
_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


@dataclass
class LookupResult:
    """One resolved (or missed) lookup."""

    query: str
    found: bool
    kind: str  # "topic" | "builtin" | "prelude" | "miss"
    text: str  # served reference text, or the miss message
    suggestions: list[str] = field(default_factory=list)


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip())


class ReferenceLookup:
    """Index over the reference sections; resolves names to text."""

    def __init__(self, references_dir: Optional[Path] = None):
        self.references_dir = Path(references_dir or DEFAULT_REFERENCES_DIR)
        self._topics: dict[str, Path] = {}
        # builtin dotted name -> list of (source file name, block text)
        self._builtins: dict[str, list[tuple[str, str]]] = {}
        # prelude reserved name -> list of (source file name, line text)
        self._prelude: dict[str, list[tuple[str, str]]] = {}
        self._build_index()

    # ------------------------------------------------------------------
    # Index construction
    # ------------------------------------------------------------------

    def _build_index(self) -> None:
        if not self.references_dir.is_dir():
            return
        for path in sorted(self.references_dir.glob("*.md")):
            self._topics[path.stem.lower()] = path
            lines = path.read_text(encoding="utf-8").splitlines()
            self._index_signatures(path.name, lines)
            if path.stem.lower() == "prelude":
                self._index_prelude_lines(path.name, lines)

    def _index_signatures(self, source: str, lines: list[str]) -> None:
        for i, line in enumerate(lines):
            match = _SIGNATURE_RE.search(line)
            if not match:
                continue
            name = match.group(1)
            block = self._extract_block(lines, i)
            self._builtins.setdefault(name, []).append((source, block))

    @staticmethod
    def _extract_block(lines: list[str], start: int) -> str:
        """The signature line plus its continuation lines.

        Continuations are lines indented deeper than the signature line
        (multi-line argument lists and ``--`` comments) and a same-indent
        line beginning with ``)`` (the close of a multi-line signature).
        A blank line, a dedent, or the next signature ends the block.
        """
        base = _indent(lines[start])
        block = [lines[start]]
        for j in range(start + 1, len(lines)):
            line = lines[j]
            if not line.strip():
                break
            if _indent(line) > base:
                block.append(line)
                continue
            if _indent(line) == base and line.lstrip().startswith(")"):
                block.append(line)
                continue
            break
        return "\n".join(block)

    def _index_prelude_lines(self, source: str, lines: list[str]) -> None:
        for line in lines:
            match = _PRELUDE_LINE_RE.match(line)
            if not match:
                continue
            names = match.group(1).split()
            if not all(_NAME_RE.match(n) for n in names):
                continue
            for name in names:
                self._prelude.setdefault(name, []).append((source, line.rstrip()))

    # ------------------------------------------------------------------
    # Public surface
    # ------------------------------------------------------------------

    def topic_names(self) -> list[str]:
        return sorted(self._topics)

    def known_names(self) -> list[str]:
        """Every resolvable name (topics + builtins + prelude)."""
        return sorted(set(self._topics) | set(self._builtins) | set(self._prelude))

    def lookup(self, raw_query: str) -> LookupResult:
        query = raw_query.strip()
        name = query[len("strand-builtin:"):] if query.startswith("strand-builtin:") else query

        # 1. Topic (case-insensitive stem).
        topic = self._topics.get(name.lower())
        if topic is not None:
            return LookupResult(
                query=query,
                found=True,
                kind="topic",
                text=topic.read_text(encoding="utf-8").rstrip("\n"),
            )

        # 2. Builtin signature block (exact, then case-insensitive).
        hit = self._builtins.get(name) or self._ci_get(self._builtins, name)
        if hit:
            parts = [
                f"### {name} — builtin signature (references/{source})\n\n{block}"
                for source, block in hit
            ]
            return LookupResult(
                query=query, found=True, kind="builtin", text="\n\n".join(parts)
            )

        # 3. Prelude reserved name (exact, then case-insensitive).
        hit = self._prelude.get(name) or self._ci_get(self._prelude, name)
        if hit:
            parts = [
                f"### {name} — implicit-prelude entry (references/{source})\n\n{line}"
                for source, line in hit
            ]
            return LookupResult(
                query=query, found=True, kind="prelude", text="\n\n".join(parts)
            )

        # 4. Miss: nearest-match suggestions, never a fabricated signature.
        suggestions = difflib.get_close_matches(
            name, self.known_names(), n=3, cutoff=0.4
        )
        lines = [f"Unknown reference name '{query}'. No signature or section was"]
        lines.append("served — the harness never fabricates signatures.")
        if suggestions:
            lines.append(f"Did you mean: {', '.join(suggestions)}?")
        lines.append(f"Available topics: {', '.join(self.topic_names())}.")
        return LookupResult(
            query=query,
            found=False,
            kind="miss",
            text=" ".join(lines[:2]) + "\n" + "\n".join(lines[2:]),
            suggestions=suggestions,
        )

    @staticmethod
    def _ci_get(index: dict, name: str):
        lowered = name.lower()
        for key, value in index.items():
            if key.lower() == lowered:
                return value
        return None


def build_reference_reply(
    lookup: ReferenceLookup,
    names: list[str],
    turns_used_after: int,
    max_turns: int,
) -> tuple[str, list[str], list[str]]:
    """Assemble the user-message text for one reference turn.

    Returns ``(reply_text, served_names, missed_names)``. A request with
    no names serves the topic index (still a reference turn).
    """
    served: list[str] = []
    missed: list[str] = []
    sections: list[str] = []
    if not names:
        sections.append(
            "strand:need requires at least one topic or builtin name. "
            f"Available topics: {', '.join(lookup.topic_names())}."
        )
    for name in names:
        result = lookup.lookup(name)
        sections.append(result.text)
        (served if result.found else missed).append(name)
    footer = (
        f"Reference turns used: {turns_used_after} of {max_turns}. "
        "Emit the Layer A program next, or request further references."
    )
    reply = "[strand reference response]\n\n" + "\n\n---\n\n".join(sections)
    reply += "\n\n" + footer
    return reply, served, missed


__all__ = [
    "DEFAULT_REFERENCES_DIR",
    "LookupResult",
    "ReferenceLookup",
    "build_reference_reply",
]
