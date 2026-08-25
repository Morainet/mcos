package com.morainet.mcos.android

import androidx.lifecycle.viewModelScope
import com.morainet.mcos.marketplace.RecipePlaceholder
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
 * Recipe install wizard (§8.3) + update permission-diff consent (§7.2) flows on
 * [MarketplaceViewModel]. Same pure-JVM scaffolding as [MarketplaceViewModelTest]:
 * an in-memory index transport with a real Ed25519 verify chain behind installs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarketplaceRecipeUpdateTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val mainRule = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(mainDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
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
    // recipe search + wizard (§8.2 / §8.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun recipeSearchPopulatesResults() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope()
        val transport = TestMarketplace.FakeIndexTransport(recipeBody = TestMarketplace.recipeSearchJson(recipe))
        vm.attach(TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore()))

        vm.onBaseUrlChange("http://idx.test")
        vm.searchRecipes()
        withTimeout(5_000) { vm.uiState.first { !it.searching } }

        val state = vm.uiState.value
        assertNull(state.error)
        assertEquals(1, state.recipeResults.size)
        assertEquals("hello.recipe", state.recipeResults.single().recipeId)
        assertTrue(transport.getJsonUrls.single().startsWith("http://idx.test/v1/recipes?"))
    }

    @Test
    fun recipeNoInputsInstallsIntoWorkflowStore() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(recipeId = "notify.recipe")
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(emptyMap())

        val state = vm.uiState.value
        assertNull(state.error)
        assertNull("wizard should close on success", state.recipePlan)
        assertNotNull("workflow registered under its recipe id", deps.runtime.workflowStore().get("notify.recipe"))
        assertEquals(1, state.registryRevision)
    }

    @Test
    fun recipeMissingRequiredInputSurfacesError() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            workflow = TestMarketplace.commandWorkflow("{{placeholder.msg}}"),
            placeholders = listOf(RecipePlaceholder(key = "msg", label = "Message", required = true)),
        )
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(mapOf("msg" to ""))

        val state = vm.uiState.value
        assertNotNull(state.recipePlan) // wizard stays open
        assertTrue(
            "expected required-field error, got ${state.error}",
            state.error.orEmpty().contains("Fill required"),
        )
        assertNull(deps.runtime.workflowStore().get(recipe.recipeId))
    }

    @Test
    fun recipeRequiredInputBindsCompilesAndRegisters() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "greet.recipe",
            workflow = TestMarketplace.commandWorkflow("{{placeholder.msg}}"),
            placeholders = listOf(RecipePlaceholder(key = "msg", label = "Message", required = true)),
        )
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(mapOf("msg" to "hi there"))

        val state = vm.uiState.value
        assertNull(state.error)
        assertNull(state.recipePlan)
        assertNotNull(deps.runtime.workflowStore().get("greet.recipe"))
        assertTrue(state.message.orEmpty().contains("greet.recipe"))
    }

    // ═══════════════════════════════════════════════════════════════
    // event-trigger recipes: arm on install, disarm on uninstall (§9.2)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A11-installing an event-trigger recipe arms it pre-authorized`() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "wifi.recipe",
            workflow = TestMarketplace.triggeredWorkflow(eventType = "wifi.connected"),
        )
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(emptyMap())
        // Arm happens in a coroutine right after registration.
        withTimeout(5_000) { vm.uiState.first { it.message.orEmpty().contains("armed on wifi.connected") } }

        assertEquals(listOf("wifi.recipe"), deps.runtime.armedTriggers())
        assertNull(vm.uiState.value.error)
        // The registered spec retains the trigger, not just the step tree.
        assertNotNull(deps.runtime.workflowStore().spec("wifi.recipe")?.trigger)
    }

    @Test
    fun `A12-uninstalling the plugin a trigger recipe uses disarms it`() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val meta = TestMarketplace.metadata(pair) // example.hello → hello.world
        val transport = TestMarketplace.FakeIndexTransport(searchBody = TestMarketplace.searchResponseJson(meta))
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(meta)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "hello.recipe",
            workflow = TestMarketplace.triggeredWorkflow(commandId = "hello.world", eventType = "agent.started"),
        )
        vm.onBaseUrlChange("http://idx.test")
        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(emptyMap())
        withTimeout(5_000) { vm.uiState.first { it.message.orEmpty().contains("armed on agent.started") } }
        assertEquals(listOf("hello.recipe"), deps.runtime.armedTriggers())

        vm.uninstall("example.hello")
        withTimeout(10_000) { vm.uiState.first { it.message.orEmpty().contains("Uninstalled") } }

        assertTrue("trigger must be disarmed with its plugin", deps.runtime.armedTriggers().isEmpty())
        assertTrue(
            "expected disarm hint, got ${vm.uiState.value.message}",
            vm.uiState.value.message.orEmpty().contains("disarmed"),
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // schedule-trigger recipes: arm on install, invalid cron errors
    // (§9.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `A13-installing a schedule-trigger recipe arms it pre-authorized`() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "nightly.recipe",
            workflow = TestMarketplace.scheduledWorkflow(cron = "0 23 * * *", tz = "Asia/Shanghai"),
        )
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(emptyMap())
        // Arm happens in a coroutine right after registration; the message
        // names the cron and timezone the schedule will fire in.
        withTimeout(5_000) {
            vm.uiState.first { it.message.orEmpty().contains("armed on cron '0 23 * * *' (Asia/Shanghai)") }
        }

        assertEquals(listOf("nightly.recipe"), deps.runtime.armedTriggers())
        assertNull(vm.uiState.value.error)
        assertNotNull(deps.runtime.workflowStore().spec("nightly.recipe")?.trigger)
    }

    @Test
    fun `A14-installing a schedule recipe with an invalid cron surfaces an arm error`() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "broken.recipe",
            // Parses as a spec (cron is just a string there) — the arm call
            // is what rejects it, so the wizard must surface the reason.
            workflow = TestMarketplace.scheduledWorkflow(cron = "not a cron"),
        )
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        vm.submitRecipe(emptyMap())
        withTimeout(5_000) { vm.uiState.first { it.error.orEmpty().contains("Trigger not armed") } }

        assertTrue(
            "expected the stable reason code, got ${vm.uiState.value.error}",
            vm.uiState.value.error.orEmpty().contains("schedule_cron_invalid"),
        )
        assertTrue(deps.runtime.armedTriggers().isEmpty())
        // The recipe itself still installed — only the trigger is dead.
        assertNotNull(deps.runtime.workflowStore().get("broken.recipe"))
    }

    @Test
    fun recipeMissingDependencyBlocksInstall() = runBlocking {
        val recipe = TestMarketplace.recipeEnvelope(
            recipeId = "needs.dep",
            requiredPlugins = listOf("absent.plugin@^1.0.0"),
        )
        // The fake index 404s /v1/plugins/{id}, so the dependency is unresolvable.
        val deps = TestMarketplace.deps(TestMarketplace.FakeIndexTransport(), TestMarketplace.FakeSecureStore())
        vm.attach(deps)
        vm.onBaseUrlChange("http://idx.test")

        vm.prepareRecipe(recipe)
        withTimeout(5_000) { vm.uiState.first { it.recipePlan != null } }
        assertTrue(vm.uiState.value.recipePlan!!.plan.blockedOnDependencies)

        vm.submitRecipe(emptyMap())
        assertTrue(
            "expected dependency error, got ${vm.uiState.value.error}",
            vm.uiState.value.error.orEmpty().contains("absent.plugin"),
        )
        assertNull(deps.runtime.workflowStore().get("needs.dep"))
    }

    // ═══════════════════════════════════════════════════════════════
    // update with permission-diff consent (§7.2)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun updateWithElevatedPermissionNeedsConsentThenConfirms() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val v1 = TestMarketplace.metadata(pair, version = "1.0.0")
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(v1),
            artifactBytes = TestMarketplace.payload(),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        // Install v1 so installedMetas holds the old permissions baseline.
        vm.install(v1)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        // v1.1.0 adds an elevated permission → fresh consent required.
        val v2 = TestMarketplace.metadata(pair, version = "1.1.0")
            .copy(permissionsPreview = listOf(TestMarketplace.permission("CAMERA")))

        vm.requestUpdate(v2)
        withTimeout(5_000) { vm.uiState.first { it.pendingUpdate != null } }
        val pending = vm.uiState.value.pendingUpdate!!
        assertTrue(pending.diff.consentRequired)
        assertEquals(1, pending.diff.added.size)

        vm.confirmUpdate()
        withTimeout(10_000) { vm.uiState.first { it.pendingUpdate == null && it.registryRevision >= 2 } }
        val state = vm.uiState.value
        assertNull(state.error)
        assertEquals("1.1.0", state.installedMetas.getValue(v2.packageId).version)
        assertTrue(state.message.orEmpty().contains("Updated"))
    }

    @Test
    fun silentUpdateProceedsWithoutConsent() = runBlocking {
        val pair = TestMarketplace.keyPair()
        val v1 = TestMarketplace.metadata(pair, version = "1.0.0")
        val transport = TestMarketplace.FakeIndexTransport(
            searchBody = TestMarketplace.searchResponseJson(v1),
            artifactBytes = TestMarketplace.payload(),
        )
        val deps = TestMarketplace.deps(transport, TestMarketplace.FakeSecureStore(), keyPair = pair)
        vm.attach(deps)

        vm.install(v1)
        withTimeout(10_000) { vm.uiState.first { it.installResults.isNotEmpty() } }

        // v1.1.0 adds no new permissions → silent update, no consent gate.
        val v2 = TestMarketplace.metadata(pair, version = "1.1.0")
        vm.requestUpdate(v2)
        withTimeout(10_000) { vm.uiState.first { it.installedMetas[v2.packageId]?.version == "1.1.0" } }

        val state = vm.uiState.value
        assertNull(state.pendingUpdate)
        assertNull(state.error)
        assertTrue(state.message.orEmpty().contains("Updated"))
    }
}
