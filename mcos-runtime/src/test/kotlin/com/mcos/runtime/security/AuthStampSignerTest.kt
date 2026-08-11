package com.mcos.runtime.security

import com.mcos.sdk.AuthStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [AuthStampSigner] — AuthStamp HMAC signature (issue #3).
 */
class AuthStampSignerTest {

    private fun stamp(
        runId: String = "run-1",
        commandId: String = "cmd.test",
        pluginId: String = "test.plugin",
        grantsUsed: Set<String> = setOf("network.read"),
        issuedAt: Long = 1000L,
        expiresAt: Long = 60000L,
    ) = AuthStamp(runId, commandId, pluginId, grantsUsed, issuedAt, expiresAt)

    @Test
    fun `sign produces non-empty signature`() {
        val signer = AuthStampSigner()
        val signed = signer.sign(stamp())
        assertTrue(signed.signature.isNotEmpty())
        assertNotEquals("", signed.signature)
    }

    @Test
    fun `verify accepts a validly signed stamp`() {
        val signer = AuthStampSigner()
        val signed = signer.sign(stamp())
        assertTrue(signer.verify(signed))
    }

    @Test
    fun `verify rejects unsigned stamp`() {
        val signer = AuthStampSigner()
        assertFalse(signer.verify(stamp()))
    }

    @Test
    fun `verify rejects tampered runId`() {
        val signer = AuthStampSigner()
        val signed = signer.sign(stamp(runId = "run-1"))
        val tampered = signed.copy(runId = "run-999")
        assertFalse(signer.verify(tampered))
    }

    @Test
    fun `verify rejects tampered grants`() {
        val signer = AuthStampSigner()
        val signed = signer.sign(stamp(grantsUsed = setOf("network.read")))
        val tampered = signed.copy(grantsUsed = setOf("network.write"))
        assertFalse(signer.verify(tampered))
    }

    @Test
    fun `verify rejects tampered expiry`() {
        val signer = AuthStampSigner()
        val signed = signer.sign(stamp(expiresAt = 60000L))
        val tampered = signed.copy(expiresAt = 99999999L)
        assertFalse(signer.verify(tampered))
    }

    @Test
    fun `stamps from different signers cannot be verified`() {
        val signerA = AuthStampSigner()
        val signerB = AuthStampSigner()
        val signed = signerA.sign(stamp())
        assertFalse(signerB.verify(signed))
    }

    @Test
    fun `sign is deterministic for identical payload`() {
        val signer = AuthStampSigner()
        val s1 = signer.sign(stamp())
        val s2 = signer.sign(stamp())
        assertEquals(s1.signature, s2.signature)
    }

    @Test
    fun `grant set order does not affect signature`() {
        val signer = AuthStampSigner()
        val s1 = signer.sign(stamp(grantsUsed = setOf("a", "b", "c")))
        val s2 = signer.sign(stamp(grantsUsed = setOf("c", "a", "b")))
        assertEquals(s1.signature, s2.signature)
        assertTrue(signer.verify(s1))
    }

    @Test
    fun `sign does not mutate the original stamp`() {
        val signer = AuthStampSigner()
        val original = stamp()
        val signed = signer.sign(original)
        assertEquals("", original.signature)
        assertNotEquals("", signed.signature)
    }
}
