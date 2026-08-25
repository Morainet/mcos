package com.morainet.mcos.plugin.files

import com.morainet.mcos.sdk.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Files plugin — file.list, file.search, photo.search, photo.compress.
 * Matches [04-plugin-sdk.md 17].
 */
class FilesPlugin : McosPlugin {

    override val manifest = PluginManifest(
        id = "mcos.plugin.files",
        name = "Files Plugin",
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "File listing, photo search and compression",
        provider = ProviderInfo("MCOS", "https://github.com/mcos-org"),
        entry = "com.morainet.mcos.plugin.files.FilesPlugin",
        permissions = listOf(
            PermissionEntry("android", "android.permission.READ_MEDIA_IMAGES", "Access photos"),
            PermissionEntry("android", "android.permission.READ_EXTERNAL_STORAGE", "List files")
        ),
        commands = listOf(
            CommandManifestEntry(
                id = "file.list", version = "1.0.0",
                title = "List Files",
                description = "List files in a directory or content URI",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""file.list(path="/sdcard/DCIM")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Directory path or content URI"))
                        })
                        put("mimeType", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by MIME type, e.g. 'image/*'"))
                        })
                        put("limit", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(1000))
                            put("description", JsonPrimitive("Max entries to return (default: 100)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "file.search", version = "1.0.0",
                title = "Search Files",
                description = "Search files by name pattern",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""file.search(pattern="*.jpg")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("File name glob pattern, e.g. '*.jpg'"))
                        })
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Search root directory (default: media store)"))
                        })
                        put("limit", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(500))
                            put("description", JsonPrimitive("Max results (default: 50)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "photo.search", version = "1.0.0",
                title = "Search Photos",
                description = "Search photos by date range, location, or media metadata",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""photo.search(date="today")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("date", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("today"))
                                add(JsonPrimitive("yesterday"))
                                add(JsonPrimitive("this_week"))
                                add(JsonPrimitive("this_month"))
                            })
                            put("description", JsonPrimitive("Date shorthand"))
                        })
                        put("after", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 date lower bound"))
                        })
                        put("before", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 date upper bound"))
                        })
                        put("location", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("GPS location label or coordinates"))
                        })
                        put("limit", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(200))
                            put("description", JsonPrimitive("Max results (default: 50)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "photo.compress", version = "1.0.0",
                title = "Compress Photo",
                description = "Compress one or more photos to a lower quality/format",
                sideEffectClass = SideEffectClass.write,
                idempotent = false,
                timeoutMs = 30000,
                examples = listOf(
                    "photo.compress(quality=80)",
                    """photo.compress(quality=80, uris=["content://1","content://2"])"""
                ),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("uris", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            })
                            put("description", JsonPrimitive("Photo URIs to compress. If empty, compresses latest photo."))
                        })
                        put("quality", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(100))
                            put("description", JsonPrimitive("JPEG quality 1-100 (default: 80)"))
                        })
                        put("maxWidth", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(8192))
                            put("description", JsonPrimitive("Max width in pixels (default: original)"))
                        })
                        put("maxHeight", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(8192))
                            put("description", JsonPrimitive("Max height in pixels (default: original)"))
                        })
                    })
                }
            ),
            // ─── Sandbox storage (04-plugin-sdk.md 6.1) ────────────────────
            // Plugin-namespaced reads/writes; hosts without the sandbox
            // capability surface UNAVAILABLE, never fake success.
            CommandManifestEntry(
                id = "file.write", version = "1.0.0",
                title = "Write Sandbox File",
                description = "Write text to a file in the plugin's sandbox (append optional)",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""file.write(path="logs/today.txt", text="battery 82%")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("path"))
                        add(JsonPrimitive("text"))
                    })
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Sandbox-relative path (auto-creates parent dirs)"))
                        })
                        put("text", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(MAX_FILE_BYTES))
                            put("description", JsonPrimitive("UTF-8 text to write (1 MiB limit)"))
                        })
                        put("append", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Append instead of overwrite (default: false)"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "file.read", version = "1.0.0",
                title = "Read Sandbox File",
                description = "Read a text file from the plugin's sandbox",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""file.read(path="logs/today.txt")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Sandbox-relative path"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "file.stat", version = "1.0.0",
                title = "Stat Sandbox File",
                description = "Check a sandbox path's existence, type and size",
                sideEffectClass = SideEffectClass.read,
                timeoutMs = 15000,
                examples = listOf("""file.stat(path="logs/today.txt")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Sandbox-relative path"))
                        })
                    })
                }
            ),
            CommandManifestEntry(
                id = "file.delete", version = "1.0.0",
                title = "Delete Sandbox File",
                description = "Delete a file (or empty directory) from the plugin's sandbox",
                sideEffectClass = SideEffectClass.write,
                timeoutMs = 15000,
                examples = listOf("""file.delete(path="logs/today.txt")"""),
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("minLength", JsonPrimitive(1))
                            put("description", JsonPrimitive("Sandbox-relative path"))
                        })
                    })
                }
            )
        ),
        namespaces = listOf("file", "photo")
    )

    private var services: HostServices? = null

    override suspend fun onLoad(services: HostServices) {
        this.services = services
    }

    override suspend fun onUnload() {
        this.services = null
    }

    override fun handlers(): Map<String, CommandHandler> = mapOf(
        "file.list" to FileListHandler(),
        "file.search" to FileSearchHandler(),
        "photo.search" to PhotoSearchHandler(),
        "photo.compress" to PhotoCompressHandler(),
        "file.write" to FileWriteHandler(),
        "file.read" to FileReadHandler(),
        "file.stat" to FileStatHandler(),
        "file.delete" to FileDeleteHandler()
    )

    // ─── Handlers ────────────────────────────────────────────────────────

    inner class FileListHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: path")
            val mimeType = args["mimeType"]?.jsonPrimitive?.content
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: 100

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")
            val entries = s.files.list(path, mimeType)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("count", JsonPrimitive(entries.size))
                    put("entries", buildJsonArray {
                        entries.take(limit).forEach { entry ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(entry.name))
                                put("uri", JsonPrimitive(entry.uri))
                                if (entry.mimeType != null)
                                    put("mimeType", JsonPrimitive(entry.mimeType))
                                if (entry.size != null)
                                    put("size", JsonPrimitive(entry.size))
                            })
                        }
                    })
                }
            )
        }
    }

    inner class FileSearchHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val pattern = args["pattern"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: pattern")
            val path = args["path"]?.jsonPrimitive?.content ?: "media://images"
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: 50

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")

            // Search by listing files and filtering by pattern (simplified glob)
            val allEntries = s.files.list(path)
            val matched = allEntries.filter { entry ->
                matchGlob(entry.name, pattern)
            }.take(limit)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("pattern", JsonPrimitive(pattern))
                    put("count", JsonPrimitive(matched.size))
                    put("entries", buildJsonArray {
                        matched.forEach { entry ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(entry.name))
                                put("uri", JsonPrimitive(entry.uri))
                                if (entry.mimeType != null)
                                    put("mimeType", JsonPrimitive(entry.mimeType))
                            })
                        }
                    })
                }
            )
        }

        private fun matchGlob(name: String, pattern: String): Boolean {
            if (pattern == "*") return true
            // Only fall back to substring matching when the pattern has no glob
            // wildcards at all. A pattern containing `?` must go through the
            // regex path so the single-char wildcard is honoured.
            if (!pattern.contains('*') && !pattern.contains('?')) {
                return name.contains(pattern, ignoreCase = true)
            }
            return globMatches(name, pattern)
        }
    }

    inner class PhotoSearchHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val date = args["date"]?.jsonPrimitive?.content
            val after = args["after"]?.jsonPrimitive?.content
            val before = args["before"]?.jsonPrimitive?.content
            val location = args["location"]?.jsonPrimitive?.content
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: 50

            val s = services ?: throw McosException("UNAVAILABLE", "System services not available")

            // Resolve date filters to epoch millis (local timezone) and push
            // them into the host query so the native media store applies the
            // selection server-side instead of loading everything.
            val bounds = resolveDateBounds(date, after, before, s.clock.nowMs())
            val photos = s.files.searchPhotos(
                mimeType = "image/*",
                afterMs = bounds.afterMs,
                beforeMs = bounds.beforeMs,
                limit = limit,
            )

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("count", JsonPrimitive(photos.size))
                    if (date != null) put("date", JsonPrimitive(date))
                    if (after != null) put("after", JsonPrimitive(after))
                    if (before != null) put("before", JsonPrimitive(before))
                    if (location != null) put("location", JsonPrimitive(location))
                    put("photos", buildJsonArray {
                        photos.forEach { entry ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(entry.name))
                                put("uri", JsonPrimitive(entry.uri))
                                if (entry.mimeType != null)
                                    put("mimeType", JsonPrimitive(entry.mimeType))
                                if (entry.size != null)
                                    put("size", JsonPrimitive(entry.size))
                                if (entry.dateModifiedMs != null)
                                    put("dateModifiedMs", JsonPrimitive(entry.dateModifiedMs))
                            })
                        }
                    })
                }
            )
        }
    }

    inner class PhotoCompressHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val uris = args["uris"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: emptyList()
            val quality = args["quality"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 80
            val maxWidth = args["maxWidth"]?.jsonPrimitive?.intOrNull
            val maxHeight = args["maxHeight"]?.jsonPrimitive?.intOrNull

            // Use the host media service when available (real bitmap
            // compression); otherwise fall back to a stub path mapping.
            val compressedPaths = services?.media?.let { media ->
                media.compress(uris, quality, maxWidth, maxHeight)
            } ?: uris.map { uri ->
                "content://mcos/compressed/${uri.substringAfterLast("/")}"
            }

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("quality", JsonPrimitive(quality))
                    put("count", JsonPrimitive(compressedPaths.size))
                    put("compressed", buildJsonArray {
                        compressedPaths.forEach { path ->
                            add(JsonPrimitive(path))
                        }
                    })
                },
                artifacts = compressedPaths.map { path ->
                    Artifact(type = "image", uri = path, mimeType = "image/jpeg")
                }
            )
        }
    }

    // ─── Sandbox storage handlers (04-plugin-sdk.md 6.1) ──────────────────
    //
    // These deliberately read the sandbox from ctx.services — the Executor's
    // Stage-4 facade — and NOT from the onLoad-captured `services` field the
    // media handlers use: the sandbox is namespaced per executing plugin
    // (NamespacedSandbox in the Executor), which only the per-execution view
    // carries. The onLoad field would hand every plugin the same host-wide
    // root and defeat the isolation.

    inner class FileWriteHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: path")
            val text = args["text"]?.jsonPrimitive?.contentOrNull
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: text")
            val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false

            val bytes = text.encodeToByteArray()
            // Belt-and-braces next to the schema maxLength: direct handler
            // invocations (tests, SDK hosts) skip schema validation.
            if (bytes.size > MAX_FILE_BYTES) {
                throw McosException(
                    code = "SCHEMA_VIOLATION",
                    message = "file.write payload exceeds the ${MAX_FILE_BYTES / 1024} KiB command limit",
                    details = JsonObject(mapOf("reason" to JsonPrimitive("file_too_large"))),
                )
            }
            val sandbox = ctx.services.sandbox
                ?: throw McosException("UNAVAILABLE", "Sandbox storage is not available on this host")
            sandbox.write(path, bytes, append)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("size", JsonPrimitive(sandbox.stat(path)?.size ?: bytes.size.toLong()))
                    put("append", JsonPrimitive(append))
                }
            )
        }
    }

    inner class FileReadHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: path")

            val sandbox = ctx.services.sandbox
                ?: throw McosException("UNAVAILABLE", "Sandbox storage is not available on this host")
            // Reject oversized files from their stat BEFORE loading them into
            // memory — reading first would defeat the cap and risk an OOM on a
            // multi-megabyte file (the command limit is a surface bound, not a
            // post-hoc check).
            val entry = sandbox.stat(path)
                ?: throw McosException("files.not_found", "No sandbox file at path: $path")
            entry.size?.let { size ->
                if (size > MAX_FILE_BYTES) {
                    throw McosException(
                        code = "files.too_large",
                        message = "File exceeds the ${MAX_FILE_BYTES / 1024} KiB command limit: $path",
                    )
                }
            }
            val bytes = sandbox.read(path)
                ?: throw McosException("files.not_found", "No sandbox file at path: $path")
            // Belt-and-braces for hosts whose stat does not report a size.
            if (bytes.size > MAX_FILE_BYTES) {
                throw McosException(
                    code = "files.too_large",
                    message = "File exceeds the ${MAX_FILE_BYTES / 1024} KiB command limit: $path",
                )
            }

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("size", JsonPrimitive(bytes.size.toLong()))
                    put("text", JsonPrimitive(bytes.decodeToString()))
                }
            )
        }
    }

    inner class FileStatHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: path")

            val sandbox = ctx.services.sandbox
                ?: throw McosException("UNAVAILABLE", "Sandbox storage is not available on this host")
            val entry = sandbox.stat(path)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("exists", JsonPrimitive(entry != null))
                    put("isDir", JsonPrimitive(entry?.isDir ?: false))
                    entry?.size?.let { put("size", JsonPrimitive(it)) }
                }
            )
        }
    }

    inner class FileDeleteHandler : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult {
            val args = ctx.args.jsonObject
            val path = args["path"]?.jsonPrimitive?.content
                ?: throw McosException("SCHEMA_VIOLATION", "Missing required arg: path")

            val sandbox = ctx.services.sandbox
                ?: throw McosException("UNAVAILABLE", "Sandbox storage is not available on this host")
            val deleted = sandbox.delete(path)

            return CommandResult.Ok(
                value = buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("deleted", JsonPrimitive(deleted))
                }
            )
        }
    }

    companion object {
        /** Command-surface cap for sandbox text payloads (input AND output). */
        const val MAX_FILE_BYTES: Int = 1024 * 1024

        /**
         * Anchored glob match for `*` (any run) and `?` (exactly one char).
         *
         * Iterative two-pointer algorithm with a single backtrack point for
         * the last `*` — O(name·pattern) worst case with no recursion. This
         * deliberately replaces the previous glob→regex translation: patterns
         * like `a*a*a*…b` compile to `a.*a.*a.*…b`, which sends the JVM regex
         * engine into catastrophic backtracking (ReDoS) on non-matching input.
         * A direct matcher has no such pathological case.
         */
        internal fun globMatches(name: String, pattern: String): Boolean {
            var n = 0
            var p = 0
            var starIdx = -1
            var matchIdx = 0
            while (n < name.length) {
                when {
                    p < pattern.length && (pattern[p] == '?' || pattern[p] == name[n]) -> {
                        n++; p++
                    }
                    p < pattern.length && pattern[p] == '*' -> {
                        starIdx = p; matchIdx = n; p++
                    }
                    starIdx != -1 -> {
                        p = starIdx + 1; matchIdx++; n = matchIdx
                    }
                    else -> return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}

/**
 * Date-range bounds for `photo.search`, resolved to epoch millis.
 */
internal data class DateBounds(val afterMs: Long?, val beforeMs: Long?)

/**
 * Resolve `photo.search` time filters to epoch millis in the local timezone.
 *
 * `date` supplies the lower bound when `after` is absent:
 *  - `today`      → 00:00 local time today
 *  - `yesterday`  → 00:00 local time yesterday
 *  - `this_week`  → 00:00 local time Monday of the current week
 *  - `this_month` → 00:00 local time on the 1st of the current month
 *
 * `after`/`before` accept ISO-8601 dates (`2026-08-15`) or datetimes
 * (`2026-08-15T10:30:00+08:00`); bare dates resolve to local midnight.
 *
 * @param nowMs current wall-clock millis, used only to derive "today".
 */
internal fun resolveDateBounds(
    date: String?,
    after: String?,
    before: String?,
    nowMs: Long,
): DateBounds {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val lowerFromShorthand = when (date) {
        null -> null
        "today" -> today.atStartOfDay(zone).toInstant().toEpochMilli()
        "yesterday" -> today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        "this_week" -> today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
        "this_month" -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> null
    }
    return DateBounds(
        afterMs = parseIsoDate(after) ?: lowerFromShorthand,
        beforeMs = parseIsoDate(before),
    )
}

/** Parse an ISO-8601 date or datetime to epoch millis, or null when unparseable. */
internal fun parseIsoDate(value: String?): Long? {
    if (value == null) return null
    val zone = ZoneId.systemDefault()
    return try {
        when {
            value.length == 10 -> LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
            else -> OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }
    } catch (_: Exception) {
        null
    }
}
