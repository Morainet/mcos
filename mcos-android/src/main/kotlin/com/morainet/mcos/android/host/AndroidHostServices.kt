package com.morainet.mcos.android.host

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.morainet.mcos.sdk.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android-specific implementation of [HostServices].
 * Wires real Android APIs — ContentResolver, HttpURLConnection,
 * Intent-based activity launching, NotificationManager, bitmap
 * compression, SharedPreferences — into the MCOS plugin execution
 * pipeline.
 */
class AndroidHostServices(
    context: Context,
    private val resultBridge: ActivityResultBridge,
) : HostServices {

    override val files: FileService = AndroidFileService(context)
    override val net: NetService = AndroidNetService()
    override val ui: UiService = AndroidUiService(context, resultBridge)
    override val secureStore: SecureStore = AndroidSecureStore(context)
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: JsonService = object : JsonService {
        private val j = Json { ignoreUnknownKeys = true }
        override fun parse(source: String): JsonElement = j.parseToJsonElement(source)
    }
    override val memory: MemoryFacade = InMemoryFacade()
    override val notifications: NotificationService = AndroidNotificationService(context)
    override val media: MediaService = AndroidMediaService(context)
}

// ── FileService ─────────────────────────────────────────────────────────────

class AndroidFileService(private val context: Context) : FileService {
    override suspend fun list(uri: String, mimeType: String?): List<FileEntry> {
        val contentUri = when {
            uri.startsWith("media://images") ||
                uri.startsWith("content://media/external/images") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            uri.startsWith("media://video") ||
                uri.startsWith("content://media/external/video") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            uri.startsWith("media://audio") ||
                uri.startsWith("content://media/external/audio") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            else -> return emptyList()
        }
        return queryMediaStore(contentUri, null, null, null, null, Int.MAX_VALUE)
    }

    override suspend fun searchPhotos(
        mimeType: String,
        afterMs: Long?,
        beforeMs: Long?,
        limit: Int,
    ): List<FileEntry> {
        // MediaStore DATE_ADDED is stored in whole seconds.
        val selection = buildString {
            if (afterMs != null) {
                if (isNotEmpty()) append(" AND ")
                append("${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            }
            if (beforeMs != null) {
                if (isNotEmpty()) append(" AND ")
                append("${MediaStore.MediaColumns.DATE_ADDED} <= ?")
            }
        }
        val selectionArgs = buildList {
            if (afterMs != null) add((afterMs / 1000).toString())
            if (beforeMs != null) add((beforeMs / 1000).toString())
        }
        // Newest first; API 31+ pushes LIMIT into the query, older devices
        // are capped client-side inside queryMediaStore.
        return queryMediaStore(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mimeType = mimeType,
            selection = selection.ifEmpty { null },
            selectionArgs = selectionArgs.toTypedArray(),
            sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            limit = limit,
        )
    }

    private fun queryMediaStore(
        contentUri: Uri,
        mimeType: String?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        limit: Int,
    ): List<FileEntry> {
        val effectiveSort = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sortOrder != null) {
            "$sortOrder LIMIT $limit"
        } else {
            sortOrder
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val entries = mutableListOf<FileEntry>()
        try {
            context.contentResolver.query(
                contentUri, projection, selection, selectionArgs, effectiveSort,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && entries.size < limit) {
                    val modifiedSec = cursor.getLong(dateCol)
                    entries.add(
                        FileEntry(
                            uri = "content://media/external/${cursor.getString(idCol)}",
                            name = cursor.getString(nameCol) ?: "",
                            size = cursor.getLong(sizeCol),
                            mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                            dateModifiedMs = if (modifiedSec > 0) modifiedSec * 1000 else null,
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Media permission not granted yet — return empty instead of crashing.
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

class AndroidUiService(
    private val context: Context,
    private val bridge: ActivityResultBridge,
) : UiService {

    override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? {
        val action = intent["action"] ?: return null
        return when (action) {
            "ACTION_IMAGE_CAPTURE" -> launchCamera(intent)
            "ACTION_SCAN_BARCODE" -> null // P2: ML Kit barcode scanning
            "ACTION_VIEW" -> openUrl(intent)
            "ACTION_SEND" -> share(intent)
            else -> startRawAction(action, intent)
        }
    }

    private suspend fun launchCamera(intent: Map<String, String>): Map<String, String>? {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val uri = fileProviderUri(outFile)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = bridge.launch(i)
        if (result?.resultCode == Activity.RESULT_OK && outFile.exists()) {
            return mapOf(
                "status" to "captured",
                "uri" to fileProviderUri(outFile).toString(),
                "mimeType" to "image/jpeg",
            )
        }
        return null // user cancelled
    }

    private fun openUrl(intent: Map<String, String>): Map<String, String>? {
        val url = intent["uri"]
        if (url.isNullOrBlank()) return mapOf("status" to "cancelled")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            mapOf("status" to "opened")
        } catch (_: Exception) {
            mapOf("status" to "failed")
        }
    }

    private fun share(intent: Map<String, String>): Map<String, String>? {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        intent["text"]?.takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        intent["uri"]?.takeIf { it.isNotBlank() }?.let {
            try {
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(it))
                i.type = "image/*"
            } catch (_: Exception) {
                // ignore malformed uri
            }
        }
        val chooser = Intent.createChooser(i, "Share via MCOS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            mapOf("status" to "completed")
        } catch (_: Exception) {
            mapOf("status" to "failed")
        }
    }

    private fun startRawAction(action: String, intent: Map<String, String>): Map<String, String>? {
        val i = Intent(action)
        intent.forEach { (k, v) -> if (k != "action") i.putExtra(k, v) }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            mapOf("status" to "started")
        } catch (_: Exception) {
            mapOf("status" to "failed")
        }
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ── NotificationService ─────────────────────────────────────────────────────

class AndroidNotificationService(private val context: Context) : NotificationService {

    private val counter = AtomicInteger(1)
    private val channelId = "mcos_commands"

    override suspend fun notify(title: String, text: String): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 26 guarantees API 26+; channel creation is always safe.
        val channel = NotificationChannel(
            channelId, "MCOS Commands", NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)
        // Permission is required on API 33+; skip posting (no crash) when denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return channelId
        }
        val builder = Notification.Builder(context, channelId)
        nm.notify(
            counter.getAndIncrement(),
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
        )
        return channelId
    }
}

// ── MediaService ────────────────────────────────────────────────────────────

class AndroidMediaService(private val context: Context) : MediaService {

    override suspend fun compress(
        uris: List<String>,
        quality: Int,
        maxWidth: Int?,
        maxHeight: Int?,
    ): List<String> {
        val outDir = File(context.cacheDir, "mcos").apply { mkdirs() }
        return uris.mapNotNull { uri ->
            try {
                val resolver = context.contentResolver
                val bmp = resolver.openInputStream(Uri.parse(uri))?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: return@mapNotNull null

                val scaled = resize(bmp, maxWidth, maxHeight)
                if (scaled !== bmp) bmp.recycle()

                val outFile = File(
                    outDir,
                    "mcos_compressed_${System.currentTimeMillis()}_${scaled.width}x${scaled.height}.jpg"
                )
                outFile.outputStream().use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                }
                scaled.recycle()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun resize(bmp: Bitmap, maxWidth: Int?, maxHeight: Int?): Bitmap {
        if (maxWidth == null && maxHeight == null) return bmp
        var w = bmp.width
        var h = bmp.height
        if (maxWidth != null && w > maxWidth) {
            h = (h * maxWidth) / w
            w = maxWidth
        }
        if (maxHeight != null && h > maxHeight) {
            w = (w * maxHeight) / h
            h = maxHeight
        }
        return if (w == bmp.width && h == bmp.height) bmp
        else Bitmap.createScaledBitmap(bmp, w, h, true)
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
        return ResolveResult.NotFound() // P2: episodic + fuzzy ref resolution
    }
}
