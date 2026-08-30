package com.morainet.mcos.android.demo

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The shell MCP wiring (04 §10 per-server enablement): adding a server then
 * toggling it on runs `McpAdapter.discover` and registers its `mcp.<id>.*`
 * commands through the runtime; toggling it off unregisters them. Drives the
 * real facade + registry with a fake MCP server bolted onto the host's `net`.
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

    private suspend fun addAndEnableDemo() {
        vm.onMcpNewIdChange("demo")
        vm.onMcpNewEndpointChange("https://example.test/mcp")
        vm.addMcpServer()
        withTimeout(10_000) { vm.uiState.first { st -> st.mcpServers.any { it.id == "demo" } } }
        vm.setMcpServerEnabled("demo", true)
        withTimeout(10_000) {
            vm.uiState.first { st -> st.mcpServers.find { it.id == "demo" }.let { it != null && !it.busy } }
        }
    }

    @Test
    fun enableRegistersBridgedToolsAsMcpCommands() = kotlinx.coroutines.runBlocking {
        val secureStore = TestMarketplace.FakeSecureStore()
        val host = NetOverrideHost(StubHostServices(InMemoryFacade()), FakeMcpNetService())
        vm.attach(TestMarketplace.deps(secureStore = secureStore, hostServices = host))

        addAndEnableDemo()

        val state = vm.uiState.value
        val demo = state.mcpServers.single { it.id == "demo" }
        assertTrue("expected demo enabled, status=${demo.status}", demo.enabled)
        assertTrue(
            "expected mcp.demo.echo registered, got ${state.commandIds}",
            state.commandIds.contains("mcp.demo.echo"),
        )
        // The list persists (id/endpoint/enabled) as JSON; the token is not here.
        assertNotNull(secureStore.get("mcp_servers"))
        assertTrue(secureStore.get("mcp_servers")!!.contains("demo"))
    }

    @Test
    fun disableUnregistersBridgedCommands() = kotlinx.coroutines.runBlocking {
        val secureStore = TestMarketplace.FakeSecureStore()
        val host = NetOverrideHost(StubHostServices(InMemoryFacade()), FakeMcpNetService())
        vm.attach(TestMarketplace.deps(secureStore = secureStore, hostServices = host))

        addAndEnableDemo()
        assertTrue(vm.uiState.value.commandIds.contains("mcp.demo.echo"))

        vm.setMcpServerEnabled("demo", false)
        withTimeout(10_000) {
            vm.uiState.first { st -> st.mcpServers.find { it.id == "demo" }.let { it != null && !it.busy && !it.enabled } }
        }

        val state = vm.uiState.value
        assertFalse(
            "expected mcp.demo.echo unregistered, got ${state.commandIds}",
            state.commandIds.contains("mcp.demo.echo"),
        )
    }

    /** Delegates every capability to [base] except [net]. */
    private class NetOverrideHost(
        base: HostServices,
        override val net: NetService,
    ) : HostServices by base

    /**
     * A one-tool MCP server over the [NetService] contract: `tools/list`
     * advertises a mappable `echo` tool; `tools/call` echoes a text envelope.
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
