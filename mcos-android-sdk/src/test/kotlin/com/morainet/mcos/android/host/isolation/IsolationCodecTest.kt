package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.sdk.Artifact
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IsolationCodec (item 41, slice 3a): every type that crosses the process
 * boundary round-trips through the wire object losslessly, decode is lenient
 * (unknown fields ignored, malformed input → null instead of throw).
 */
class IsolationCodecTest {

    private val invocation = IsolatedInvocation(
        pluginId = "com.example.plugin",
        pluginVersion = "1.2.3",
        commandId = "example.fetch",
        args = buildJsonObject { put("url", "https://example.test") },
        auth = stamp(),
        runId = "run-42",
        deadlineMs = 1_800_000_000_000L,
        source = "CHAT",
    )

    private fun stamp(signature: String = "sig-1") = AuthStamp(
        runId = "run-42",
        commandId = "example.fetch",
        pluginId = "com.example.plugin",
        grantsUsed = setOf("network.example.test", "files.read"),
        issuedAt = 1_700_000_000_000L,
        expiresAt = 1_700_000_300_000L,
        signature = signature,
    )

    // ── invocation ────────────────────────────────────────────────────────

    @Test
    fun invocationRoundTripsWithAuth() {
        val decoded = IsolationCodec.decodeInvocation(IsolationCodec.encodeInvocation(invocation))
        assertEquals(invocation, decoded)
    }

    @Test
    fun invocationRoundTripsWithoutAuth() {
        val bare = invocation.copy(auth = null)
        assertEquals(bare, IsolationCodec.decodeInvocation(IsolationCodec.encodeInvocation(bare)))
    }

    @Test
    fun invocationDecodeIgnoresUnknownFields() {
        val json = IsolationCodec.encodeInvocation(invocation).let {
            JsonObject(it.toMap() + ("futureField" to kotlinx.serialization.json.JsonPrimitive("x")))
        }
        assertEquals(invocation, IsolationCodec.decodeInvocation(json))
    }

    @Test
    fun invocationDecodeReturnsNullOnMissingRequiredField() {
        val json = IsolationCodec.encodeInvocation(invocation).let { JsonObject(it.toMap() - "runId") }
        assertNull(IsolationCodec.decodeInvocation(json))
    }

    @Test
    fun invocationDecodeReturnsNullOnGarbage() {
        assertNull(IsolationCodec.decodeInvocation(JsonObject(mapOf("pluginId" to kotlinx.serialization.json.JsonArray(emptyList())))))
    }

    // ── command result ───────────────────────────────────────────────────

    @Test
    fun okResultRoundTripsValueAndArtifacts() {
        val ok = CommandResult.Ok(
            value = buildJsonObject { put("answer", 42) },
            artifacts = listOf(
                Artifact(type = "image", uri = "file:///tmp/a.jpg", mimeType = "image/jpeg"),
                Artifact(type = "doc", uri = "file:///tmp/b", metadata = mapOf("pageCount" to "3")),
            ),
        )
        assertEquals(ok, IsolationCodec.decodeResult(IsolationCodec.encodeResult(ok)))
    }

    @Test
    fun errResultRoundTripsCodeRetryableAndDetails() {
        val err = CommandResult.Err(
            code = "PERMISSION_DENIED",
            message = "denied by scope gate",
            retryable = false,
            details = buildJsonObject { put("reason", "stamp_scope_mismatch") },
        )
        assertEquals(err, IsolationCodec.decodeResult(IsolationCodec.encodeResult(err)))
    }

    @Test
    fun resultDecodeReturnsNullOnNeitherOkNorError() {
        assertNull(IsolationCodec.decodeResult(buildJsonObject { put("surprise", 1) }))
    }

    // ── call envelope + stamp ────────────────────────────────────────────

    @Test
    fun callEnvelopeCarriesStampWhenPresent() {
        val (args, decodedStamp) = IsolationCodec.decodeCall(
            IsolationCodec.encodeCall(buildJsonObject { put("key", "k1") }, stamp()),
        )
        assertEquals(buildJsonObject { put("key", "k1") }, args)
        assertEquals(stamp(), decodedStamp)
    }

    @Test
    fun callEnvelopeOmitsStampWhenAbsent() {
        val (args, decodedStamp) = IsolationCodec.decodeCall(
            IsolationCodec.encodeCall(buildJsonObject { put("key", "k1") }, null),
        )
        assertEquals(buildJsonObject { put("key", "k1") }, args)
        assertNull(decodedStamp)
    }

    @Test
    fun stampRoundTripPreservesGrantsOrderIndependently() {
        val encoded = IsolationCodec.encodeStamp(stamp())
        // grants are encoded sorted so equal sets encode identically
        val items = encoded["grantsUsed"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(items.sorted(), items)
        assertEquals(stamp(), IsolationCodec.decodeStamp(encoded))
    }

    @Test
    fun unsignedStampSurvivesTheWire() {
        val unsigned = stamp(signature = "")
        assertEquals(unsigned, IsolationCodec.decodeStamp(IsolationCodec.encodeStamp(unsigned)))
    }

    // ── shared error envelope ────────────────────────────────────────────

    @Test
    fun errorEnvelopeRoundTripsAndStaysAbsentOnSuccess() {
        val envelope = IsolationCodec.encodeError(
            code = "UNAVAILABLE",
            message = "no sandbox",
            retryable = true,
            details = buildJsonObject { put("reason", "facade_internal_error") },
        )
        val decoded = IsolationCodec.decodeError(envelope)
        assertNotNull(decoded)
        assertEquals("UNAVAILABLE", decoded!!.code)
        assertEquals("no sandbox", decoded.message)
        assertEquals(true, decoded.retryable)
        assertEquals("facade_internal_error", decoded.details["reason"]!!.jsonPrimitive.content)

        assertNull(IsolationCodec.decodeError(buildJsonObject { put("value", "ok") }))
    }
}
