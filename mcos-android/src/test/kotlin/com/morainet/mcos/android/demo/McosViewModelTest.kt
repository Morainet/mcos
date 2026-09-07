package com.morainet.mcos.android.demo

import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.sdk.SecureStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Unit tests for [McosViewModel] — plain JVM, no Robolectric: the view model
 * holds no Android types, the runtime is the real facade with stub host
 * services, and the persisted-key store is an in-memory fake.
 *
 * [UnconfinedTestDispatcher] lets [McosViewModel.attach]'s startup load and
 * [McosViewModel.run]'s pipeline run eagerly; the async runtime work happens
 * on real dispatchers, so completion is awaited with a real-time bounded
 * poll instead of virtual-time advancement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McosViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(mainDispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    private lateinit var vm: McosViewModel

    @get:Rule
    val rule = mainRule

    @Before
    fun setUp() {
        vm = McosViewModel()
    }

    @After
    fun tearDown() {
        // Cancel pending debounce/preview jobs so no test leaks a delayed
        // probe into the next one.
        vm.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // attach / persisted API key
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun attachMigratesLegacyKeyIntoOpenAiVendorOnce() = runBlocking {
        val store = FakeSecureStore(legacy = "sk-test")
        vm.attach(buildDeps(store))

        // Legacy single key lands in the OpenAI vendor slot and is selected.
        withTimeout(5_000) { vm.uiState.first { it.selectedVendor.apiKey == "sk-test" } }
        val openai = vm.uiState.value.vendors.first { it.vendor.id == "openai" }
        assertEquals("sk-test", openai.apiKey)
        assertEquals("openai", vm.uiState.value.selectedVendorId)
        // The legacy entry is consumed (migrated then removed).
        assertNull(store.get("llm_api_key"))
        assertEquals("sk-test", store.get("llm_vendor_openai_key")?.decodeToString())

        // A later attach must not re-read the store — in-memory state wins.
        vm.attach(buildDeps(FakeSecureStore(legacy = "sk-other")))
        assertEquals("sk-test", vm.uiState.value.selectedVendor.apiKey)
    }

    @Test
    fun vendorKeysArePersistedAndIsolatedPerVendor() = runBlocking {
        val store = FakeSecureStore()
        vm.attach(buildDeps(store))

        vm.onVendorKeyChange("openai", "sk-openai")
        vm.onVendorKeyChange("deepseek", "sk-deepseek")

        // Each vendor's key persists under its own SecureStore slot.
        withTimeout(5_000) { vm.uiState.first { it.vendors.first { v -> v.vendor.id == "deepseek" }.apiKey == "sk-deepseek" } }
        assertEquals("sk-openai", store.get("llm_vendor_openai_key")?.decodeToString())
        assertEquals("sk-deepseek", store.get("llm_vendor_deepseek_key")?.decodeToString())
    }

    @Test
    fun selectVendorSwitchesTheEffectiveConfig() = runBlocking {
        val store = FakeSecureStore()
        vm.attach(buildDeps(store))

        vm.onVendorKeyChange("deepseek", "sk-deepseek")
        vm.selectVendor("deepseek")

        val config = vm.selectedLlmConfig()
        assertNotNull("selected DeepSeek should map to a config", config)
        assertEquals("sk-deepseek", config!!.apiKey)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.endpoint)
        assertEquals("deepseek-chat", config.model)
        assertEquals("deepseek", store.get("llm_vendor_selected")?.decodeToString())
    }

    @Test
    fun customVendorRequiresEndpointAndModel() {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.selectVendor("custom")
        vm.onVendorKeyChange("custom", "sk-x")
        // Endpoint + model still blank → not usable, no config.
        assertNull(vm.selectedLlmConfig())

        vm.onVendorEndpointChange("custom", "https://my.host/v1/chat/completions")
        vm.onVendorModelChange("custom", "my-model")
        val config = vm.selectedLlmConfig()
        assertNotNull(config)
        assertEquals("https://my.host/v1/chat/completions", config!!.endpoint)
    }

    // ═══════════════════════════════════════════════════════════════
    // DSL run
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun runExecutesDslToCompletion() = runBlocking {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.onDslTextChange("hello.world(name=\"World\")")
        vm.run()

        // The pipeline completes when the run's terminal event arrives
        // (the observed flow completes at the terminal event).
        withTimeout(10_000) {
            vm.uiState.first { !it.isExecuting }
        }

        val state = vm.uiState.value
        assertFalse(state.isExecuting)
        assertTrue(state.pluginsLoaded)
        assertTrue(
            "expected hello.world registered, got ${state.commandIds}",
            state.commandIds.contains("hello.world"),
        )
        assertTrue(
            "expected a success log line, got ${vm.events.value}",
            vm.events.value.any { it.contains("Done") },
        )
    }

    @Test
    fun blankDslRunIsIgnored() {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.onDslTextChange("")
        vm.run()

        assertFalse(vm.uiState.value.isExecuting)
        assertTrue(vm.events.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM chat
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun chatWithoutApiKeyWarnsAndDoesNotExecute() = runBlocking {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.onNlTextChange("take a photo")
        vm.chat()

        assertTrue(
            "expected missing-key warning, got ${vm.events.value}",
            vm.events.value.any { it.contains("Set an LLM API key in Settings") },
        )
        assertFalse(vm.uiState.value.isExecuting)
    }

    // ═══════════════════════════════════════════════════════════════
    // Confirmations / input clearing
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun respondConfirmationWithoutPendingIsNoOp() {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.respondConfirmation(true)
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun clearInputResetsDslPreviewAndLog() = runBlocking {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.onDslTextChange("hello.world(name=\"x\")")
        vm.run()
        withTimeout(10_000) { vm.uiState.first { !it.isExecuting } }
        assertTrue(vm.events.value.isNotEmpty())

        vm.clearInput()

        assertEquals("", vm.uiState.value.dslText)
        assertNull(vm.uiState.value.previewText)
        assertTrue(vm.events.value.isEmpty())
    }

    @Test
    fun blankDslChangeClearsPreview() {
        vm.attach(buildDeps(FakeSecureStore()))
        vm.onDslTextChange("hello.world(name=\"x\")")
        vm.onDslTextChange("")

        assertEquals("", vm.uiState.value.dslText)
        assertNull(vm.uiState.value.previewText)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Real facade + registry with a real built-in plugin, stub host services,
     * and the production marketplace chain (see [TestMarketplace.deps]); the
     * DSL tests below never touch the marketplace pieces.
     */
    private fun buildDeps(secureStore: SecureStore): AppDeps =
        TestMarketplace.deps(secureStore = secureStore)

    private class FakeSecureStore(legacy: String? = null) : SecureStore {
        private val entries = mutableMapOf<String, ByteArray>()
        init {
            legacy?.let { entries[LEGACY_KEY] = it.encodeToByteArray() }
        }

        override suspend fun get(key: String): ByteArray? = entries[key]
        override suspend fun put(key: String, value: ByteArray) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
        override suspend fun keys(): Set<String> = entries.keys.toSet()
    }

    private companion object {
        const val LEGACY_KEY = "llm_api_key"
    }
}
