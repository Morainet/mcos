package com.morainet.mcos.android.demo

import com.morainet.mcos.android.McpServerController
import com.morainet.mcos.android.host.InMemoryFacade
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpServerController.importJsonConfig] parses a standard `mcpServers` config:
 * HTTP entries become disabled servers (token pulled from an Authorization
 * header into the SecureStore); stdio (command) entries are skipped.
 */
class McpJsonImportTest {

    private fun controller(secure: TestMarketplace.FakeSecureStore): McpServerController {
        val host = StubHostServices(InMemoryFacade())
        val deps = TestMarketplace.deps(secureStore = secure, hostServices = host)
        return McpServerController(
            secureStore = secure,
            runtime = deps.runtime,
            registry = deps.registry,
            hostServices = host,
            permissionKernel = DefaultPermissionKernel(),
            bridge = object : com.morainet.mcos.android.McpServerBridge {
                override suspend fun discover(
                    record: com.morainet.mcos.android.McpServerRecord,
                    secretKey: String?,
                    enabledTools: Set<String>?,
                ) = com.morainet.mcos.android.BridgedMcpServer(
                    plugin = object : com.morainet.mcos.sdk.McosPlugin {
                        override val manifest = com.morainet.mcos.sdk.PluginManifest(
                            id = "mcos.plugin.mcp.${record.id}",
                            name = record.id,
                            version = "1.0.0",
                            minRuntimeVersion = "0.1.0",
                            description = "",
                            provider = com.morainet.mcos.sdk.ProviderInfo("t", "https://t"),
                            entry = "x",
                            commands = emptyList(),
                        )
                        override suspend fun onLoad(services: com.morainet.mcos.sdk.HostServices) {}
                        override suspend fun onUnload() {}
                        override fun handlers() = emptyMap<String, com.morainet.mcos.sdk.CommandHandler>()
                    },
                )
            },
        )
    }

    @Test
    fun `imports http servers and skips stdio`() = runBlocking {
        val secure = TestMarketplace.FakeSecureStore()
        val c = controller(secure)
        val json = """
            {
              "mcpServers": {
                "github": { "url": "https://mcp.example/github", "headers": { "Authorization": "Bearer tok123" } },
                "local":  { "command": "npx", "args": ["-y", "server"] },
                "raw":    { "endpoint": "https://mcp.example/raw" }
              }
            }
        """.trimIndent()

        val result = c.importJsonConfig(json)

        assertEquals(2, result.added)
        assertEquals(1, result.skippedStdio)
        assertEquals(0, result.invalid)
        val ids = c.servers().map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("github", "raw")))
        // Bearer token was stripped of its prefix and stored under the secret key.
        assertEquals(
            "tok123",
            secure.get(McpServerController.secretKeyOf("github"))?.decodeToString(),
        )
    }

    @Test
    fun `malformed document reports invalid`() = runBlocking {
        val c = controller(TestMarketplace.FakeSecureStore())
        assertEquals(1, c.importJsonConfig("not json").invalid)
        assertEquals(1, c.importJsonConfig("""{"nope":true}""").invalid)
    }

    @Test
    fun `duplicate id is counted, not overwritten`() = runBlocking {
        val c = controller(TestMarketplace.FakeSecureStore())
        c.importJsonConfig("""{"mcpServers":{"g":{"url":"https://a/mcp"}}}""")
        val result = c.importJsonConfig("""{"mcpServers":{"g":{"url":"https://b/mcp"}}}""")
        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        assertEquals("https://a/mcp", c.servers().single { it.id == "g" }.endpoint)
    }
}
