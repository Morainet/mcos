package com.morainet.mcos.sdk

/**
 * Side-effect classification for command permission policy.
 * Matches [02-command-protocol.md 8.1].
 *
 * **Ordinal order is semantically significant**: ascending severity
 * `read < write < network < destructive < control`. Code that compares
 * ordinals (e.g. `sideEffectClass >= write`) relies on this ordering.
 * Do NOT reorder these constants without auditing all ordinal comparisons.
 */
enum class SideEffectClass {
    /** No lasting change — e.g. weather.today, camera.scan */
    read,

    /** Creates or modifies data — e.g. camera.capture, sys.notify */
    write,

    /** Leaves device boundary — e.g. mail.send, mcp.* */
    network,

    /** Deletes or is irreversible — e.g. photo.clean */
    destructive,

    /** Actuates physical device or IoT — e.g. home.light.on, vpn.connect */
    control
}
