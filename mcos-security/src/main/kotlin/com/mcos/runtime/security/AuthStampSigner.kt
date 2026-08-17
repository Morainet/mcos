package com.mcos.runtime.security

import com.mcos.sdk.AuthStamp
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs and verifies [AuthStamp] instances.
 *
 * An AuthStamp is a caller-supplied credential that the Executor trusts after
 * checking expiry and permission coverage. Without a signature, any caller
 * could fabricate a stamp that grants arbitrary permissions (privilege
 * escalation). The runtime signs stamps it issues and rejects supplied stamps
 * whose signature does not verify.
 *
 * The signer is an interface so the executor wiring is never `null`
 * (null would silently skip signature checks — fail-open). Production uses
 * [HmacAuthStampSigner]; tests use the named [TrustingAuthStampSigner] to
 * opt out explicitly.
 */
interface AuthStampSigner {

    /** Returns a copy of [stamp] with [AuthStamp.signature] set. */
    fun sign(stamp: AuthStamp): AuthStamp

    /**
     * Returns true if [stamp] carries a valid signature for its current
     * payload. Unsigned stamps are rejected.
     */
    fun verify(stamp: AuthStamp): Boolean
}

/**
 * HMAC-SHA256 [AuthStampSigner].
 *
 * Matches [08-security.md 5.2]: the signed payload is
 * `(runId, commandId, pluginId, grantsUsed, issuedAt, expiresAt)`.
 *
 * @param key HMAC key. Defaults to a fresh process-scoped random key; in a
 *        device-bound deployment (V1) this should be derived from the device
 *        keystore.
 */
class HmacAuthStampSigner(
    private val key: ByteArray = SecureRandom().generateSeed(32)
) : AuthStampSigner {

    override fun sign(stamp: AuthStamp): AuthStamp = stamp.copy(signature = compute(stamp))

    override fun verify(stamp: AuthStamp): Boolean {
        if (stamp.signature.isEmpty()) return false
        val expected = compute(stamp)
        return constantTimeEquals(expected, stamp.signature)
    }

    private fun compute(stamp: AuthStamp): String {
        val payload = listOf(
            stamp.runId,
            stamp.commandId,
            stamp.pluginId,
            stamp.grantsUsed.sorted().joinToString(","),
            stamp.issuedAt.toString(),
            stamp.expiresAt.toString()
        ).joinToString("|")

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
