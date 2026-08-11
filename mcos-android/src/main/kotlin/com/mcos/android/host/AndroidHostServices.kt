package com.mcos.android.host

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.mcos.sdk.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-specific implementation of [HostServices].
 * Wires real Android APIs — ContentResolver, HttpURLConnection,
 * Intent-based activity launching, SharedPreferences — into the
 * MCOS plugin execution pipeline.
 */
class AndroidHostServices(context: Context) : HostServices {

    override val files: FileService = AndroidFileService(context)
    override val net: NetService = AndroidNetService()
    override val ui: UiService = AndroidUiService(context)
    override val secureStore: SecureStore = AndroidSecureStore(context)
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService = object : JsonService {
        private val j = Json { ignoreUnknownKeys = true }
        override fun parse(source: String): JsonElement = j.parseToJsonElement(source)
    }
    override val memory: MemoryFacade = InMemoryFacade()
}

// ── FileService ─────────────────────────────────────────────────────────────

class AndroidFileService(private val context: Context) : FileService {
    override suspend fun list(uri: String, mimeType: String?): List<FileEntry> {
        val contentUri = when {
            uri.startsWith("media://images") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            uri.startsWith("media://video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            uri.startsWith("media://audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return emptyList()
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val entries = mutableListOf<FileEntry>()
        context.contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                entries.add(
                    FileEntry(
                        uri = "content://media/external/${cursor.getString(idCol)}",
                        name = cursor.getString(nameCol) ?: "",
                        size = cursor.getLong(sizeCol),
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                    )
                )
            }
        }
        return entries
    }
}

// ── NetService ──────────────────────────────────────────────────────────────

class AndroidNetService : NetService {
    override suspend fun request(
        method: String, url: String, body: String?, headers: Map<String, String>,
    ): NetResponse {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (body != null && method.uppercase() in setOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            NetResponse(status = code, body = text)
        } catch (e: Exception) {
            NetResponse(status = 0, body = e.message ?: "Unknown network error")
        }
    }
}

// ── UiService ───────────────────────────────────────────────────────────────

class AndroidUiService(private val context: Context) : UiService {
    override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? {
        val action = intent["action"] ?: return null
        val extras = intent.filterKeys { it != "action" }
        val i = Intent(action)
        extras.forEach { (k, v) -> i.putExtra(k, v) }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            null // result not available with simple startActivity
        } catch (_: Exception) {
            null
        }
    }
}

// ── SecureStore ─────────────────────────────────────────────────────────────

class AndroidSecureStore(private val context: Context) : SecureStore {
    private val prefs = context.getSharedPreferences("mcos_secure", Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = prefs.getString(key, null)
    override suspend fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

// ── MemoryFacade ────────────────────────────────────────────────────────────

class InMemoryFacade : MemoryFacade {
    private val store = mutableMapOf<String, JsonElement>()

    override suspend fun get(path: String): JsonElement? = store[path]

    override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult {
        return ResolveResult.NotFound // P2: episodic + fuzzy ref resolution
    }
}
