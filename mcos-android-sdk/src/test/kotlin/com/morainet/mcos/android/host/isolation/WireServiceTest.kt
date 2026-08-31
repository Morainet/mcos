package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.security.HmacAuthStampSigner
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests for [WireService] — the pure serving cores the two Binder
 * endpoints delegate to (isolation slice 3b-final). These functions are the
 * ONLY logic between a Binder thread and the tested halves
 * ([IsolatedPluginRunner] / [IsolatedFacadeServer]); they must never throw
 * and must frame every failure as an error envelope.
 */
class WireServiceTest {

    private val signer = HmacAuthStampSigner("wire-key".toByteArray())
    private val admittedUid = 10_202

    private fun plugin(handler: CommandHandler): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = "wire.plugin", name = "Wire", version = "1.0.0",
            minRuntimeVersion = "0.1.0", description = "wire service test plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.test.Wire",
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf("wire.run" to handler)
    }

    private fun invocationFrame(
        commandId: String = "wire.run",
        auth: AuthStamp? = null,
    ): String {
        val invocation = IsolatedInvocation(
            pluginId = "wire.plugin", pluginVersion = "1.0.0", commandId = commandId,
            args = buildJsonObject { }, auth = auth,
            runId = "run-1", deadlineMs = Long.MAX_VALUE, source = "TEST",
        )
        return BinderWire.frame(IsolationOps.OP_INVOKE, IsolationCodec.encodeInvocation(invocation))
    }

    private fun decodeReplyPayload(reply: String) =
        IsolationCodec.decodeResult(BinderWire.unframe(reply)!!.second)

            ?: error("reply did not frame a CommandResult: $reply")

    // ── serveInvoke (plugin-process side) ───────────────────────────────

    @Test
    fun serveInvokeFramesTheRunnerReply() {
        val runner = IsolatedPluginRunner(
            plugin(object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Ok(JsonPrimitive("served"))
            }),
            IsolationChannel { _, _ -> error("facade not used by this handler") },
        )
        val reply = WireService.serveInvoke(invocationFrame(), runner)
        val ok = decodeReplyPayload(reply) as CommandResult.Ok
        assertEquals("served", (ok.value as JsonPrimitive).content)
    }

    @Test
    fun serveInvokeMapsARunnerErrorVerbatim() {
        val runner = IsolatedPluginRunner(
            plugin(object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    throw McosException("SOME_DENIAL", "denied by policy", retryable = false)
            }),
            IsolationChannel { _, _ -> error("facade not used by this handler") },
        )
        val reply = WireService.serveInvoke(invocationFrame(), runner)
        val err = decodeReplyPayload(reply) as CommandResult.Err
        assertEquals("SOME_DENIAL", err.code)
        assertEquals("denied by policy", err.message)
    }

    @Test
    fun serveInvokeOnAMalformedFrameReturnsAFramedErrorInsteadOfThrowing() {
        val runner = IsolatedPluginRunner(
            plugin(object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult = error("unreachable")
            }),
            IsolationChannel { _, _ -> error("unreachable") },
        )
        val reply = WireService.serveInvoke("{{{not a frame", runner)
        val err = decodeReplyPayload(reply) as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(WireFailureReasons.FRAME_DECODE, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun serveInvokeRefusesAFacadeDirectionOp() {
        val runner = IsolatedPluginRunner(
            plugin(object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult = error("unreachable")
            }),
            IsolationChannel { _, _ -> error("unreachable") },
        )
        val facadeFrame = BinderWire.frame(IsolationOps.OP_CLOCK_NOW, buildJsonObject { })
        val reply = WireService.serveInvoke(facadeFrame, runner)
        val err = decodeReplyPayload(reply) as CommandResult.Err
        assertEquals(WireFailureReasons.OP_MISMATCH, err.details["reason"]!!.jsonPrimitive.content)
    }

    // ── serveFacade (main-process side) ─────────────────────────────────

    @Test
    fun serveFacadeServesTheOpThroughTheFacadeServer() {
        val host = FakeHostServices()
        val server = IsolatedFacadeServer(host, signer, "wire.plugin", admittedUid)
        val frame = BinderWire.frame(IsolationOps.OP_CLOCK_NOW, IsolationCodec.encodeCall(buildJsonObject { }, null))

        val reply = WireService.serveFacade(frame, server, admittedUid)

        val payload = BinderWire.unframe(reply)!!.second
        assertEquals(host.clock.nowMs(), payload["nowMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun serveFacadeEnforcesTheIdentityGateBeforeTheHostFacade() {
        val net = CapturingNetService()
        val host = FakeHostServices(net = net)
        val server = IsolatedFacadeServer(host, signer, "wire.plugin", admittedUid)
        val frame = BinderWire.frame(IsolationOps.OP_CLOCK_NOW, IsolationCodec.encodeCall(buildJsonObject { }, null))

        val reply = WireService.serveFacade(frame, server, callingUid = 66_666)

        val err = IsolationCodec.decodeError(BinderWire.unframe(reply)!!.second)
        assertNotNull(err)
        assertEquals("PERMISSION_DENIED", err!!.code)
        assertEquals(BinderIdentityPolicy.AUDIT_REASON, err.details["reason"]!!.jsonPrimitive.content)
    }
}
