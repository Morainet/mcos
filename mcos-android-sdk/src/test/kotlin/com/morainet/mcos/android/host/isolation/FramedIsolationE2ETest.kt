package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The full isolation chain through the FRAMED wire (isolation slice
 * 3b-final): identical composition to [IsolationEndToEndTest] except both
 * hops now cross [PipeIsolationChannel] + [BinderWire] framing + [WireService]
 * — the exact serving cores and channel objects the Binder endpoints
 * ([BinderWirePipe]/[FacadeBinderEndpoint]/[InvokeBinderEndpoint]) shuttle.
 * After these tests the only unexercised behavior left in the transport is
 * the Binder kernel itself (Parcel marshalling, process split,
 * `getCallingUid`) — the on-device verification item.
 */
class FramedIsolationE2ETest {

    private val pluginId = "framed.isolated"
    private val commandId = "framed.fetch"
    private val admittedUid = 10_303
    private val signer = HmacAuthStampSigner("framed-key".toByteArray())

    /** What crosses each hop, for assertions. */
    private class FramedHarness(
        val net: CapturingNetService,
        val secureStore: MapSecureStore,
        val sandbox: FakeFlatSandbox,
        val executor: Executor,
        val invokeCodes: MutableList<Int>,
        val facadeCodes: MutableList<Int>,
        val facadeOps: MutableList<String>,
    )

    /**
     * @param invokePipeBehavior overrides the main→plugin pipe (default:
     *        serve faithfully through [WireService.serveInvoke]).
     */
    private fun harness(
        handler: CommandHandler,
        invokePipeBehavior: ((code: Int, request: String) -> String)? = null,
    ): FramedHarness {
        val net = CapturingNetService()
        val secureStore = MapSecureStore()
        val sandbox = FakeFlatSandbox()
        val host: HostServices = FakeHostServices(net = net, secureStore = secureStore, sandbox = sandbox)
        val invokeCodes = mutableListOf<Int>()
        val facadeCodes = mutableListOf<Int>()

        // main-process facade endpoint behavior (what FacadeBinderEndpoint does)
        val facadeServer = IsolatedFacadeServer(host, signer, pluginId, admittedUid)
        val facadeOps = mutableListOf<String>()
        val facadePipe = WirePipe { code, request ->
            facadeCodes.add(code)
            facadeOps.add(BinderWire.unframe(request)!!.first)
            WireService.serveFacade(request, facadeServer, admittedUid)
        }

        // plugin-process invoke endpoint behavior (what InvokeBinderEndpoint does)
        val runner = IsolatedPluginRunner(
            pluginWith(handler),
            PipeIsolationChannel(facadePipe, BinderWire.CODE_FACADE, Dispatchers.Unconfined),
        )
        val invokePipe = invokePipeBehavior?.let { behavior ->
            WirePipe { code, request ->
                invokeCodes.add(code)
                behavior(code, request)
            }
        } ?: WirePipe { code, request ->
            invokeCodes.add(code)
            WireService.serveInvoke(request, runner)
        }

        // main process: manifest-only registration + the real Executor over
        // the framed transport. Item 45: registerManifest IS the production
        // manifest-only path — descriptors from the wire manifest, handler
        // slots holding the isolation stub.
        val registry = CommandRegistry()
        registry.registerManifest(pluginWith(handler = null).manifest, TrustLevel.MARKETPLACE_VERIFIED)
        val executor = Executor(
            registry,
            host,
            SecurityConfig.permissive().copy(signer = signer),
            isolationHost = TransportIsolationHost(
                PipeIsolationChannel(invokePipe, BinderWire.CODE_INVOKE, Dispatchers.Unconfined),
            ),
        )
        return FramedHarness(net, secureStore, sandbox, executor, invokeCodes, facadeCodes, facadeOps)
    }

    private fun pluginWith(handler: CommandHandler?): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = "Framed", version = "1.0.0",
            minRuntimeVersion = "0.1.0", description = "framed wire e2e plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.test.Framed",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId, version = "1.0.0", title = "Fetch",
                    description = "network fetch through the framed isolation wire",
                    sideEffectClass = SideEffectClass.network,
                ),
            ),
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> =
            if (handler == null) emptyMap() else mapOf(commandId to handler)
    }

    private fun networkStamp(domain: String = "api.example.test"): AuthStamp {
        val now = System.currentTimeMillis()
        return signer.sign(
            AuthStamp(
                runId = "", commandId = commandId, pluginId = pluginId,
                grantsUsed = setOf("network.$domain"),
                issuedAt = now, expiresAt = now + 300_000, signature = "",
            ),
        )
    }

    @Test
    fun fullChainThroughTheFramedWireRunsTheHandlerWithScopedFacade() = runTest {
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                val resp = ctx.services.net.request(
                    method = "POST",
                    url = "https://api.example.test/v1/framed",
                    body = "{{secret.framedToken}}",
                )
                ctx.services.sandbox!!.write("out/framed.txt", "framed-ok".toByteArray())
                return CommandResult.Ok(JsonPrimitive(resp.body ?: "null"))
            }
        })
        h.secureStore.put("framedToken", "tok-framed")

        val result = h.executor.execute(commandId, auth = networkStamp(), source = "CHAT")

        val ok = result as CommandResult.Ok
        assertEquals("net-ok", (ok.value as JsonPrimitive).content)
        assertEquals(1, h.net.calls)
        // every hop crossed the framed wire with the right transaction code
        assertEquals(listOf(BinderWire.CODE_INVOKE), h.invokeCodes)
        // two facade crossings, in handler order: the net request (with
        // {{secret.*}} resolved main-side by the §9.2 decorator — the secret
        // itself never crosses the wire) then the sandbox write
        assertEquals(listOf(BinderWire.CODE_FACADE, BinderWire.CODE_FACADE), h.facadeCodes)
        assertEquals(listOf(IsolationOps.OP_NET_REQUEST, IsolationOps.OP_SANDBOX_WRITE), h.facadeOps)
        // §8.3 namespacing survived the double framing
        assertEquals("framed-ok", String(h.sandbox.files["$pluginId/out/framed.txt"]!!))
    }

    @Test
    fun pluginProcessDeathOnTheFramedWireMapsToPluginError() = runTest {
        val h = harness(
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult = error("unreachable")
            },
            invokePipeBehavior = { _, _ -> throw IllegalStateException("dead object") },
        )

        val result = h.executor.execute(commandId, auth = networkStamp())

        val err = result as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(TransportIsolationHost.TRANSPORT_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun wellFramedButResultlessReplyOnTheFramedWireMapsToDecodeFailure() = runTest {
        val h = harness(
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult = error("unreachable")
            },
            // a valid frame whose payload is not a CommandResult envelope
            invokePipeBehavior = { _, _ ->
                BinderWire.frame(IsolationOps.OP_INVOKE, buildJsonObject { put("mystery", 1) })
            },
        )

        val result = h.executor.execute(commandId, auth = networkStamp())

        val err = result as CommandResult.Err
        assertEquals("PLUGIN_ERROR", err.code)
        assertEquals(TransportIsolationHost.DECODE_FAILURE, err.details["reason"]!!.jsonPrimitive.content)
    }
}
