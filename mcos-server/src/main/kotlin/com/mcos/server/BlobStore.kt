package com.mcos.server

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Upload rejected because the blob exceeds the configured size cap. */
class BlobTooLargeException(message: String) : Exception(message)

/**
 * Opaque blob storage on disk ([07-memory.md 11.0]: `mcos-server` stores
 * **encrypted blobs only** — it never parses or inspects their content).
 *
 * Blob ids are restricted to `[A-Za-z0-9_-]{1,128}` so a client-supplied id
 * is always safe to use as a file name (no path traversal). Writes are
 * atomic (tmp file + rename), so a crash mid-write never leaves a partial
 * blob readable under its final id.
 */
class BlobStore(
    private val root: Path,
    private val maxBlobBytes: Long = MAX_BLOB_BYTES,
) {
    companion object {
        const val MAX_BLOB_BYTES: Long = 16L * 1024 * 1024
        private val BLOB_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** True when [id] is a safe blob id (file-name-safe, no separators). */
        fun isValidBlobId(id: String): Boolean = BLOB_ID.matches(id)
    }

    private val dir: Path = root.resolve("blobs")

    init {
        Files.createDirectories(dir)
    }

    /** Store [bytes] under [id], atomically replacing any previous value. */
    fun put(id: String, bytes: ByteArray) {
        require(isValidBlobId(id)) { "invalid blob id: $id" }
        if (bytes.size > maxBlobBytes) {
            throw BlobTooLargeException("blob $id exceeds $maxBlobBytes bytes")
        }
        val target = dir.resolve(id)
        val tmp = dir.resolve("$id.tmp")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Filesystem without atomic-move support: fall back to a plain replace.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Fetch [id], or `null` when no such blob exists. */
    fun get(id: String): ByteArray? {
        require(isValidBlobId(id)) { "invalid blob id: $id" }
        val file = dir.resolve(id)
        if (!Files.exists(file)) return null
        return Files.readAllBytes(file)
    }

    /** Remove [id]. Idempotent — a missing blob is a no-op. */
    fun delete(id: String) {
        require(isValidBlobId(id)) { "invalid blob id: $id" }
        Files.deleteIfExists(dir.resolve(id))
    }
}
