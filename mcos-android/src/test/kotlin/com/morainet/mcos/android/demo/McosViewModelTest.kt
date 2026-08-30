package com.morainet.mcos.android.demo

import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.sdk.SecureStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    fun attachLoadsPersistedApiKeyOnce() {
        vm.attach(buildDeps(FakeSecureStore(initial = "sk-test")))

        assertEquals("sk-test", vm.uiState.value.apiKey)

        // A later attach (e.g. after rotation) must not re-read the store —
        // the in-memory key the user may have edited wins.
        vm.attach(buildDeps(FakeSecureStore(initial = "sk-other")))
        assertEquals("sk-test", vm.uiState.value.apiKey)
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
            vm.events.value.any { it.contains("Set an LLM API key") },
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

    private class FakeSecureStore(initial: String? = null) : SecureStore {
        private val entries = mutableMapOf<String, String>()
        init {
            initial?.let { entries[LLM_API_KEY_FOR_TEST] = it }
        }

        override suspend fun get(key: String): String? = entries[key]
        override suspend fun put(key: String, value: String) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
    }

    private companion object {
        const val LLM_API_KEY_FOR_TEST = "llm_api_key"
    }
}
