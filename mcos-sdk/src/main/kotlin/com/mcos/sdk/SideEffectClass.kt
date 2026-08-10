package com.mcos.sdk

/**
 * Side-effect classification for command permission policy.
 * Matches [02-command-protocol.md §8.1].
 */
enum class SideEffectClass {
    /** No lasting change — e.g. weather.today, camera.scan */
    read,

    /** Creates or modifies data — e.g. camera.capture, sys.notify */
    write,

    /** Deletes or is irreversible — e.g. photo.clean */
    destructive,

    /** Leaves device boundary — e.g. mail.send, mcp.* */
    network,

    /** Actuates physical device or IoT — e.g. home.light.on, vpn.connect */
    control
}
