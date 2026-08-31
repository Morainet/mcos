package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.sdk.Artifact
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.runtime.core.executor.IsolatedInvocation
import com.morainet.mcos.runtime.core.error.McosErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IsolatedPluginRunner (item 42, slice 3b part 1): the plugin-process
 * execution half — decode, identity gate, ctx construction with the proxy
 * facade, local deadline, and honest error envelopes. Proves the runner
 * never trusts the envelope's claims: wrong plugin, missing handler, and a
 * stamp describing a different invocation are all refused or stripped
 * before the handler runs.
 */
class IsolatedPluginRunnerTest {

    private val pluginId = "test.isolated"
    private var lastCtx: ExecutionContext? = null
    private var runCount = 0

    /** A channel that records ops; facade calls in these tests are only shaped, not served. */
    private class RecordingChannel(var reply: JsonObject = JsonObject(emptyMap())) : IsolationChannel {
        val ops = mutableListOf<String>()
        override suspend fun call(op: String, envelope: JsonObject): JsonObject {
            ops.add(op)
            return reply
        }
    }

    private fun plugin(handler: CommandHandler): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = "Isolated", version = "1.4.0",
            minRuntimeVersion = "0.1.0",
            description = "runner test plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.test.Isolated",
            commands = listOf(),
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf("iso.run" to handler)
    }

    private fun invocation(
        commandId: String = "iso.run",
        auth: AuthStamp? = null,
        deadlineIn: Long = 60_000L,
    ) = IsolatedInvocation(
        pluginId = pluginId,
        pluginVersion = "1.4.0",
        commandId = commandId,
        args = buildJsonObject { put("n", 7) },
        auth = auth,
        runId = "run-1",
        deadlineMs = 1_700_000_100_000L + deadlineIn,
        source = "CHAT",
    )

    private fun stampFor(
        runId: String = "run-1",
        commandId: String = "iso.run",
        pluginId: String = this.pluginId,
    ) = AuthStamp(
        runId = runId, commandId = commandId, pluginId = pluginId,
        grantsUsed = setOf("network.api.example.test"),
        issuedAt = 1_700_000_000_000L, expiresAt = 1_700_000_400_000L, signature = "sig",
    )

    private fun run(handler: CommandHandler) = IsolatedPluginRunner(
        plugin(handler),
        RecordingChannel(),
        nowMs = { 1_700_000_100_000L },
    )

    @Test
    fun happyPathBuildsCtxFromTheInvocationAndEncodesOk() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                lastCtx = ctx; runCount++
                return CommandResult.Ok(
                    kotlinx.serialization.json.JsonPrimitive("done"),
                    artifacts = listOf(Artifact(type = "image", uri = "file:///tmp/x.jpg")),
                )
            }
        })
        val reply = runner.serveInvoke(IsolationCodec.encodeInvocation(invocation(auth = stampFor())))
        val result = IsolationCodec.decodeResult(reply) as CommandResult.Ok
        assertEquals("done", (result.value as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, result.artifacts.size)
        assertEquals("file:///tmp/x.jpg", result.artifacts.single().uri)
        assertEquals("run-1", lastCtx!!.runId)
        assertEquals("iso.run", lastCtx!!.commandId)
        assertEquals(buildJsonObject { put("n", 7) }, lastCtx!!.args)
        assertEquals(stampFor(), lastCtx!!.auth)
        assertEquals(1_700_000_160_000L, lastCtx!!.deadline)
        assertTrue(lastCtx!!.services is IsolatedHostServicesProxy)
    }

    @Test
    fun startPassesTheProxyFacadeToOnLoad() = runTest {
        var loaded: HostServices? = null
        val p = object : McosPlugin by plugin(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext) =
                CommandResult.Ok(kotlinx.serialization.json.JsonPrimitive("x"))
        }) {
            override suspend fun onLoad(services: HostServices) { loaded = services }
        }
        IsolatedPluginRunner(p, RecordingChannel()).start()
        assertTrue(loaded is IsolatedHostServicesProxy)
    }

    @Test
    fun invocationForAnotherPluginIsRefusedWithoutRunningTheHandler() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult { runCount++; error("unreachable") }
        })
        val bad = invocation().copy(pluginId = "other.plugin")
        val err = IsolationCodec.decodeError(runner.serveInvoke(IsolationCodec.encodeInvocation(bad)))!!
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("plugin.identity_mismatch", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, runCount)
    }

    @Test
    fun unknownCommandSurfacesUnknownCommand() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult { runCount++; error("unreachable") }
        })
        val reply = runner.serveInvoke(IsolationCodec.encodeInvocation(invocation(commandId = "iso.gone")))
        val err = IsolationCodec.decodeError(reply)!!
        assertEquals("UNKNOWN_COMMAND", err.code)
        assertEquals(IsolatedPluginRunner.HANDLER_MISSING, err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, runCount)
    }

    @Test
    fun stampDescribingADifferentInvocationIsStripped() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                lastCtx = ctx
                return CommandResult.Ok(kotlinx.serialization.json.JsonPrimitive("x"))
            }
        })
        // Signed by the runtime, but minted for a different run — it must
        // not reach the plugin's facade as this run's authorization.
        val foreign = invocation(auth = stampFor(runId = "run-OTHER"))
        runner.serveInvoke(IsolationCodec.encodeInvocation(foreign))
        assertNull(lastCtx!!.auth)

        val matching = invocation(auth = stampFor())
        runner.serveInvoke(IsolationCodec.encodeInvocation(matching))
        assertEquals("run-1", lastCtx!!.auth!!.runId)
    }

    @Test
    fun handlerMcosExceptionKeepsCodeAndReason() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                throw McosException(
                    code = McosErrorCode.PERMISSION_DENIED.name,
                    message = "denied by scope gate",
                    details = buildJsonObject { put("reason", "stamp_scope_mismatch") },
                )
        })
        val err = IsolationCodec.decodeError(runner.serveInvoke(IsolationCodec.encodeInvocation(invocation())))!!
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("stamp_scope_mismatch", err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlerCrashBecomesPluginErrorNotAThrownException() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = throw IllegalStateException("boom")
        })
        val err = IsolationCodec.decodeError(runner.serveInvoke(IsolationCodec.encodeInvocation(invocation())))!!
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(IsolatedPluginRunner.HANDLER_CRASH, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun pastDeadlineInvocationTimesOutWithoutRunningTheHandler() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult { runCount++; error("unreachable") }
        })
        val err = IsolationCodec.decodeError(
            runner.serveInvoke(IsolationCodec.encodeInvocation(invocation(deadlineIn = -1_000L))),
        )!!
        assertEquals("TIMEOUT", err.code)
        assertEquals(IsolatedPluginRunner.DEADLINE_EXCEEDED, err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, runCount)
    }

    @Test
    fun unparseableEnvelopeBecomesDecodeFailure() = runTest {
        val runner = run(object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult { runCount++; error("unreachable") }
        })
        val err = IsolationCodec.decodeError(runner.serveInvoke(buildJsonObject { put("junk", true) }))!!
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(IsolatedPluginRunner.INVOCATION_DECODE_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, runCount)
    }
}
