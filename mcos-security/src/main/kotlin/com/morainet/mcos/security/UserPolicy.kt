package com.morainet.mcos.security

/**
 * User-controlled confirmation-tightening flags ([08-security.md §4.2],
 * hot-reloadable via [03-runtime.md §19] `userPolicy`).
 *
 * Every flag is **upgrade-only**: it can raise a decision toward more
 * confirmation (ALLOW → CONFIRM_*), never loosen one that an enterprise policy
 * or the kernel already tightened. That invariant is enforced structurally in
 * `DefaultPermissionKernel` — the flags gate additional CONFIRM paths, they
 * never grant.
 *
 * All default `false`: the pre-§19 behaviour is exactly `UserPolicy()`.
 *
 * @property confirmEveryNetwork every NETWORK-class command requires a
 *   confirmation (CONFIRM_ONCE) regardless of any cached session grant — the
 *   user wants to eyeball each outbound call.
 * @property backgroundEventsRequireForeground commands dispatched from a
 *   background source (EVENT / SCHEDULE triggers) always require confirmation
 *   regardless of side-effect class — "no silent background execution"
 *   ([08-security.md §4.2]). Breaks silent pre-authorized background recipes
 *   only when the user opts in.
 * @property disableSessionGrants CONFIRM_SESSION decisions are demoted to
 *   CONFIRM_ONCE, so a grant never outlives the single invocation that made it.
 */
data class UserPolicy(
    val confirmEveryNetwork: Boolean = false,
    val backgroundEventsRequireForeground: Boolean = false,
    val disableSessionGrants: Boolean = false,
) {
    /**
     * OR-combine two user policies: each flag is a tightening, so the union
     * (any source that set a flag wins) is the most-restrictive merge used by
     * `RuntimeConfigManager`.
     */
    fun or(other: UserPolicy): UserPolicy = UserPolicy(
        confirmEveryNetwork = confirmEveryNetwork || other.confirmEveryNetwork,
        backgroundEventsRequireForeground =
            backgroundEventsRequireForeground || other.backgroundEventsRequireForeground,
        disableSessionGrants = disableSessionGrants || other.disableSessionGrants,
    )
}
