package com.morainet.mcos.android.host

import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges Compose's `ActivityResultContracts.RequestPermission()` launcher
 * to the suspend world of [HostServices][com.morainet.mcos.sdk.HostServices] —
 * the in-app runtime-permission prompt flow (04-plugin-sdk §6.3: a command
 * hitting a missing runtime grant prompts in-app instead of pointing the
 * user at system settings).
 *
 * Mirrors [ActivityResultBridge]: the Compose side attaches a [Prompter]
 * (an adapter over the registered launcher) and forwards every callback
 * through [onResult]; the service side calls [request] to suspend until the
 * user answers. While no Activity is registered — a headless schedule run
 * cold-started by an alarm — [request] returns null and the caller surfaces
 * an honest PERMISSION_DENIED.
 *
 * Pure Kotlin: the Compose launcher reaches this class only through the
 * [Prompter] fun interface, so the bridge semantics are JVM-unit-testable.
 */
class RuntimePermissionBridge {

    /** Launches the system permission dialog for [permission]. */
    fun interface Prompter {
        fun prompt(permission: String)
    }

    private var prompter: Prompter? = null
    private var pending: CompletableDeferred<Boolean?>? = null

    /** Attach the Compose-registered launcher adapter (latest wins). */
    fun attach(prompter: Prompter) {
        this.prompter = prompter
    }

    /**
     * Dispatch the user's answer. Must be called from the launcher's Compose
     * callback — `RequestPermission()` yields only a Boolean, so the pending
     * request (not a permission name) is what gets resolved. No pending
     * request → no-op.
     */
    fun onResult(granted: Boolean) {
        val d = synchronized(this) {
            val d = pending ?: return
            pending = null
            d
        }
        d.complete(granted)
    }

    /**
     * Prompt for [permission] and suspend until the user answers.
     *
     * @return the user's answer, or null when no [Prompter] is attached (no
     *   Activity — headless run) or another prompt is already in flight;
     *   callers surface an honest PERMISSION_DENIED in both cases.
     */
    suspend fun request(permission: String): Boolean? {
        val deferred = synchronized(this) {
            val p = prompter ?: return null
            // One dialog at a time: a second concurrent request completes
            // null immediately instead of clobbering the first awaiter.
            if (pending != null) return null
            CompletableDeferred<Boolean?>().also {
                pending = it
                p.prompt(permission)
            }
        }
        return deferred.await()
    }

    /** Cancel any pending prompt (e.g. the Activity is being destroyed). */
    fun cancelPending() {
        val d = synchronized(this) {
            val d = pending ?: return
            pending = null
            d
        }
        d.complete(null)
    }
}
