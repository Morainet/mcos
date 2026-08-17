package com.morainet.mcos.runtime.marketplace

/**
 * Normative install state machine ([09-marketplace.md §7.0]).
 *
 * Transitions:
 * ```
 * NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> STAGING -> LOADING -> INSTALLED
 * VERIFYING/LOADING -> FAILED                    (verification / load error)
 * INSTALLED -> UPDATE_AVAILABLE                  (marketplace has newer version)
 * UPDATE_AVAILABLE -> DOWNLOADING                (user taps update)
 * INSTALLED/DISABLED -> UNINSTALLING -> NOT_INSTALLED
 * INSTALLED -> DISABLED                          (trust downgrade / quarantine)
 * DISABLED -> INSTALLED                          (user re-enables)
 * FAILED -> NOT_INSTALLED                        (cleanup)
 * ```
 */
enum class InstallState {
    /** Plugin not on device. */
    NOT_INSTALLED,

    /** Artifact download in progress. */
    DOWNLOADING,

    /** SHA-256 + signature verification. */
    VERIFYING,

    /** Copying to Runtime download dir. */
    STAGING,

    /** Runtime registering descriptors. */
    LOADING,

    /** Active and ready. */
    INSTALLED,

    /** Newer version in marketplace. */
    UPDATE_AVAILABLE,

    /** Installed but trust-downgraded / quarantined. */
    DISABLED,

    /** Drain in progress (canceling running steps). */
    UNINSTALLING,

    /** Download/verify/load error (cleanup needed). */
    FAILED,
}

/**
 * Progress/state event emitted during an install/update/uninstall
 * ([09-marketplace.md §7.1]).
 *
 * @param packageId the plugin being acted on.
 * @param state the current state.
 * @param version package version, when known.
 * @param percent 0..100 download/staging progress.
 * @param message human-readable detail (error reason on [InstallState.FAILED]).
 */
data class InstallProgress(
    val packageId: String,
    val state: InstallState,
    val version: String? = null,
    val percent: Int = 0,
    val message: String? = null,
)
