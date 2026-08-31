package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * TransportIsolationHost (item 41, slice 3a): the Executor-facing
 * [com.morainet.mcos.runtime.core.executor.IsolationHost] over an
 * [IsolationChannel]. Results unmarshal losslessly; a transport failure —
 * process death, dead Binder — maps to an honest PLUGIN_ERROR Err instead of
 * an exception (08 §8.1 crash isolation), while cancellation still
 * propagates.
 */
class TransportIsolationHostTest {

    private val invocation = IsolatedInvocation(
        pluginId = "com.example.plugin",
        pluginVersion = "2.0.0",
        commandId = "example.fetch",
        args = buildJsonObject { put("url", "https://api.example.test") },
        auth = AuthStamp(
            runId = "run-7",
            commandId = "example.fetch",
            pluginId = "com.example.plugin",
            grantsUsed = setOf("network.api.example.test"),
            issuedAt = 1_700_000_000_000L,
            expiresAt = 1_700_000_300_000L,
            signature = "sig",
        ),
        runId = "run-7",
        deadlineMs = 1_800_000_000_000L,
        source = "SCHEDULE",
    )

    @Test
    fun invokeEncodesTheInvocationAndUnmarshalsOk() = runTest {
        var seenOp: String? = null
        var seenInvocation: IsolatedInvocation? = null
        val capturing = IsolationChannel { op, envelope ->
            seenOp = op
            seenInvocation = IsolationCodec.decodeInvocation(envelope)
            IsolationCodec.encodeResult(CommandResult.Ok(buildJsonObject { put("done", true) }))
        }
        val result = TransportIsolationHost(capturing).invoke(invocation)
        assertEquals(IsolationOps.OP_INVOKE, seenOp)
        assertEquals(invocation, seenInvocation)
        assertTrue(result is CommandResult.Ok)
        assertEquals(
            "true",
            (result as CommandResult.Ok).value.let { (it as? JsonObject)?.get("done")?.jsonPrimitive?.content },
        )
    }

    @Test
    fun errResultsCrossLosslessly() = runTest {
        val err = CommandResult.Err(
            code = "PERMISSION_DENIED",
            message = "scope gate denial",
            retryable = false,
            details = buildJsonObject { put("reason", "stamp_scope_mismatch") },
        )
        val host = TransportIsolationHost(IsolationChannel { _, _ -> IsolationCodec.encodeResult(err) })
        assertEquals(err, host.invoke(invocation))
    }

    @Test
    fun channelFailureBecomesPluginErrorErrNotAnException() = runTest {
        val host = TransportIsolationHost(
            IsolationChannel { _, _ -> throw IOException("plugin process died (binder)") },
        )
        val result = host.invoke(invocation)
        assertTrue(result is CommandResult.Err)
        val err = result as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(TransportIsolationHost.TRANSPORT_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(false, err.retryable)
    }

    @Test
    fun unparseableReplyBecomesDecodeFailureErr() = runTest {
        val host = TransportIsolationHost(
            IsolationChannel { _, _ -> buildJsonObject { put("garbage", 1) } },
        )
        val err = host.invoke(invocation) as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(TransportIsolationHost.DECODE_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancellationStillPropagates() = runTest {
        val host = TransportIsolationHost(
            IsolationChannel { _, _ -> throw CancellationException("deadline") },
        )
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { host.invoke(invocation) } }
    }
}
