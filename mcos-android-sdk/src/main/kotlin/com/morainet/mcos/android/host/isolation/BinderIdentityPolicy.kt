package com.morainet.mcos.android.host.isolation

/**
 * §8.2 check 1 — Binder caller-UID identity ([08-security.md §8.2]):
 * every call crossing the isolation boundary is admitted only when the
 * caller's UID matches the UID the boundary was established for. Both
 * processes of one installed app share a UID, so the check is
 * *same-app-UID equality*; anything else (a different app binding the
 * exported-looking service, a shell uid) is rejected and auditable under
 * [AUDIT_REASON].
 *
 * Pure so the decision is unit-testable; the Binder adapter supplies
 * `Binder.getCallingUid()` as `callingUid` and the UID the connection was
 * bound for as `expectedUid`.
 */
object BinderIdentityPolicy {

    /** Audit/security-event code for a rejected cross-boundary call (§8.2 check 1). */
    const val AUDIT_REASON = "plugin.identity_mismatch"

    /** `true` when the call may cross the boundary. */
    fun check(callingUid: Int, expectedUid: Int): Boolean = callingUid == expectedUid
}
