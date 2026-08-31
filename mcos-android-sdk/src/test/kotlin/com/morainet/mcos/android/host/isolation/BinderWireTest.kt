package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-protocol tests for the Binder byte transport's pure core (isolation
 * slice 3b-final): frame/unframe round-trips, lenient decode, and
 * [PipeIsolationChannel] — the exact channel object both Binder endpoints
 * sit behind — over a fake [WirePipe].
 */
class BinderWireTest {

    private val payload = buildJsonObject {
        put("q", "hello")
        put("nested", buildJsonObject { put("n", 1) })
    }

    // ── framing ─────────────────────────────────────────────────────────

    @Test
    fun frameRoundTripPreservesOpAndPayload() {
        val frame = BinderWire.frame(IsolationOps.OP_INVOKE, payload)
        val (op, decoded) = BinderWire.unframe(frame)!!
        assertEquals(IsolationOps.OP_INVOKE, op)
        assertEquals(payload, decoded)
    }

    @Test
    fun unframeIsLenientAboutUnknownFields() {
        val frame = """{"op":"clock.now","payload":{},"futureField":[1,2]}"""
        val (op, decoded) = BinderWire.unframe(frame)!!
        assertEquals("clock.now", op)
        assertEquals(buildJsonObject { }, decoded)
    }

    @Test
    fun unframeReturnsNullOnMalformedJson() {
        assertNull(BinderWire.unframe("{not json"))
        assertNull(BinderWire.unframe(""))
        assertNull(BinderWire.unframe("\"a string\""))
    }

    @Test
    fun unframeReturnsNullWhenOpIsMissingOrBlank() {
        assertNull(BinderWire.unframe("""{"payload":{}}"""))
        assertNull(BinderWire.unframe("""{"op":"","payload":{}}"""))
        assertNull(BinderWire.unframe("""{"op":123,"payload":{}}"""))
    }

    @Test
    fun unframeReturnsNullWhenPayloadIsNotAnObject() {
        assertNull(BinderWire.unframe("""{"op":"invoke","payload":"nope"}"""))
        assertNull(BinderWire.unframe("""{"op":"invoke"}"""))
    }

    // ── PipeIsolationChannel ────────────────────────────────────────────

    @Test
    fun channelFramesTheCallAndReturnsTheReplyPayload() = runTest {
        val codes = mutableListOf<Int>()
        val ops = mutableListOf<String>()
        val pipe = WirePipe { code, request ->
            codes.add(code)
            ops.add(BinderWire.unframe(request)!!.first)
            // serve faithfully: echo the payload back framed
            BinderWire.frame("reply.op", buildJsonObject { put("echo", JsonPrimitive(true)) })
        }
        val channel = PipeIsolationChannel(pipe, BinderWire.CODE_FACADE, Dispatchers.Unconfined)

        val reply = channel.call(IsolationOps.OP_CLOCK_NOW, buildJsonObject { put("x", 1) })

        assertEquals(listOf(BinderWire.CODE_FACADE), codes)
        assertEquals(listOf(IsolationOps.OP_CLOCK_NOW), ops)
        assertEquals(JsonPrimitive(true), reply.jsonObject["echo"]!!.jsonPrimitive)
    }

    @Test
    fun channelThrowsWireFormatExceptionOnAnUnparseableReply() = runTest {
        val channel = PipeIsolationChannel(
            WirePipe { _, _ -> "garbage-not-a-frame" },
            BinderWire.CODE_INVOKE,
        )
        var thrown: WireFormatException? = null
        try {
            channel.call(IsolationOps.OP_INVOKE, buildJsonObject { })
        } catch (e: WireFormatException) {
            thrown = e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun pipeTransportFailureBecomesTransportFailureThroughTheHost() = runTest {
        val host = TransportIsolationHost(
            PipeIsolationChannel(
                WirePipe { _, _ -> throw IllegalStateException("plugin process died") },
                BinderWire.CODE_INVOKE,
            ),
        )
        val result = host.invoke(
            IsolatedInvocation(
                pluginId = "p", pluginVersion = "1.0.0", commandId = "c",
                args = buildJsonObject { }, auth = null,
                runId = "r", deadlineMs = Long.MAX_VALUE, source = "TEST",
            ),
        )
        val err = result as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(TransportIsolationHost.TRANSPORT_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun unframeableReplyIsAHonestTransportFailureThroughTheHost() = runTest {
        // A corrupt pipe and a dead pipe are equally unusable at the
        // runtime-core seam — both map to transport failure.
        val host = TransportIsolationHost(
            PipeIsolationChannel(WirePipe { _, _ -> "" }, BinderWire.CODE_INVOKE, Dispatchers.Unconfined),
        )
        val result = host.invoke(
            IsolatedInvocation(
                pluginId = "p", pluginVersion = "1.0.0", commandId = "c",
                args = buildJsonObject { }, auth = null,
                runId = "r", deadlineMs = Long.MAX_VALUE, source = "TEST",
            ),
        )
        val err = result as CommandResult.Err
        assertEquals(TransportIsolationHost.TRANSPORT_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun wellFramedButResultlessReplyBecomesDecodeFailureThroughTheHost() = runTest {
        // A VALID frame whose payload is not a CommandResult envelope is a
        // decode failure — the wire worked, the reply did not parse.
        val host = TransportIsolationHost(
            PipeIsolationChannel(
                WirePipe { _, _ -> BinderWire.frame(IsolationOps.OP_INVOKE, buildJsonObject { put("mystery", 1) }) },
                BinderWire.CODE_INVOKE,
                Dispatchers.Unconfined,
            ),
        )
        val result = host.invoke(
            IsolatedInvocation(
                pluginId = "p", pluginVersion = "1.0.0", commandId = "c",
                args = buildJsonObject { }, auth = null,
                runId = "r", deadlineMs = Long.MAX_VALUE, source = "TEST",
            ),
        )
        val err = result as CommandResult.Err
        assertEquals(TransportIsolationHost.DECODE_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }
}
