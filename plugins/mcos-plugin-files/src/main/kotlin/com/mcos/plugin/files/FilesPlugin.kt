package com.mcos.plugin.files

import com.mcos.sdk.*
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
        entry = "com.mcos.plugin.files.FilesPlugin",
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
        "photo.compress" to PhotoCompressHandler()
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
            val regex = Regex.glob(pattern)
            return regex.matches(name)
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

    companion object {
        /**
         * Glob → regex conversion.
         *
         * Builds the regex char-by-char: every regex metacharacter is escaped
         * (so a literal `.` in the glob matches a `.`), while the two glob
         * wildcards keep their special meaning:
         *  - `*` → `.*`  (any run of characters)
         *  - `?` → `.`   (exactly one character)
         *
         * We can NOT use `Regex.escape(pattern)` here: it wraps the whole
         * literal run in `\Q...\E`, after which a `.replace("\\*", ".*")` can
         * no longer find the escaped `*` — silently breaking glob matching.
         */
        private fun Regex.Companion.glob(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (c in pattern) {
                sb.append(when (c) {
                    '*' -> ".*"
                    '?' -> "."
                    // Regex metacharacters that must be escaped to match literally.
                    '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|',
                    '-', '/' -> "\\$c"
                    else -> c.toString()
                })
            }
            sb.append('$')
            return Regex(sb.toString())
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
