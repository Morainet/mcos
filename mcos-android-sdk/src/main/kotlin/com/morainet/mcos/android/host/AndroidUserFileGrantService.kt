package com.morainet.mcos.android.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.UserFileGrant
import com.morainet.mcos.sdk.UserFileGrantService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android [UserFileGrantService] (04-plugin-sdk.md 6.1): the system
 * document picker (`ACTION_OPEN_DOCUMENT`) is the user-consent moment, and
 * the picked document is readable for **this session only**.
 *
 * Deliberately NO `takePersistableUriPermission` — grants are
 * session-scoped (the plugin re-picks after a host restart), which keeps
 * the app far away from the platform's persisted-permission cap (128/512)
 * and matches the runtime's memory-only token registry exactly.
 *
 * The whole pick is single-flight under a [Mutex]:
 * [ActivityResultBridge] holds one pending slot — a second concurrent
 * result launch would silently orphan the first waiter and misroute its
 * result. A pick cancelled by the Executor's deadline releases the slot so
 * a later launch can proceed.
 */
class AndroidUserFileGrantService(
    private val context: Context,
    private val bridge: ActivityResultBridge,
) : UserFileGrantService {

    private val pickMutex = Mutex()

    override suspend fun pickForRead(mimeTypes: List<String>): UserFileGrant? = pickMutex.withLock {
        val types = mimeTypes.takeIf { it.isNotEmpty() } ?: listOf("*/*")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
            if (types.singleOrNull() != "*/*") {
                putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
            // NO FLAG_ACTIVITY_NEW_TASK — like the camera launch, a result
            // launch must stay in the host activity's task or the framework
            // fires RESULT_CANCELED immediately.
        }
        val result = try {
            bridge.launch(intent)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Deadline/cancel while the picker is on screen: release the
            // bridge's single pending slot so the next launch works.
            bridge.cancelPending()
            throw e
        }
        when {
            // No launcher attached (headless run) or launch failed — the
            // honest blocker, not a fake cancel.
            result == null -> throw McosException(
                "UNAVAILABLE",
                "The system picker cannot launch right now (no Activity registered or launch failed) — " +
                    "grants need a foreground MCOS screen",
            )
            result.resultCode != Activity.RESULT_OK || result.data?.data == null -> null // user cancelled
            else -> grantFromUri(result.data!!.data!!)
        }
    }

    /** Session grant: the URI is readable until our process dies — no persist call. */
    private fun grantFromUri(uri: Uri): UserFileGrant {
        val (name, size) = queryColumns(uri) ?: (null to null)
        return UserFileGrant(
            ref = uri.toString(),
            name = name,
            sizeBytes = size,
            mimeType = context.contentResolver.getType(uri),
        )
    }

    override suspend fun statGranted(ref: String): UserFileGrant? {
        val uri = Uri.parse(ref)
        val (name, size) = queryColumns(uri) ?: return null
        return UserFileGrant(
            ref = ref,
            name = name,
            sizeBytes = size,
            mimeType = context.contentResolver.getType(uri),
        )
    }

    override suspend fun readGranted(ref: String): ByteArray? = try {
        context.contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
    } catch (_: SecurityException) {
        null // grant gone (provider revoked / reboot semantics changed)
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    override suspend fun releaseGranted(ref: String): Boolean = true

    /**
     * One cursor over both OpenableColumns. Null when the provider serves
     * nothing for the uri (unknown/revoked grant); name/size individually
     * null when that column is missing — cloud providers legitimately
     * report no SIZE until the file is materialized.
     */
    private fun queryColumns(uri: Uri): Pair<String?, Long?>? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                name to size
            }
        }
    } catch (_: Exception) {
        null
    }
}
