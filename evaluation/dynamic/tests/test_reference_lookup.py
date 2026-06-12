"""Reference lookup (Q-060 M-2): topics, builtin signature blocks,
prelude names, and nearest-match misses.

These tests run against the real prompts/references/ sections, so they
double as content pins: the authoritative signature text must stay
locatable by the names agents will request.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from strand_eval.reference_lookup import (
    DEFAULT_REFERENCES_DIR,
    ReferenceLookup,
    build_reference_reply,
)

EXPECTED_TOPICS = [
    "builtins",
    "density-sugars",
    "effects",
    "errors",
    "formats",
    "grammar-codes",
    "llm-vector",
    "prelude",
    "state-machines",
]


@pytest.fixture(scope="module")
def lookup() -> ReferenceLookup:
    assert DEFAULT_REFERENCES_DIR.is_dir(), (
        f"reference sections missing at {DEFAULT_REFERENCES_DIR}"
    )
    return ReferenceLookup()


def test_all_expected_topics_indexed(lookup):
    assert lookup.topic_names() == EXPECTED_TOPICS


def test_topic_hit_serves_section_text(lookup):
    result = lookup.lookup("prelude")
    assert result.found and result.kind == "topic"
    assert "FunctionType signatures (117)" in result.text
    assert "Foreign-node builtins (128)" in result.text


def test_builtin_hit_serves_signature_block_verbatim(lookup):
    result = lookup.lookup("List.Map")
    assert result.found and result.kind == "builtin"
    assert "strand-builtin:List.Map(list, fn: A -> B) -> List<B>" in result.text


def test_builtin_hit_accepts_target_prefix(lookup):
    bare = lookup.lookup("Json.Parse")
    prefixed = lookup.lookup("strand-builtin:Json.Parse")
    assert bare.found and prefixed.found
    assert bare.text == prefixed.text
    assert "Option<JsonValue>" in bare.text


def test_multiline_signature_block_is_complete(lookup):
    """Http.Request's signature spans five lines incl. the `) ->` close;
    the block must carry all of them, not just the opening line."""
    result = lookup.lookup("Http.Request")
    assert result.found
    assert "strand-builtin:Http.Request(" in result.text
    assert ") -> {status: Int, body: Bytes" in result.text


def test_signature_block_carries_contract_comments(lookup):
    """The `--` comment lines under a signature are the authority text
    (semantics like inclusive/exclusive bounds); they belong to the block."""
    result = lookup.lookup("Random.Int")
    assert result.found
    assert "inclusive min, exclusive max" in result.text


def test_prelude_short_name_hit(lookup):
    result = lookup.lookup("fsWrite")
    assert result.found and result.kind == "prelude"
    assert "Fs.* filesystem" in result.text
    assert "ArgRef(0)" in result.text


def test_prelude_effect_category_hit(lookup):
    result = lookup.lookup("writeFx")
    assert result.found and result.kind == "prelude"
    assert "Filesystem.Write{path: String}" in result.text


def test_miss_returns_suggestion_and_no_fabricated_signature(lookup):
    result = lookup.lookup("Lst.Mapp")
    assert not result.found and result.kind == "miss"
    assert "List.Map" in result.suggestions
    assert "never fabricates" in result.text
    # No signature-shaped content is served on a miss.
    assert "strand-builtin:Lst.Mapp" not in result.text


def test_miss_with_no_close_match_still_lists_topics(lookup):
    result = lookup.lookup("zzzzqqqq")
    assert not result.found
    assert "Available topics:" in result.text


def test_v5_excluded_streaming_open_resolves_to_signature(lookup):
    """The streaming opens are excluded from the v5 implicit table but
    must still be resolvable by name (the agent needs the signature to
    hand-declare the FNT + FN)."""
    result = lookup.lookup("Anthropic.Messages.CreateStream")
    assert result.found
    assert "GenerateRequest" in result.text


def test_build_reference_reply_mixes_hits_and_misses(lookup):
    reply, served, missed = build_reference_reply(
        lookup, ["List.Map", "Lst.Mapp"], turns_used_after=1, max_turns=3
    )
    assert served == ["List.Map"]
    assert missed == ["Lst.Mapp"]
    assert reply.startswith("[strand reference response]")
    assert "strand-builtin:List.Map(" in reply
    assert "Did you mean" in reply
    assert "Reference turns used: 1 of 3." in reply


def test_build_reference_reply_empty_request_lists_topics(lookup):
    reply, served, missed = build_reference_reply(
        lookup, [], turns_used_after=1, max_turns=3
    )
    assert served == [] and missed == []
    assert "requires at least one topic" in reply
    assert "prelude" in reply


def test_missing_references_dir_degrades_to_explicit_miss(tmp_path):
    empty = ReferenceLookup(tmp_path / "nonexistent")
    assert empty.topic_names() == []
    result = empty.lookup("List.Map")
    assert not result.found
    assert "never fabricates" in result.text
