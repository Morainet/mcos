package com.morainet.mcos.android.host.isolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BinderIdentityPolicy (item 41, slice 3a): §8.2 check 1 as a pure layer —
 * the transport-reported caller UID must equal the UID the server admitted
 * for this plugin connection. The Binder wire-up (getCallingUid) is slice 3b;
 * the equality decision and its audit reason are settled here.
 */
class BinderIdentityPolicyTest {

    @Test
    fun sameUidPasses() {
        assertTrue(BinderIdentityPolicy.check(callingUid = 10_101, expectedUid = 10_101))
    }

    @Test
    fun differentUidFails() {
        assertFalse(BinderIdentityPolicy.check(callingUid = 10_102, expectedUid = 10_101))
    }

    @Test
    fun zeroUidNeverMatchesAnAdmittedUid() {
        // A transport that failed to report identity hands in 0 — deny.
        assertFalse(BinderIdentityPolicy.check(callingUid = 0, expectedUid = 10_101))
    }

    @Test
    fun auditReasonIsTheSpecName() {
        assertEquals("plugin.identity_mismatch", BinderIdentityPolicy.AUDIT_REASON)
    }
}
