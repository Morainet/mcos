package com.morainet.mcos.android

import com.morainet.mcos.android.host.InMemoryFacade
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.NetResponse
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The shell MCP wiring (02 §12.4 spike follow-up): [McosViewModel.connectMcp]
 * runs `McpAdapter.discover` against the host [NetService] and registers the
 * synthesized plugin through the runtime install pipeline, so the bridged
 * `mcp.*` tools land in the live registry. This test drives the real facade +
 * registry with a fake MCP server bolted onto the host's `net`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpShellWiringTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(mainDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:Rule
    val rule = mainRule

    private lateinit var vm: McosViewModel

    @Before
    fun setUp() { vm = McosViewModel() }

    @After
    fun tearDown() { vm.viewModelScope.cancel() }

    @Test
    fun connectRegistersBridgedToolsAsMcpCommands() = kotlinx.coroutines.runBlocking {
        val secureStore = TestMarketplace.FakeSecureStore()
        val host = NetOverrideHost(StubHostServices(InMemoryFacade()), FakeMcpNetService())
        vm.attach(TestMarketplace.deps(secureStore = secureStore, hostServices = host))

        vm.onMcpServerIdChange("demo")
        vm.onMcpEndpointChange("https://example.test/mcp")
        vm.connectMcp()

        withTimeout(10_000) {
            vm.uiState.first { !it.mcpConnecting && it.mcpStatus != null }
        }

        val state = vm.uiState.value
        assertTrue(
            "expected connected status, got ${state.mcpStatus}",
            state.mcpStatus?.startsWith("connected") == true,
        )
        assertTrue(
            "expected mcp.demo.echo registered, got ${state.commandIds}",
            state.commandIds.contains("mcp.demo.echo"),
        )
        // The single-server config persists (token-in-config, 10 §5.7).
        assertEquals("demo", secureStore.get("mcp_server_id"))
        assertEquals("https://example.test/mcp", secureStore.get("mcp_endpoint"))
    }

    /** Delegates every capability to [base] except [net]. */
    private class NetOverrideHost(
        base: HostServices,
        override val net: NetService,
    ) : HostServices by base

    /**
     * A one-tool MCP server over the [NetService] contract: `tools/list`
     * advertises a mappable `echo` tool; `tools/call` echoes back a text
     * content envelope. Enough to prove the discover→register path.
     */
    private class FakeMcpNetService : NetService {
        override suspend fun request(
            method: String,
            url: String,
            body: String?,
            headers: Map<String, String>,
        ): NetResponse {
            val result = if (body?.contains("\"tools/list\"") == true) {
                """{"tools":[{"name":"echo","description":"Echo text",""" +
                    """"inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}"""
            } else {
                """{"content":[{"type":"text","text":"ok"}],"isError":false}"""
            }
            return NetResponse(status = 200, body = """{"jsonrpc":"2.0","id":1,"result":$result}""")
        }
    }
}
