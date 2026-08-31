package com.morainet.mcos.android

import com.morainet.mcos.android.host.InMemoryFacade
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpServerController (item 40): the server list persists without tokens
 * (only the `mcp.secret.<id>` key name ever reaches the persisted record),
 * the enable path goes through the runtime install pipeline at builtin trust
 * (register + grant + onLoad), and failures leave honest state instead of
 * throwing. Extracted from the demo ViewModel's MCP block, which these tests
 * replace with the SDK contract.
 */
class McpServerControllerTest {

    private class FakeSecureStore : SecureStore {
        val entries = mutableMapOf<String, ByteArray>()
        override suspend fun get(key: String): ByteArray? = entries[key]
        override suspend fun put(key: String, value: ByteArray) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
        override suspend fun keys(): Set<String> = entries.keys.toSet()
    }

    /**
     * Deterministic bridge: hands back [HelloPlugin] (one command,
     * `hello.world`), can be told to fail per server id (mutable so a test can
     * let a server connect once, then break the endpoint before a reconnect),
     * and records the secret *key* it was handed — the token itself must never
     * cross this seam.
     */
    private class FakeBridge(
        private val failFor: MutableSet<String> = mutableSetOf(),
        private val legacyPluginIds: Boolean = true,
    ) : McpServerBridge {
        var lastSecretKey: String? = null
            private set
        var discoverCalls = 0
            private set

        override suspend fun discover(record: McpServerRecord, secretKey: String?): BridgedMcpServer {
            discoverCalls++
            lastSecretKey = secretKey
            if (record.id in failFor) throw IllegalStateException("endpoint unreachable: ${record.id}")
            return BridgedMcpServer(
                plugin = HelloPlugin(),
                skippedTools = listOf(SkippedBridgedTool("odd_tool", "oneOf", "unmappable keyword")),
            )
        }

        /** Legacy-guess mode mirrors McpAdapter's `mcos.plugin.mcp.<id>` convention. */
        override fun pluginIdFor(serverId: String): String? =
            if (legacyPluginIds) "mcos.plugin.mcp.$serverId" else null
    }

    /** Real facade + registry + loader (trust gate: builtins always allow) — only the bridge is fake. */
    private class Fixture(
        val store: FakeSecureStore = FakeSecureStore(),
        val registry: CommandRegistry = CommandRegistry(),
        val bridge: FakeBridge = FakeBridge(),
    ) {
        val runtime: McosRuntime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPluginLoader(PluginLoader(trustGate = PluginTrustGate(), registry = registry))
            .withEventBus(TypedEventBus())
            .build()

        val controller = McpServerController(
            secureStore = store,
            runtime = runtime,
            registry = registry,
            hostServices = StubHostServices(InMemoryFacade()),
            permissionKernel = DefaultPermissionKernel(),
            bridge = bridge,
        )
    }

    @Test
    fun addPersistsRecordAndKeepsTokenOutOfTheList() = runTest {
        val f = Fixture()
        val result = f.controller.addServer("demo", "https://mcp.example.com", "tok-1")

        assertTrue(result is McpAddResult.Added)
        assertEquals("tok-1", f.store.entries[McpServerController.secretKeyOf("demo")]?.decodeToString())
        val persisted = f.store.entries.getValue(McpServerController.SERVERS_KEY).decodeToString()
        assertTrue(persisted.contains("\"demo\""))
        assertTrue(persisted.contains("\"https://mcp.example.com\""))
        // The persisted list must never contain the bearer token itself (04 §11.1).
        assertFalse(persisted.contains("tok-1"))
        // Added disabled — connecting is an explicit setEnabled.
        assertEquals(listOf(McpServerRecord("demo", "https://mcp.example.com", enabled = false)), f.controller.servers())
    }

    @Test
    fun addRejectsBlankAndDuplicate() = runTest {
        val f = Fixture()
        assertTrue(f.controller.addServer("demo", "https://mcp.example.com", null) is McpAddResult.Added)

        assertEquals(McpAddResult.Duplicate, f.controller.addServer("demo", "https://other.example.com", null))
        assertEquals(McpAddResult.Invalid, f.controller.addServer("  ", "https://mcp.example.com", null))
        assertEquals(McpAddResult.Invalid, f.controller.addServer("demo2", "", null))
        assertEquals(1, f.controller.servers().size)
    }

    @Test
    fun enableRegistersCommandsGrantsAndLearnsPluginId() = runTest {
        val f = Fixture()
        f.controller.addServer("demo", "https://mcp.example.com", null)

        val result = f.controller.setEnabled("demo", true)

        assertTrue(result is McpEnableResult.Enabled)
        result as McpEnableResult.Enabled
        assertEquals("example.hello", result.pluginId)
        assertEquals(1, result.commandsRegistered)
        assertEquals(1, result.skipped.size)
        assertTrue(f.registry.resolve("hello.world") is ResolveResult.Found)
        // No token configured → the bridge gets a null key, not a fabricated one.
        assertNull(f.bridge.lastSecretKey)
        val record = f.controller.servers().single()
        assertTrue(record.enabled)
        assertEquals("example.hello", record.pluginId)
    }

    @Test
    fun enablePassesSecretKeyWhenTokenConfigured() = runTest {
        val f = Fixture()
        f.controller.addServer("demo", "https://mcp.example.com", "tok-2")

        assertTrue(f.controller.setEnabled("demo", true) is McpEnableResult.Enabled)

        // Discovery authenticates via the SecureStore key; the token itself
        // never crosses the seam (the adapter resolves it from the store).
        assertEquals(McpServerController.secretKeyOf("demo"), f.bridge.lastSecretKey)
    }

    @Test
    fun discoveryFailureLeavesRecordDisabled() = runTest {
        val f = Fixture(bridge = FakeBridge(failFor = mutableSetOf("demo")))
        f.controller.addServer("demo", "https://mcp.example.com", null)

        val result = f.controller.setEnabled("demo", true)

        assertTrue(result is McpEnableResult.Error)
        result as McpEnableResult.Error
        assertEquals("endpoint unreachable: demo", result.message)
        val record = f.controller.servers().single()
        assertFalse(record.enabled)
        assertFalse(f.registry.resolve("hello.world") is ResolveResult.Found)
    }

    @Test
    fun disableUnregistersCommandsLive() = runTest {
        val f = Fixture()
        f.controller.addServer("demo", "https://mcp.example.com", null)
        f.controller.setEnabled("demo", true)

        val result = f.controller.setEnabled("demo", false)

        assertEquals(McpEnableResult.Disabled(commandsUnregistered = 1), result)
        assertFalse(f.registry.resolve("hello.world") is ResolveResult.Found)
        assertFalse(f.controller.servers().single().enabled)
    }

    @Test
    fun removeDropsSecretRecordAndCommands() = runTest {
        val f = Fixture()
        f.controller.addServer("demo", "https://mcp.example.com", "tok-3")
        f.controller.setEnabled("demo", true)

        val result = f.controller.removeServer("demo")

        assertEquals(McpRemoveResult.Removed(commandsUnregistered = 1), result)
        assertNull(f.store.entries[McpServerController.secretKeyOf("demo")])
        assertTrue(f.controller.servers().isEmpty())
        assertFalse(f.registry.resolve("hello.world") is ResolveResult.Found)
    }

    @Test
    fun legacySingleServerKeysMigrateOnceIntoTheList() = runTest {
        val f = Fixture()
        f.store.entries["mcp_server_id"] = "legacy".toByteArray()
        f.store.entries["mcp_endpoint"] = "https://old.example.com".toByteArray()

        val servers = f.controller.servers()

        assertEquals(listOf(McpServerRecord("legacy", "https://old.example.com", enabled = false)), servers)
        // Migration consumes the item-31 keys so a fresh controller cannot duplicate.
        assertNull(f.store.entries["mcp_server_id"])
        assertNull(f.store.entries["mcp_endpoint"])
        assertTrue(
            f.store.entries.getValue(McpServerController.SERVERS_KEY).decodeToString().contains("\"legacy\""),
        )
        // Second controller over the same store: still exactly one record.
        val f2 = Fixture(store = f.store)
        assertEquals(1, f2.controller.servers().size)
    }

    @Test
    fun reconnectEnabledIsolatesPerServerFailures() = runTest {
        val failFor = mutableSetOf<String>()
        val f = Fixture(bridge = FakeBridge(failFor = failFor))
        f.controller.addServer("good", "https://good.example.com", null)
        f.controller.addServer("bad", "https://bad.example.com", null)
        f.controller.setEnabled("good", true)
        f.controller.setEnabled("bad", true)

        failFor += "bad" // the endpoint breaks between runs
        val results = f.controller.reconnectEnabled()

        assertTrue(results.getValue("good") is McpEnableResult.Enabled)
        assertTrue(results.getValue("bad") is McpEnableResult.Error)
        // One bad endpoint must not block the others — both records stay listed
        // (the failed one disabled, awaiting a manual retry).
        val byId = f.controller.servers().associateBy { it.id }
        assertTrue(byId.getValue("good").enabled)
        assertFalse(byId.getValue("bad").enabled)
    }

    @Test
    fun persistedPluginIdLetsAFreshControllerDisableWithoutDiscovery() = runTest {
        val f = Fixture()
        f.controller.addServer("demo", "https://mcp.example.com", null)
        f.controller.setEnabled("demo", true)

        // Fresh process: new runtime, same store + registry, and a bridge that
        // has no legacy naming contract (pluginIdFor → null).
        val f2 = Fixture(store = f.store, registry = f.registry, bridge = FakeBridge(legacyPluginIds = false))
        assertEquals(1, f2.controller.servers().single().let { if (it.enabled) 1 else 0 })
        assertEquals(0, f2.bridge.discoverCalls)

        val result = f2.controller.setEnabled("demo", false)

        assertEquals(McpEnableResult.Disabled(commandsUnregistered = 1), result)
        assertEquals(0, f2.bridge.discoverCalls)
    }

    @Test
    fun unknownIdsReturnNullAndUnknown() = runTest {
        val f = Fixture()
        assertNull(f.controller.setEnabled("nope", true))
        assertNull(f.controller.setEnabled("nope", false))
        assertEquals(McpRemoveResult.Unknown, f.controller.removeServer("nope"))
    }
}
