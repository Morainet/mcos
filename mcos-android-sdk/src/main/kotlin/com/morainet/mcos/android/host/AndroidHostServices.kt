package com.morainet.mcos.android.host

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo as PlatformWifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
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
    private val permissionBridge: RuntimePermissionBridge,
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
    override val deviceInfo: DeviceInfoService =
        AndroidDeviceInfoService(context, permissionBridge, resultBridge)
    override val clipboard: ClipboardService = AndroidClipboardService(context)
    override val haptics: HapticsService = AndroidHapticsService(context)

    /**
     * Sandboxed per-plugin file storage (04 §6.1): one root inside the app's
     * private files dir; the Executor's Stage-4 facade namespaces every
     * plugin under `<root>/<pluginId>/`. [DirectorySandbox] is pure java.nio,
     * so its JVM test suite covers this exact implementation. No new
     * permission — app-private storage. Secrets never live here (08 §9).
     */
    override val sandbox: SandboxFileService =
        DirectorySandbox(File(context.filesDir, "plugin-sandbox").toPath())
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun toast(message: String) {
        // 04-plugin-sdk.md 6.3: toasts dispatch on the main thread. The host
        // suspends no caller for the toast's lifetime — fire-and-forget post.
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? {
        val action = intent["action"] ?: return null
        return when (action) {
            "ACTION_IMAGE_CAPTURE" -> launchCamera(intent)
            "ACTION_SCAN_BARCODE" -> throw McosException(
                "UNAVAILABLE",
                "Barcode scanning is not implemented on this host yet (P2: ML Kit) — " +
                    "surfacing an honest failure instead of a fake user-cancel",
                retryable = false,
            )
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
            // NO FLAG_ACTIVITY_NEW_TASK here: this launch awaits a result via
            // the ActivityResult bridge. NEW_TASK moves the camera into its
            // own task, where the framework cannot deliver the result back —
            // it fires RESULT_CANCELED at the launcher IMMEDIATELY (while the
            // camera still opens), which surfaced as "Photo capture was
            // cancelled by user" even after a successful shot. Fire-and-forget
            // launches (openUrl/share/startRawAction) may keep NEW_TASK;
            // result launches must stay in the host activity's task.
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

// ── DeviceInfoService / ClipboardService / HapticsService ───────────────────

/**
 * Real Android device telemetry. Every value comes from a system API —
 * nothing is fabricated. Data the OS will not hand out without a grant
 * (Wi-Fi SSID/RSSI without location permission) degrades to null, and
 * states that are genuine device states (no location fix) surface as such
 * rather than as errors.
 */
class AndroidDeviceInfoService(
    private val context: Context,
    private val permissionBridge: RuntimePermissionBridge,
    private val resultBridge: ActivityResultBridge,
) : DeviceInfoService {

    private companion object {
        // Settings.System.BRIGHTNESS_MODE_OFF/AUTOMATIC are @hide in the
        // public SDK — mirror the platform values (0 = manual, 1 = automatic).
        const val BRIGHTNESS_MODE_MANUAL = 0
        const val BRIGHTNESS_MODE_AUTOMATIC = 1
    }

    override suspend fun battery(): BatteryInfo {
        // Sticky broadcast query — registerReceiver(null, …) reads the last
        // ACTION_BATTERY_CHANGED state without registering a receiver.
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val tempC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10
        return BatteryInfo(percent, charging, tempC)
    }

    override suspend fun wifi(): WifiInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!connected) return WifiInfo(connected = false)
        // SSID and RSSI require the location permission on Android 9+.
        // Without it the OS returns "<unknown ssid>" — degrade honestly.
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return WifiInfo(connected = true, ssid = null, strength = null)
        }
        return try {
            @Suppress("DEPRECATION") // WifiManager.connectionInfo is deprecated on 31+,
            // but is still the only source of SSID/RSSI for the current network.
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Alias import: bare `WifiInfo` in this file is the SDK contract
            // type; this alias is the Android framework class.
            val info: PlatformWifiInfo = wm.connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"")
                ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            WifiInfo(connected = true, ssid = ssid, strength = info.rssi)
        } catch (_: SecurityException) {
            WifiInfo(connected = true, ssid = null, strength = null)
        }
    }

    override suspend fun screen(): ScreenInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        val metrics = context.resources.displayMetrics
        val brightness = try {
            Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Exception) {
            null
        }
        return ScreenInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            rotation = rotation,
            brightness = brightness,
        )
    }

    override suspend fun volume(): VolumeInfo {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun percent(stream: Int): Int? {
            val max = am.getStreamMaxVolume(stream)
            return if (max > 0) am.getStreamVolume(stream) * 100 / max else null
        }
        return VolumeInfo(
            musicPercent = percent(AudioManager.STREAM_MUSIC) ?: 0,
            ringPercent = percent(AudioManager.STREAM_RING),
            alarmPercent = percent(AudioManager.STREAM_ALARM),
        )
    }

    override suspend fun location(): LocationInfo? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // In-app runtime-permission prompt (04 §6.3): ask once instead of
            // sending the user to system settings. null = no Activity (a
            // headless schedule run) or another prompt in flight — surface
            // the real blocker instead of silently reporting "no fix".
            val granted = permissionBridge.request(Manifest.permission.ACCESS_FINE_LOCATION)
            if (granted != true) {
                throw McosException(
                    "PERMISSION_DENIED",
                    if (granted == false) {
                        "Location permission was denied"
                    } else {
                        "Location requires the ACCESS_FINE_LOCATION runtime grant — no prompt " +
                            "possible right now (headless run or dialog busy); grant it for MCOS in system settings"
                    }
                )
            }
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        } ?: return null // honest no-fix state, not an error
        return LocationInfo(
            lat = last.latitude,
            lng = last.longitude,
            accuracyM = if (last.hasAccuracy()) last.accuracy else null,
            timestampMs = if (last.time > 0) last.time else null,
        )
    }

    override suspend fun brightness(): BrightnessInfo {
        val level = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0
        )
        val auto = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0
        ) == BRIGHTNESS_MODE_AUTOMATIC
        return BrightnessInfo(level = level, auto = auto)
    }

    override suspend fun setBrightness(level: Int) {
        // WRITE_SETTINGS is special access: there is no requestPermissions
        // dialog for it — the user must flip "Modify system settings" for
        // the app. Deep-link the exact screen via the activity-result bridge
        // and re-check on return; without the grant the write would
        // silently fail, so surface the real blocker either way.
        if (!Settings.System.canWrite(context)) {
            val returned = resultBridge.launch(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
            )
            if (returned == null || !Settings.System.canWrite(context)) {
                throw McosException(
                    "PERMISSION_DENIED",
                    if (returned == null) {
                        "Setting brightness requires WRITE_SETTINGS — enable 'Modify system settings' for MCOS in system settings"
                    } else {
                        "Brightness is still not writable — flip 'Modify system settings' for MCOS " +
                            "in the special-access screen and retry"
                    }
                )
            }
        }
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level
        )
    }
}

/** System clipboard. [get] returns null when empty or OS-restricted (Android 10+ background). */
class AndroidClipboardService(private val context: Context) : ClipboardService {

    private fun manager(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override suspend fun set(text: String) {
        manager().setPrimaryClip(ClipData.newPlainText("mcos", text))
    }

    override suspend fun get(): String? {
        val clip = manager().primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}

/** Vibration via VibratorManager (Android 12+) with the legacy Vibrator fallback. */
class AndroidHapticsService(private val context: Context) : HapticsService {

    override suspend fun vibrate(durationMs: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // minSdk 26 — VibrationEffect is always available.
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
        )
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
