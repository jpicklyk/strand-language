package org.strand.hashing

import org.strand.core.Hash
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Q-058: an on-disk, hash-keyed content-addressed store that makes content
 * addressing operative across runs.
 *
 * A store is a directory with a recorded format version and canonical-encoding
 * epoch, holding one file per hashable node (keyed by the node's full multihash
 * hex, sharded by the leading prefix byte) and one file per cached verify
 * verdict (keyed by root hash). See `proposals/persistent-store.md` for the
 * full design.
 *
 * ```
 * <root>/
 *   store.json            { storeFormatVersion, encodingEpoch, hashFunction }
 *   nodes/1e/1e7a3c….json one hashable node's subgraph record (see NodeStoreCodec)
 *   verdicts/1e/1e….json  one cached verify verdict, keyed by ROOT hash
 * ```
 *
 * **Fail closed.** Opening a store whose `storeFormatVersion` exceeds this
 * reader's, or whose `encodingEpoch` differs from the running
 * [CanonicalEncoding.EPOCH], raises a structured error rather than misreading
 * bytes that would re-hash differently. A `store.json` beside an existing
 * `nodes/` directory but unparseable is a corrupt store and is rejected. A
 * node read back is re-hashed against its key (the per-node integrity layer);
 * the federation Merkle root re-hash on admission ([DiskStoreResolver] +
 * [FederatedProgram.fetchAndAdmit]) is the second layer.
 *
 * **Scope.** Local single-machine, single-writer. Garbage collection,
 * compaction, networked resolvers, and concurrent writers are deferred (see the
 * proposal § 8).
 */
class PersistentStore private constructor(
    val root: Path,
    /** The store's recorded encoding epoch. Equals [CanonicalEncoding.EPOCH] for a store this reader wrote. */
    val encodingEpoch: Int,
) {
    private val nodesDir: Path = root.resolve("nodes")
    private val verdictsDir: Path = root.resolve("verdicts")

    // ---------------------------------------------------------------------
    // Node read / write + integrity
    // ---------------------------------------------------------------------

    /** True iff the store holds a node-record file keyed by [hash]. */
    fun hasNode(hash: Hash): Boolean = Files.exists(nodePath(hash))

    /**
     * Persist every hashable node reachable from [program]'s root, one record
     * per node keyed by its content hash. Dedup is automatic: a node already on
     * disk is a no-op. Returns the count of newly-written records. A second
     * program sharing a subgraph writes the shared nodes zero times.
     *
     * Each node's subgraph record is obtained from a [LocalProgramResolver]
     * over [program] — the same self-contained, bound-node-including
     * [SubgraphFetch] the Q-043 federation path serves and admits, so the
     * round-trip is bit-identical to a cross-store fetch.
     */
    fun writeProgram(program: FinalizedProgram): Int {
        val resolver = LocalProgramResolver(program)
        var written = 0
        // program.hashToNodeId enumerates exactly the hashable reachable nodes.
        for (hash in program.hashToNodeId.keys) {
            if (hasNode(hash)) continue
            val fetch = resolver.resolve(hash) ?: continue
            if (writeNode(fetch)) written++
        }
        return written
    }

    /** The number of node-record files held (across all shards). For tests/diagnostics. */
    fun nodeCount(): Int {
        if (!Files.exists(nodesDir)) return 0
        var n = 0
        Files.newDirectoryStream(nodesDir).use { shards ->
            for (shard in shards) {
                if (Files.isDirectory(shard)) {
                    Files.newDirectoryStream(shard, "*.json").use { files -> files.forEach { _ -> n++ } }
                }
            }
        }
        return n
    }

    /**
     * Write the [SubgraphFetch] rooted at its `rootHash` as a node record,
     * unless one is already present (dedup: a node already on disk is a no-op).
     * Returns true if newly written. The caller obtains the fetch from a
     * [LocalProgramResolver] over the finalized program.
     */
    fun writeNode(fetch: SubgraphFetch): Boolean {
        val path = nodePath(fetch.rootHash)
        if (Files.exists(path)) return false
        val record = NodeStoreCodec.encodeFetch(fetch)
        val doc = Js.objOf(
            "storeFormatVersion" to Js.of(STORE_FORMAT_VERSION),
            "record" to record,
        )
        Files.createDirectories(path.parent)
        atomicWrite(path, doc.render())
        return true
    }

    /**
     * Read the node record keyed by [hash] into a [SubgraphFetch], or null if
     * absent. The decoded `rootHash` is checked against [hash]
     * ([NodeResolverIntegrityViolation] on mismatch); the per-node bytes have
     * already been written under [hash], and the Merkle root re-hash on
     * admission binds the whole subgraph. A malformed record file is a hard
     * error (fail closed).
     */
    fun readNode(hash: Hash): SubgraphFetch? {
        val path = nodePath(hash)
        if (!Files.exists(path)) return null
        val text = Files.readString(path)
        val doc = parseStoreJson(text) as? Js.Obj
            ?: throw StoreFormatError("node record at $path is not a JSON object")
        val recVersion = doc.int("storeFormatVersion")
            ?: throw StoreFormatError("node record at $path missing storeFormatVersion")
        if (recVersion > STORE_FORMAT_VERSION) {
            throw StoreFormatError(
                "node record at $path has storeFormatVersion $recVersion; this reader supports $STORE_FORMAT_VERSION"
            )
        }
        val record = doc.obj("record")
            ?: throw StoreFormatError("node record at $path missing 'record'")
        return NodeStoreCodec.decodeFetch(record, hash)
    }

    // ---------------------------------------------------------------------
    // Verdict cache
    // ---------------------------------------------------------------------

    /** Write the cached verify verdict for [rootHash]. Overwrites any prior verdict. */
    fun writeVerdict(rootHash: Hash, verdict: StoredVerdict) {
        val doc = Js.objOf(
            "storeFormatVersion" to Js.of(STORE_FORMAT_VERSION),
            "encodingEpoch" to Js.of(encodingEpoch),
            "rootHash" to Js.of(rootHash.toString()),
            "verdict" to verdict.toJson(),
        )
        val path = verdictPath(rootHash)
        Files.createDirectories(path.parent)
        atomicWrite(path, doc.render())
    }

    /**
     * Read the cached verdict for [rootHash], or null if absent, malformed, or
     * recorded under a different epoch (in which case it is ignored — re-verify
     * — rather than trusted; defense in depth beneath the store-level epoch
     * gate). The verdict is only meaningful when the store also holds the node
     * record for [rootHash]; the caller guards on [hasNode].
     */
    fun readVerdict(rootHash: Hash): StoredVerdict? {
        val path = verdictPath(rootHash)
        if (!Files.exists(path)) return null
        val doc = try {
            parseStoreJson(Files.readString(path)) as? Js.Obj ?: return null
        } catch (_: StoreJsonParseException) {
            return null
        }
        val epoch = doc.int("encodingEpoch") ?: return null
        if (epoch != encodingEpoch) return null
        val verdictObj = doc.obj("verdict") ?: return null
        return StoredVerdict.fromJson(verdictObj)
    }

    // ---------------------------------------------------------------------
    // Layout helpers
    // ---------------------------------------------------------------------

    private fun shard(hash: Hash): String = "%02x".format(hash.bytes[0])
    private fun nodePath(hash: Hash): Path = nodesDir.resolve(shard(hash)).resolve("$hash.json")
    private fun verdictPath(hash: Hash): Path = verdictsDir.resolve(shard(hash)).resolve("$hash.json")

    private fun atomicWrite(path: Path, content: String) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** The on-disk layout/codec version, independent of the canonical encoding. */
        const val STORE_FORMAT_VERSION: Int = 1

        /**
         * Open (or initialize) the store rooted at [dir]. An empty or absent
         * directory is initialized with the running [CanonicalEncoding.EPOCH]
         * and [STORE_FORMAT_VERSION]. An existing `store.json` is validated:
         * a higher format version or a different epoch fails closed. A `nodes/`
         * directory present without a parseable `store.json` is a corrupt store.
         */
        fun open(dir: Path): PersistentStore {
            Files.createDirectories(dir)
            val headerPath = dir.resolve("store.json")
            if (!Files.exists(headerPath)) {
                // Uninitialized: an existing nodes/ dir beside no header is corrupt.
                if (Files.exists(dir.resolve("nodes"))) {
                    throw StoreFormatError(
                        "store at $dir has a nodes/ directory but no store.json header (corrupt store)"
                    )
                }
                writeHeader(headerPath, CanonicalEncoding.EPOCH)
                return PersistentStore(dir, CanonicalEncoding.EPOCH)
            }
            val doc = try {
                parseStoreJson(Files.readString(headerPath)) as? Js.Obj
                    ?: throw StoreFormatError("store.json at $headerPath is not a JSON object")
            } catch (e: StoreJsonParseException) {
                throw StoreFormatError("store.json at $headerPath is unparseable: ${e.message}")
            }
            val format = doc.int("storeFormatVersion")
                ?: throw StoreFormatError("store.json at $headerPath missing storeFormatVersion")
            if (format > STORE_FORMAT_VERSION) {
                throw StoreFormatError(
                    "store at $dir was written with storeFormatVersion $format; this reader supports $STORE_FORMAT_VERSION"
                )
            }
            val epoch = doc.int("encodingEpoch")
                ?: throw StoreFormatError("store.json at $headerPath missing encodingEpoch")
            if (epoch != CanonicalEncoding.EPOCH) {
                throw StoreEpochMismatch(expected = CanonicalEncoding.EPOCH, found = epoch, dir = dir)
            }
            return PersistentStore(dir, epoch)
        }

        /**
         * Default store directory: `$STRAND_STORE` if set, else `~/.strand/store`.
         */
        fun defaultDir(): Path {
            val env = System.getenv("STRAND_STORE")
            if (!env.isNullOrBlank()) return Paths.get(env)
            val home = System.getProperty("user.home") ?: "."
            return Paths.get(home, ".strand", "store")
        }

        private fun writeHeader(path: Path, epoch: Int) {
            val doc = Js.objOf(
                "storeFormatVersion" to Js.of(STORE_FORMAT_VERSION),
                "encodingEpoch" to Js.of(epoch),
                "hashFunction" to Js.of("blake3"),
            )
            Files.writeString(path, doc.render())
        }
    }
}

/**
 * A [NodeResolver] backed by a [PersistentStore]. Serves the node record keyed
 * by a requested hash as a [SubgraphFetch], re-keyed onto the record's local
 * indices (which [FederatedProgram.fetchAndAdmit] re-bases into the local
 * store). Slots into the existing [ChainedResolver] exactly where a
 * `--peer-store` [LocalProgramResolver] does, so every backend that already
 * threads `resolveTarget` dereferences the local store with no new threading.
 *
 * The per-node integrity check (decoded `rootHash` must equal the requested
 * hash) is in [NodeStoreCodec.decodeFetch]; the trust-minimizing Merkle root
 * re-hash is the receiver's job ([FederatedProgram.fetchAndAdmit]).
 */
class DiskStoreResolver(private val store: PersistentStore) : NodeResolver {
    override fun resolve(hash: Hash): SubgraphFetch? = store.readNode(hash)
}

/** Thrown when a store's on-disk format is malformed or a higher version than this reader supports. */
class StoreFormatError(message: String) : RuntimeException(message)

/**
 * Thrown when a store's recorded canonical-encoding epoch differs from the
 * running [CanonicalEncoding.EPOCH]. The store is single-epoch by construction
 * (no in-band epoch marker per the Q-062 policy); a reader compiled against a
 * different epoch fails closed rather than admitting nodes whose bytes would
 * re-hash differently. This is the point at which Q-024's migration story
 * becomes load-bearing.
 */
class StoreEpochMismatch(
    val expected: Int,
    val found: Int,
    val dir: Path,
) : RuntimeException(
    "store at $dir was written under encoding epoch $found; this reader implements epoch $expected — " +
        "refusing to read (fail closed). Re-ingest under the current epoch or use an epoch-$found reader."
)
