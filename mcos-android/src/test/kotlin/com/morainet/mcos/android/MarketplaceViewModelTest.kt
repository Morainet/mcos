package com.morainet.mcos.android

import com.morainet.mcos.marketplace.InstallResult
import com.morainet.mcos.marketplace.InstallState
import com.morainet.mcos.plugin.camera.CameraPlugin
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.security.permission.DefaultPermissionKernel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Unit tests for [MarketplaceViewModel] — plain JVM, no Robolectric, following
 * [McosViewModelTest]'s scaffolding. The transport is an in-memory fake, but
 * the whole install chain is real: Ed25519 key pair + signature, ArtifactVerifier,
 * trust-gated loader (with `disableSideload = true`), and the real registry.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarketplaceViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(mainDispatcher)
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    private lateinit var vm: MarketplaceViewModel

    @get:Rule
    val rule = mainRule

    @Before
    fun setUp() {
        vm = MarketplaceViewModel()
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // attach / persisted base URL
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun attachLoadsPersistedBaseUrlOnce() = runBlocking {
        val store = TestMarketplace.FakeSecureStore(initial = mapOf("marketplace_url" to "http://idx.test"))
        vm.attach(TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), store))

        withTimeout(5_000) { vm.uiState.first { it.baseUrl == "http://idx.test" } }

        // A later attach must not overwrite an in-memory URL the user may have
        // edited (same once-per-process contract as the LLM API key).
        vm.onBaseUrlChange("http://edited.test")
        vm.attach(TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore()))
        assertEquals("http://edited.test", vm.uiState.value.baseUrl)
    }

    @Test
    fun freshAttachDropsStaleInstallRecords() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val meta = TestMarketplace.metadata(pair)
        val transport = TestMarketplace.FakeIndexTransport(searchBody = TestMarketplace.searchResponseJson(meta))
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        // Activity recreated → fresh AppDeps chain → stale INSTALLED record
        // must be dropped (the new registry has no such commands).
        vm.attach(TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore()))
        assertTrue(vm.uiState.value.installResults.isEmpty())
        assertTrue(vm.uiState.value.installStates.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // search
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun searchWithoutBaseUrlShowsError() {
        vm.attach(TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore()))

        vm.search()

        assertTrue(
            "expected base-URL hint, got ${vm.uiState.value.error}",
            vm.uiState.value.error.orEmpty().contains("index URL"),
        )
        assertTrue(vm.uiState.value.results.isEmpty())
    }

    @Test
    fun searchPopulatesResultsAndPersistsBaseUrl() = runBlocking {
        val meta = TestMarketplace.metadata(TestMarketplace.keyPair())
        val transport = TestMarketplace.FakeIndexTransport(searchBody = TestMarketplace.searchResponseJson(meta))
        val store = TestMarketplace.FakeSecureStore()
        vm.attach(TestMarketplace.deps(transport, store))

        vm.onBaseUrlChange("http://idx.test/")
        vm.onQueryChange("hello")
        vm.search()
        withTimeout(5_000) { vm.uiState.first { !it.searching } }

        val state = vm.uiState.value
        assertNull(state.error)
        assertEquals(1, state.results.size)
        assertEquals("example.hello", state.results.single().packageId)
        assertTrue(state.message.orEmpty().contains("1 result"))
        // Trailing slash trimmed; URL persisted for the next launch.
        assertEquals("http://idx.test", store.entriesForTest()["marketplace_url"])
        assertTrue(transport.getJsonUrls.single().startsWith("http://idx.test/v1/plugins?"))
    }

    @Test
    fun searchSurfacesIndexError() = runBlocking {
        val transport = TestMarketplace.FakeIndexTransport(searchStatus = 500, searchBody = "{}")
        vm.attach(TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore()))

        vm.onBaseUrlChange("http://idx.test")
        vm.search()
        withTimeout(5_000) { vm.uiState.first { !it.searching } }

        val state = vm.uiState.value
        assertTrue(state.results.isEmpty())
        assertNotNull(state.error)
    }

    // ═══════════════════════════════════════════════════════════════
    // install / uninstall (real Ed25519 verify chain)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun installRegistersCommandsAsMarketplaceVerified() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val meta = TestMarketplace.metadata(pair)
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(meta),
            artifactBytes = TestMarketplace.payload(),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        val result = vm.uiState.value.installResults.getValue(meta.packageId)
        assertTrue("expected Installed, got $result", result is InstallResult.Installed)
        result as InstallResult.Installed
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, result.trustLevel)
        assertEquals(1, result.commandsRegistered)

        // The command resolves live in the shared registry — the DSL palette
        // refresh (registryRevision) relies on exactly this.
        assertTrue(deps.registry.resolve("hello.world") is ResolveResult.Found)
        assertEquals(1, vm.uiState.value.registryRevision)

        // Progress events surfaced along the way, ending INSTALLED.
        val progress = vm.uiState.value.installStates.getValue(meta.packageId)
        assertEquals(InstallState.INSTALLED, progress.state)
        assertTrue(transport.getBytesUrls.single().contains("example.hello-1.0.0"))
    }

    @Test
    fun installGrantsDeclaredPermissionsOntoTheKernel() = runBlocking {
        val pair = TestMarketplace.keyPair()
        // camera declares real permissions (hello declares none — a grant
        // assertion against it would be vacuous).
        val meta = TestMarketplace.metadata(pair, packageId = "mcos.plugin.camera")
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(meta),
            artifactBytes = TestMarketplace.payload(),
        )
        val kernel = DefaultPermissionKernel()
        val deps = TestMarketplace.deps(
            transport, TestMarketplace.FakeSecureStore(),
            keyPair = pair, permissionKernel = kernel,
        )
        vm.attach(deps)

        val declared = PluginPermissionBootstrap.declaredPermissions(CameraPlugin())
        org.junit.Assert.assertFalse(
            "camera must declare permissions for this test to mean anything",
            declared.isEmpty(),
        )
        declared.forEach { org.junit.Assert.assertFalse(kernel.hasPermission("mcos.plugin.camera", it)) }

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        assertTrue(vm.uiState.value.installResults.getValue(meta.packageId) is InstallResult.Installed)
        // The install dialog's permissionsPreview is the consent moment —
        // install() must clear the Stage-6 hard gate for every declared
        // permission (the kernel's GrantStore then persists them).
        declared.forEach { assertTrue("expected grant for $it", kernel.hasPermission("mcos.plugin.camera", it)) }
    }

    @Test
    fun tamperedArtifactFailsClosedAndRegistersNothing() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val meta = TestMarketplace.metadata(pair)
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(meta),
            artifactBytes = "tampered-bytes".encodeToByteArray(),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        val state = vm.uiState.value
        assertTrue(
            "expected Failed, got ${state.installResults}",
            state.installResults.getValue(meta.packageId) is InstallResult.Failed,
        )
        assertNotNull(state.error)
        assertTrue(deps.registry.resolve("hello.world") is ResolveResult.NotFound)
        assertEquals(0, state.registryRevision)
    }

    @Test
    fun installWithoutLocalFactoryFailsFast() {
        vm.attach(TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore()))

        val meta = TestMarketplace.metadata(TestMarketplace.keyPair(), packageId = "unknown.plugin")
        vm.install(meta)

        assertTrue(
            "expected factory-missing error, got ${vm.uiState.value.error}",
            vm.uiState.value.error.orEmpty().contains("No local implementation"),
        )
        assertTrue(vm.uiState.value.installResults.isEmpty())
        assertEquals(0, vm.uiState.value.registryRevision)
    }

    @Test
    fun uninstallRemovesCommandsAndClearsRecords() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val meta = TestMarketplace.metadata(pair)
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(meta),
            artifactBytes = TestMarketplace.payload(),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        vm.uninstall(meta.packageId)
        withTimeout(10_000) { vm.uiState.first { it.registryRevision == 2 } }

        val state = vm.uiState.value
        assertTrue(state.installResults.isEmpty())
        assertTrue(state.installStates.isEmpty())
        assertTrue(state.message.orEmpty().contains("Uninstalled"))
        assertTrue(deps.registry.resolve("hello.world") is ResolveResult.NotFound)
    }
}
