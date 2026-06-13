#!/usr/bin/env python3
"""Independent Strand canonical encoder (Q-066 conformance artifact).

Recomputes Strand content hashes from dag-json documents using ONLY:

  - design/canonical-encoding.md  (the normative byte-level specification)
  - the dag-json field schema     (the authoring/on-wire format)
  - blake3_reference.py           (vendored CC0 BLAKE3, validated at startup)

THE INDEPENDENCE RULE: this implementation was written from the
specification prose alone, without reading the Kotlin encoder sources
(impl-kotlin/hashing/CanonicalEncoder.kt, Hasher.kt). It exists to witness
that the canonical encoding is implementable from its specification — the
Q-052 second-implementation validation, discharged early at the encoding
layer. If this encoder and the Kotlin implementation ever disagree on a
golden hash, the discrepancy is either a defect in one implementation or
an underdetermined sentence in the spec; the resolution protocol is in
README.md next to this file. Do not "fix" a disagreement by porting Kotlin
code into this file — that destroys the witness.

Usage:
  python independent_encoder.py <program.json> [...]   print each root hash
  python independent_encoder.py --golden [corpusDir]   recompute every entry
        in corpus/golden-hashes.json's "programs" section; exit nonzero
        naming any mismatch. corpusDir defaults to <repo>/corpus relative
        to this file.

Stock Python 3, standard library only.
"""

import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blake3_reference import Hasher as Blake3Hasher  # noqa: E402

# --------------------------------------------------------------------------
# BLAKE3 startup validation: official test vectors (input is the repeating
# byte pattern 0,1,...,250; hashes from BLAKE3-team/BLAKE3 test_vectors.json).
# The vendored module is not trusted until these pass.
# --------------------------------------------------------------------------

_OFFICIAL_VECTORS = [
    (0, "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"),
    (1, "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213"),
    (1023, "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11"),
    (102400, "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085"),
]


def _validate_blake3():
    for n, expected in _OFFICIAL_VECTORS:
        h = Blake3Hasher()
        h.update(bytes(i % 251 for i in range(n)))
        got = h.finalize(32).hex()
        if got != expected:
            raise RuntimeError(
                f"vendored BLAKE3 failed official test vector (len={n}): got {got}, want {expected}"
            )


_validate_blake3()


def blake3_256(data: bytes) -> bytes:
    h = Blake3Hasher()
    h.update(data)
    return h.finalize(32)


# --------------------------------------------------------------------------
# Multihash (spec: Digest and serialized form)
# --------------------------------------------------------------------------

MULTIHASH_PREFIX = b"\x1e"  # BLAKE3-256 per ADR-003

# Canonical-encoding epoch this encoder implements (Q-062,
# design/canonical-encoding.md "Epoch log"). Mirrors the Kotlin constant
# org.strand.hashing.CanonicalEncoding.EPOCH; the pair advances together when
# an epoch ships. --golden refuses to compare vectors from any other epoch.
#
# Epoch 2 (2026-06-13) normalized the two gated optional fields — FunctionType
# / ForeignNode `effectProjections` and EventStream `source` — to the uniform
# presence-prefix rule (uint(0) absent / uint(1)+content present), so the
# presence byte is now always emitted and those nodes hash differently than
# under epoch 1. N-048 RecursiveProjection tag 48 was introduced additively
# under epoch 1 and carries forward unchanged.
CANONICAL_ENCODING_EPOCH = 2


def multihash(encoding: bytes) -> bytes:
    return MULTIHASH_PREFIX + blake3_256(encoding)


# --------------------------------------------------------------------------
# Canonical-CBOR subset (spec: Canonical CBOR subset). Major types 0, 1, 2,
# 4 only; definite length; shortest argument form; arguments unsigned 64-bit.
# --------------------------------------------------------------------------


def _cbor_head(major: int, arg: int) -> bytes:
    if arg < 0:
        raise ValueError(f"CBOR argument must be non-negative, got {arg}")
    base = major << 5
    if arg <= 23:
        return bytes([base | arg])
    if arg <= 0xFF:
        return bytes([base | 24, arg])
    if arg <= 0xFFFF:
        return bytes([base | 25]) + arg.to_bytes(2, "big")
    if arg <= 0xFFFFFFFF:
        return bytes([base | 26]) + arg.to_bytes(4, "big")
    if arg <= 0xFFFFFFFFFFFFFFFF:
        return bytes([base | 27]) + arg.to_bytes(8, "big")
    raise ValueError(f"CBOR argument exceeds 64 bits: {arg}")


def uint(n: int) -> bytes:
    return _cbor_head(0, n)


def cbor_int(n: int) -> bytes:
    return uint(n) if n >= 0 else _cbor_head(1, -1 - n)


def cbor_bytes(b: bytes) -> bytes:
    return _cbor_head(2, len(b)) + b


def array_header(count: int) -> bytes:
    return _cbor_head(4, count)


# --------------------------------------------------------------------------
# Category tags and discriminators (spec: Category tags; Discriminators)
# --------------------------------------------------------------------------


def tag(n: int) -> bytes:
    return struct.pack(">I", n)


TAGS = {
    "IntLit": 1, "FloatLit": 2, "StringLit": 3, "BoolLit": 4, "UnitLit": 5,
    "BytesLit": 6, "PrimitiveType": 7, "ProductType": 8, "ProductTypeField": 9,
    "SumType": 10, "SumTypeCase": 11, "FunctionType": 12, "Lambda": 14,
    "Application": 16, "Let": 17, "VarRef": 18, "NodeRef": 19,
    "ForeignNode": 20, "EffectCategory": 21, "EffectDecl": 22, "Match": 23,
    "MatchCase": 24, "Pattern": 25, "Fixpoint": 26, "StateMachine": 27,
    "EventStream": 28, "Transition": 29, "Schema": 32, "Invariant": 33,
    "TypeAbstraction": 34, "ForallType": 35, "CapabilityScope": 36,
    "ProductValue": 37, "ProductFieldValue": 38, "ProductFieldGet": 39,
    "SumValue": 40, "RecursiveType": 41, "RecursiveSelf": 42, "Handler": 43,
    "ToolDef": 44, "ResponseSchemaSpec": 45, "ModuleManifest": 46,
    "Attempt": 47, "RecursiveProjection": 48,
}

# RecursiveProjection path-step discriminator (spec: Discriminators):
# Case 0 (+ bytes(caseName)), Field 1 (+ bytes(fieldName)), Unfold 2 (no payload).
PROJECTION_STEP_TAGS = {"Case": 0, "Field": 1, "Unfold": 2}

TYPE_PARAMETER_REFERENCE_TAG = 13  # inline positional form only

PRIMITIVES = {"Int": 0, "Float": 1, "String": 2, "Bool": 3, "Unit": 4, "Bytes": 5}
STREAM_KINDS = {"external": 0, "internal": 1, "output": 2}
OVERFLOW_TAGS = {"BlockProducer": 0, "DropNewest": 1, "DropOldest": 2, "Sample": 3}
CONSUMER_MODES = {"Single": 0, "Broadcast": 1}
PATTERN_KINDS = {"literal": 0, "variable": 1, "wildcard": 2, "constructor": 3}

UNBOUND_SENTINEL = 2147483647          # 2^31 - 1, per spec: Unbound sentinels
RECURSIVE_SENTINEL = 9223372036854775807  # Long.MAX_VALUE


def name_sort_key(s: str) -> bytes:
    """Lexicographic over UTF-16 code units (spec: Set-like and positional
    edge lists). UTF-16-BE byte comparison equals code-unit comparison."""
    return s.encode("utf-16-be")


class EncoderError(Exception):
    pass


# --------------------------------------------------------------------------
# Encoder. The binder context is (frames, recursive_depth): frames is a
# tuple of frames, each frame a tuple of binder author-ids, innermost LAST
# (pushed at the end); recursive_depth counts enclosing RecursiveType
# binders (spec: Binder references).
# --------------------------------------------------------------------------

EMPTY_CONTEXT = ((), 0)


class Encoder:
    def __init__(self, nodes: dict):
        self.nodes = nodes
        self._hash_memo = {}

    # ---- reference helpers ----

    def node(self, nid: str) -> dict:
        try:
            return self.nodes[nid]
        except KeyError:
            raise EncoderError(f"unknown node id '{nid}'")

    def resolve_binder(self, binder_id: str, frames):
        """Innermost-to-outermost scan; returns (depth, index) or None."""
        for depth, frame in enumerate(reversed(frames)):
            if binder_id in frame:
                return depth, frame.index(binder_id)
        return None

    def node_multihash(self, nid: str, ctx) -> bytes:
        key = (nid, ctx)
        cached = self._hash_memo.get(key)
        if cached is not None:
            return cached
        m = multihash(self.encode(nid, ctx))
        self._hash_memo[key] = m
        return m

    def H(self, nid: str, ctx) -> bytes:
        """Hash reference: bytes(33-byte multihash) in the current context."""
        return cbor_bytes(self.node_multihash(nid, ctx))

    def T(self, nid: str, ctx) -> bytes:
        """Type reference: inline positional form for a bound TypeParameter,
        otherwise H(c). A free TypeParameter encodes as a hash reference
        whose preimage is the tag-13 sentinel form."""
        node = self.node(nid)
        if node["type"] == "TypeParameter":
            frames, _ = ctx
            pos = self.resolve_binder(nid, frames)
            if pos is not None:
                depth, index = pos
                return tag(TYPE_PARAMETER_REFERENCE_TAG) + uint(depth) + uint(index)
            preimage = (tag(TYPE_PARAMETER_REFERENCE_TAG)
                        + uint(UNBOUND_SENTINEL) + uint(UNBOUND_SENTINEL))
            return cbor_bytes(multihash(preimage))
        return self.H(nid, ctx)

    def sorted_hash_set(self, ids, ctx) -> bytes:
        """array(sorted H(...)) — multihashes sorted as unsigned byte
        strings; no deduplication."""
        hashes = sorted(self.node_multihash(i, ctx) for i in ids)
        return array_header(len(hashes)) + b"".join(cbor_bytes(m) for m in hashes)

    # ---- structural field accessors over dag-json ----

    @staticmethod
    def _opt(node, field):
        v = node.get(field)
        return None if v is None else v

    @staticmethod
    def _list(node, field):
        return node.get(field) or []

    @staticmethod
    def _hex_bytes(s: str) -> bytes:
        clean = s[2:] if s[:2] in ("0x", "0X") else s
        return bytes.fromhex(clean)

    def variable_patterns(self, pattern_id: str):
        """Every VariablePattern in the pattern tree, depth-first via
        ConstructorPattern.payloadPattern edges (spec: Binder references)."""
        node = self.node(pattern_id)
        kind = node.get("kind")
        if kind == "variable":
            return [pattern_id]
        if kind == "constructor":
            payload = self._opt(node, "payloadPattern")
            return self.variable_patterns(payload) if payload is not None else []
        return []

    # ---- the per-category encodings ----

    def encode(self, nid: str, ctx) -> bytes:
        node = self.node(nid)
        ntype = node["type"]
        frames, rec_depth = ctx

        if ntype == "ParameterDecl":
            raise EncoderError(
                f"ParameterDecl '{nid}' has no standalone canonical encoding "
                "(intrinsic to its Lambda)"
            )
        if ntype == "TypeParameter":
            raise EncoderError(
                f"TypeParameter '{nid}' has no standalone canonical encoding "
                "(identity is purely positional; reachable only via T(c))"
            )

        t = tag(TAGS[ntype])

        # ----- literals -----
        if ntype == "IntLit":
            return t + cbor_int(int(node["value"]))
        if ntype == "FloatLit":
            return t + cbor_bytes(struct.pack(">d", float(node["value"])))
        if ntype == "StringLit":
            return t + cbor_bytes(node["value"].encode("utf-8"))
        if ntype == "BoolLit":
            return t + uint(1 if node["value"] else 0)
        if ntype == "UnitLit":
            return t
        if ntype == "BytesLit":
            return t + cbor_bytes(self._hex_bytes(node["value"]))

        # ----- types -----
        if ntype == "PrimitiveType":
            return t + uint(PRIMITIVES[node["kind"]])
        if ntype == "ProductType":
            fields = sorted(self._list(node, "fields"),
                            key=lambda f: name_sort_key(self.node(f)["name"]))
            return t + array_header(len(fields)) + b"".join(self.H(f, ctx) for f in fields)
        if ntype == "ProductTypeField":
            return (t + cbor_bytes(node["name"].encode("utf-8"))
                    + self.T(node["fieldType"], ctx))
        if ntype == "SumType":
            cases = sorted(self._list(node, "cases"),
                           key=lambda c: name_sort_key(self.node(c)["name"]))
            return t + array_header(len(cases)) + b"".join(self.H(c, ctx) for c in cases)
        if ntype == "SumTypeCase":
            out = t + cbor_bytes(node["name"].encode("utf-8"))
            case_type = self._opt(node, "caseType")
            if case_type is None:
                return out + uint(0)
            return out + uint(1) + self.T(case_type, ctx)
        if ntype == "FunctionType":
            params = self._list(node, "parameters")
            out = t + array_header(len(params)) + b"".join(self.T(p, ctx) for p in params)
            out += self.T(node["result"], ctx)
            out += self.sorted_hash_set(self._list(node, "effects"), ctx)
            out += self.encode_optional_projections(self._list(node, "effectProjections"), ctx)
            return out
        if ntype == "ForallType":
            tps = self._list(node, "typeParameters")
            body_ctx = (frames + (tuple(tps),), rec_depth)
            return t + uint(len(tps)) + self.T(node["body"], body_ctx)
        if ntype == "RecursiveType":
            return t + self.T(node["body"], (frames, rec_depth + 1))
        if ntype == "RecursiveSelf":
            depth = node.get("depth") or 0
            if 0 <= depth < rec_depth:
                return t + uint(depth)
            return t + uint(RECURSIVE_SENTINEL)
        if ntype == "RecursiveProjection":
            # [tag 48][H(recursiveType)][array(step_1, ..., step_n)] (spec:
            # Types, RecursiveProjection). The recursiveType is a hash
            # reference in the SURROUNDING context — the projection introduces
            # no binder; the Unfold binder motion is the verifier-resolver's,
            # not the encoder's. The path is POSITIONAL (order is
            # semantically load-bearing), so it is not sorted. Each step is
            # nested CBOR: Case -> array(uint(0), bytes(caseName)),
            # Field -> array(uint(1), bytes(fieldName)), Unfold -> array(uint(2)).
            out = t + self.H(node["recursiveType"], ctx)
            steps = node["path"]
            out += array_header(len(steps))
            for step in steps:
                kind = step["step"]
                code = PROJECTION_STEP_TAGS[kind]
                if kind == "Case":
                    out += array_header(2) + uint(code) + cbor_bytes(step["caseName"].encode("utf-8"))
                elif kind == "Field":
                    out += array_header(2) + uint(code) + cbor_bytes(step["fieldName"].encode("utf-8"))
                elif kind == "Unfold":
                    out += array_header(1) + uint(code)
                else:
                    raise EncoderError(f"unknown RecursiveProjection step '{kind}'")
            return out

        # ----- functions and binding -----
        if ntype == "Lambda":
            params = self._list(node, "parameters")
            param_types = [self.node(p)["paramType"] for p in params]
            out = t + array_header(len(param_types))
            out += b"".join(self.T(pt, ctx) for pt in param_types)  # outer context
            body_ctx = (frames + (tuple(params),), rec_depth)
            out += self.H(node["body"], body_ctx)
            out += self.sorted_hash_set(self._list(node, "effects"), ctx)  # outer context
            return out
        if ntype == "TypeAbstraction":
            tps = self._list(node, "typeParameters")
            body_ctx = (frames + (tuple(tps),), rec_depth)
            return t + uint(len(tps)) + self.H(node["body"], body_ctx)
        if ntype == "Application":
            args = self._list(node, "arguments")
            type_args = self._list(node, "typeArguments")
            out = t + self.H(node["function"], ctx)
            out += array_header(len(args)) + b"".join(self.H(a, ctx) for a in args)
            out += array_header(len(type_args)) + b"".join(self.T(a, ctx) for a in type_args)
            instances = self._list(node, "effectInstances")
            if instances:  # gated on size > 0
                out += self.sorted_hash_set(instances, ctx)
            return out
        if ntype == "Let":
            out = t + self.H(node["value"], ctx)  # outer context
            body_ctx = (frames + ((nid,),), rec_depth)  # frame holds the Let itself
            return out + self.H(node["body"], body_ctx)
        if ntype == "VarRef":
            pos = self.resolve_binder(node["binder"], frames)
            if pos is None:
                return t + uint(UNBOUND_SENTINEL) + uint(UNBOUND_SENTINEL)
            depth, index = pos
            return t + uint(depth) + uint(index)

        # ----- references -----
        if ntype == "NodeRef":
            target_hash = node.get("targetHash")
            if target_hash is not None:
                return t + cbor_bytes(self._hex_bytes(target_hash))
            # local target: hash computed under the EMPTY binder context
            return t + cbor_bytes(self.node_multihash(node["target"], EMPTY_CONTEXT))
        if ntype == "ForeignNode":
            out = t + cbor_bytes(node["target"].encode("utf-8"))
            out += self.H(node["foreignType"], ctx)
            out += self.sorted_hash_set(self._list(node, "effects"), ctx)
            out += self.encode_optional_projections(self._list(node, "effectProjections"), ctx)
            return out

        # ----- effects and capabilities -----
        if ntype == "EffectCategory":
            params = self._list(node, "parameters")
            return (t + cbor_bytes(node["categoryName"].encode("utf-8"))
                    + array_header(len(params)) + b"".join(self.T(p, ctx) for p in params))
        if ntype == "EffectDecl":
            params = self._list(node, "parameters")
            return (t + self.H(node["effectType"], ctx)
                    + array_header(len(params)) + b"".join(self.H(p, ctx) for p in params))
        if ntype == "CapabilityScope":
            return (t + self.sorted_hash_set(self._list(node, "capabilities"), ctx)
                    + self.H(node["body"], ctx))
        if ntype == "Handler":
            return (t + self.H(node["intercept"], ctx) + self.H(node["handle"], ctx)
                    + self.H(node["body"], ctx))

        # ----- control flow -----
        if ntype == "Match":
            cases = self._list(node, "cases")  # declaration order
            return (t + self.H(node["scrutinee"], ctx)
                    + array_header(len(cases)) + b"".join(self.H(c, ctx) for c in cases))
        if ntype == "MatchCase":
            out = t + self.H(node["pattern"], ctx)  # outer context
            bound = self.variable_patterns(node["pattern"])
            body_ctx = (frames + (tuple(bound),), rec_depth) if bound else ctx
            return out + self.H(node["body"], body_ctx)
        if ntype == "Pattern":
            kind = node["kind"]
            out = t + uint(PATTERN_KINDS[kind]) + self.T(node["patternType"], ctx)
            if kind == "literal":
                return out + self.H(node["literal"], ctx)
            if kind in ("variable", "wildcard"):
                return out  # VariablePattern's name is metadata, absent
            if kind == "constructor":
                out += cbor_bytes(node["caseName"].encode("utf-8"))
                payload = self._opt(node, "payloadPattern")
                if payload is None:
                    return out + uint(0)
                return out + uint(1) + self.H(payload, ctx)
            raise EncoderError(f"unknown Pattern kind '{kind}'")
        if ntype == "Fixpoint":
            return t + self.H(node["recursionType"], ctx) + self.H(node["body"], ctx)
        if ntype == "Attempt":
            return t + self.H(node["body"], ctx)

        # ----- composite values -----
        if ntype == "ProductValue":
            fields = sorted(self._list(node, "fields"),
                            key=lambda f: name_sort_key(self.node(f)["fieldName"]))
            return (t + self.H(node["ofType"], ctx)
                    + array_header(len(fields)) + b"".join(self.H(f, ctx) for f in fields))
        if ntype == "ProductFieldValue":
            return (t + cbor_bytes(node["fieldName"].encode("utf-8"))
                    + self.H(node["value"], ctx))
        if ntype == "ProductFieldGet":
            return (t + self.H(node["target"], ctx)
                    + cbor_bytes(node["fieldName"].encode("utf-8")))
        if ntype == "SumValue":
            out = t + self.H(node["ofType"], ctx)
            out += cbor_bytes(node["caseName"].encode("utf-8"))
            payload = self._opt(node, "payload")
            if payload is None:
                return out + uint(0)
            return out + uint(1) + self.H(payload, ctx)

        # ----- state machines -----
        if ntype == "StateMachine":
            ins = self._list(node, "inputStreams")
            outs = self._list(node, "outputStreams")
            out = t + self.H(node["transitionFn"], ctx) + self.H(node["initialState"], ctx)
            out += array_header(len(ins)) + b"".join(self.H(s, ctx) for s in ins)
            out += array_header(len(outs)) + b"".join(self.H(s, ctx) for s in outs)
            out += self.sorted_hash_set(self._list(node, "effects"), ctx)
            return out
        if ntype == "EventStream":
            return t + self.encode_event_stream(node, ctx)
        if ntype == "Transition":
            guard = self._opt(node, "guard")
            out = t + (uint(0) if guard is None else uint(1) + self.H(guard, ctx))
            return out + self.H(node["body"], ctx)

        # ----- structured outputs and agent-native capabilities -----
        if ntype == "Schema":
            return (t + cbor_bytes(node["schemaName"].encode("utf-8"))
                    + self.H(node["valueType"], ctx)
                    + self.sorted_hash_set(self._list(node, "invariants"), ctx))
        if ntype == "Invariant":
            # targetSchema is excluded (cycle break)
            return (t + cbor_bytes(node["invariantName"].encode("utf-8"))
                    + self.H(node["body"], ctx))
        if ntype == "ToolDef":
            # name and description are metadata, excluded
            return (t + self.H(node["parameterSchema"], ctx)
                    + self.H(node["implementation"], ctx))
        if ntype == "ResponseSchemaSpec":
            return t + self.H(node["schema"], ctx)

        # ----- composition and distribution -----
        if ntype == "ModuleManifest":
            exports = self._list(node, "exports")
            out = t + array_header(len(exports))
            for export in exports:
                # nested CBOR: array( bytes(target multihash), array(sorted H(effect)) )
                target_m = self.node_multihash(export["target"], EMPTY_CONTEXT)
                effects = self._list(export, "declaredEffects")
                out += (array_header(2) + cbor_bytes(target_m)
                        + self.sorted_hash_set(effects, ctx))
            return out

        raise EncoderError(f"node '{nid}': no encoding for category '{ntype}'")

    def encode_optional_projections(self, projections, ctx) -> bytes:
        """Optional `effectProjections` field under the uniform presence-prefix
        rule (epoch 2, spec: Effect projections): uint(0) when absent, uint(1)
        followed by the projection-list field when present. The presence byte
        is always emitted — the epoch-1 gated-omit special case is gone."""
        if not projections:
            return uint(0)
        return uint(1) + self.encode_projections(projections, ctx)

    def encode_projections(self, projections, ctx) -> bytes:
        """Effect-projection list (spec: Effect projections): nested CBOR,
        declaration order; sources positional."""
        out = array_header(len(projections))
        for proj in projections:
            sources = proj["sources"]
            src_bytes = array_header(len(sources))
            for src in sources:
                if src["kind"] == "ArgRef":
                    src_bytes += array_header(2) + uint(0) + uint(int(src["index"]))
                elif src["kind"] == "LiteralNode":
                    src_bytes += array_header(2) + uint(1) + self.H(src["target"], ctx)
                else:
                    raise EncoderError(f"unknown ProjectionSource kind '{src['kind']}'")
            out += array_header(2) + self.H(proj["category"], ctx) + src_bytes
        return out

    def encode_event_stream(self, node, ctx) -> bytes:
        """EventStream fields after the tag (spec: State machines).
        Base: H(eventType), uint(streamKind). The three optionals
        (bufferSize, overflowPolicy, consumerMode) are all-default-omitted;
        when any is non-default all three are encoded with default
        sentinels. Epoch 2: the optional source edge follows the uniform
        presence-prefix rule — a trailing uint(0) when null, uint(1) + H(source)
        when present — always emitted as the last field."""
        out = self.H(node["eventType"], ctx)
        out += uint(STREAM_KINDS[node["streamKind"].lower()])

        buffer_size = self._opt(node, "bufferSize")
        policy = self.parse_overflow_policy(self._opt(node, "overflowPolicy"))
        consumer = self._opt(node, "consumerMode")
        consumer_norm = consumer.capitalize() if isinstance(consumer, str) else None

        all_default = (
            buffer_size is None
            and (policy is None or policy[0] == "BlockProducer")
            and (consumer_norm is None or consumer_norm == "Single")
        )
        if not all_default:
            out += uint(buffer_size if buffer_size is not None else 0)
            policy_name = policy[0] if policy is not None else "BlockProducer"
            out += uint(OVERFLOW_TAGS[policy_name])
            if policy_name == "Sample":
                out += uint(policy[1])
            out += uint(CONSUMER_MODES[consumer_norm] if consumer_norm is not None else 0)

        source = self._opt(node, "source")
        if source is None:
            out += uint(0)
        else:
            out += uint(1) + self.H(source, ctx)
        return out

    @staticmethod
    def parse_overflow_policy(v):
        """dag-json overflowPolicy: shorthand string or {kind, intervalNanos}.
        Returns None | (name,) | ("Sample", intervalNanos)."""
        if v is None:
            return None
        if isinstance(v, str):
            aliases = {
                "blockproducer": "BlockProducer", "block_producer": "BlockProducer",
                "block": "BlockProducer",
                "dropnewest": "DropNewest", "drop_newest": "DropNewest",
                "dropnew": "DropNewest",
                "dropoldest": "DropOldest", "drop_oldest": "DropOldest",
                "dropold": "DropOldest",
            }
            return (aliases[v.lower()],)
        kind = v["kind"]
        if kind == "Sample":
            return ("Sample", int(v["intervalNanos"]))
        return (kind,)


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------


def root_hash_hex(document_text: str) -> str:
    doc = json.loads(document_text)
    encoder = Encoder(doc["nodes"])
    return encoder.node_multihash(doc["root"], EMPTY_CONTEXT).hex()


def find_repo_corpus() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "corpus"
        if (candidate / "01-int-literal.json").is_file():
            return candidate
    raise SystemExit("cannot locate the repo corpus/ directory from " + str(here))


def run_golden(corpus_dir: Path) -> int:
    golden = json.loads((corpus_dir / "golden-hashes.json").read_text(encoding="utf-8"))
    file_epoch = golden.get("epoch")
    if file_epoch != CANONICAL_ENCODING_EPOCH:
        print(
            f"epoch mismatch: golden-hashes.json declares encoding epoch {file_epoch}, "
            f"this encoder implements epoch {CANONICAL_ENCODING_EPOCH} (Q-062, "
            "design/canonical-encoding.md 'Epoch log'); refusing to compare hashes "
            "across epochs"
        )
        return 1
    programs = golden["programs"]
    mismatches = []
    skipped = []
    for rel, expected in sorted(programs.items()):
        if expected.startswith("unhashable-standalone"):
            skipped.append((rel, expected))
            continue
        text = (corpus_dir / rel).read_text(encoding="utf-8")
        try:
            got = root_hash_hex(text)
        except Exception as e:  # an encoding crash is a conformance failure
            mismatches.append((rel, expected, f"<error: {type(e).__name__}: {e}>"))
            continue
        if got != expected:
            mismatches.append((rel, expected, got))
    layer_a = golden.get("layerA", {})

    print(f"encoding epoch   : {file_epoch}")
    print(f"programs checked : {len(programs) - len(skipped)}")
    print(f"matched          : {len(programs) - len(skipped) - len(mismatches)}")
    print(f"skipped (marker) : {len(skipped)}")
    print(f"layerA entries   : {len(layer_a)} (not recomputed: Layer A compilation "
          "is an authoring-pipeline concern, out of scope for the encoder; "
          "their canonical forms are covered via the equivalent .json programs)")
    if mismatches:
        print("\nMISMATCHES:")
        for rel, expected, got in mismatches:
            print(f"  {rel}\n    golden: {expected}\n    python: {got}")
        return 1
    print("\nall golden program hashes reproduced independently")
    return 0


def main(argv):
    sys.setrecursionlimit(200_000)
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] == "--golden":
        corpus_dir = Path(argv[1]) if len(argv) > 1 else find_repo_corpus()
        return run_golden(corpus_dir)
    status = 0
    for arg in argv:
        text = Path(arg).read_text(encoding="utf-8")
        print(f"{root_hash_hex(text)}  {arg}")
    return status


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
