package com.morainet.mcos.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Filesystem-backed [SandboxFileService] over a single root directory
 * (04-plugin-sdk.md 6.1). Pure java.nio — the same class serves the JVM
 * runtime (test hosts inject a temp directory) and the Android host
 * (`filesDir/plugin-sandbox`), so the sandbox semantics are JVM-tested once
 * and shared everywhere.
 *
 * Path safety is two-layered:
 * 1. **Syntax** — a path that is blank, absolute, contains `..` or `.`
 *    segments, empty segments (`a//b`, trailing `a/`), backslashes, or NUL
 *    is rejected with `SCHEMA_VIOLATION` (`details.reason =
 *    "sandbox_path_invalid"`). Malformed input never reaches the disk.
 * 2. **Containment** — the resolved target must stay lexically under the
 *    root, and **no component of the existing path chain may be a symbolic
 *    link** (the sandbox is runtime-owned; a symlink inside it can only be
 *    an escape attempt or tampering). Violations fail with
 *    `PERMISSION_DENIED` (`details.reason = "sandbox_escape"`).
 *
 * The root directory is created lazily on the first mutation; reads and
 * listings on a not-yet-created sandbox behave as empty.
 */
class DirectorySandbox(root: Path) : SandboxFileService {

    private val root: Path = root.toAbsolutePath().normalize()

    constructor(root: String) : this(Paths.get(root))

    override suspend fun read(path: String): ByteArray? {
        val target = sandboxPath(path)
        return if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
    }

    override suspend fun write(path: String, data: ByteArray, append: Boolean) {
        val target = sandboxPath(path)
        target.parent?.let { Files.createDirectories(it) }
        if (append) {
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } else {
            Files.write(target, data)
        }
    }

    override suspend fun stat(path: String): SandboxEntry? {
        val target = sandboxPath(path)
        return when {
            Files.isRegularFile(target) ->
                SandboxEntry(path = path, isDir = false, size = Files.size(target))
            Files.isDirectory(target) ->
                SandboxEntry(path = path, isDir = true, size = null)
            else -> null
        }
    }

    override suspend fun delete(path: String): Boolean {
        val target = sandboxPath(path)
        return try {
            Files.deleteIfExists(target)
        } catch (_: DirectoryNotEmptyException) {
            throw McosException(
                code = "CONFLICT",
                message = "Cannot delete a non-empty directory: $path",
                details = reason("directory_not_empty"),
            )
        }
    }

    /**
     * Non-recursive listing of a sandbox-relative directory. An empty
     * [dir] lists the sandbox root (reads and writes never accept an empty
     * path — this is the one place it has a meaning).
     */
    override suspend fun list(dir: String): List<SandboxEntry> {
        val target = if (dir.isBlank()) root else sandboxPath(dir)
        if (!Files.isDirectory(target)) return emptyList()
        return Files.list(target).use { stream ->
            stream.sorted().map { entryPath ->
                val relative = root.relativize(entryPath).toString()
                SandboxEntry(
                    path = relative,
                    isDir = Files.isDirectory(entryPath),
                    size = if (Files.isRegularFile(entryPath)) Files.size(entryPath) else null,
                )
            }.toList()
        }
    }

    override suspend fun tempFile(prefix: String, suffix: String): String {
        // The prefix/suffix become filename components — they go through the
        // same single-segment validation as paths (no separators, no "..").
        requireSegment(prefix.ifEmpty { "mcos" })
        requireSegment(suffix)
        Files.createDirectories(root)
        val created = Files.createTempFile(root, prefix.ifEmpty { "mcos" }, suffix)
        return root.relativize(created).toString()
    }

    // ─── Path safety ───────────────────────────────────────────────────

    /** Syntax validation + containment + no-symlink check for [path]. */
    private fun sandboxPath(path: String): Path {
        path.split('/').forEach(::requireSegment)
        val target = this.root.resolve(path).normalize()
        if (target != root && !target.startsWith(root)) {
            throw escape(path)
        }
        // No component between the root and the target may be a symlink —
        // the sandbox is runtime-owned, so one can only be an escape or
        // tampering. The target itself is included in the walk.
        var node = target
        while (node != root) {
            if (Files.isSymbolicLink(node)) {
                throw escape(path)
            }
            node = node.parent ?: throw escape(path)
        }
        return target
    }

    /** Rejects a single path segment that is blank, `.`/`..`, or unsafe. */
    private fun requireSegment(segment: String) {
        when {
            segment.isBlank() -> throw invalid("blank segment")
            segment == "." || segment == ".." -> throw invalid("dot segment")
            segment.contains('\\') -> throw invalid("backslash")
            segment.contains('\u0000') -> throw invalid("NUL byte")
        }
    }

    private fun invalid(why: String): McosException = McosException(
        code = "SCHEMA_VIOLATION",
        message = "Invalid sandbox path ($why)",
        details = reason("sandbox_path_invalid"),
    )

    private fun escape(path: String): McosException = McosException(
        code = "PERMISSION_DENIED",
        message = "Path escapes the sandbox root: $path",
        details = reason("sandbox_escape"),
    )

    private fun reason(value: String): JsonObject =
        JsonObject(mapOf("reason" to JsonPrimitive(value)))
}
