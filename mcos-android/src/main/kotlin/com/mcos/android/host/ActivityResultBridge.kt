package com.mcos.android.host

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges Compose's [ActivityResultLauncher] to the suspend world of
 * [HostServices][com.mcos.sdk.HostServices].
 *
 * The Compose side builds a launcher with [contract], calls [attach],
 * and forwards every callback through [onResult]. The service side
 * calls [launch] to suspend until the activity result arrives.
 */
class ActivityResultBridge {

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<ActivityResult?>? = null

    /** Contract to build the launcher with in Compose. */
    val contract: ActivityResultContracts.StartActivityForResult =
        ActivityResultContracts.StartActivityForResult()

    /** Attach the Compose-registered launcher. */
    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    /**
     * Dispatch an activity result. Must be called from the launcher's
     * Compose callback (e.g. [rememberLauncherForActivityResult][androidx.activity.compose.rememberLauncherForActivityResult]).
     */
    fun onResult(result: ActivityResult) {
        val d = pending ?: return
        pending = null
        d.complete(result)
    }

    /**
     * Launch an intent and suspend until its result arrives.
     *
     * @return the [ActivityResult], or null when no launcher is attached
     *   or the launch failed.
     */
    suspend fun launch(intent: Intent): ActivityResult? {
        val l = launcher ?: return null
        val deferred = CompletableDeferred<ActivityResult?>()
        pending = deferred
        l.launch(intent)
        return deferred.await()
    }

    /** Cancel any pending launch (e.g. activity being destroyed). */
    fun cancelPending() {
        val d = pending ?: return
        pending = null
        d.complete(null)
    }
}
