package com.morainet.mcos.marketplace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Unit tests for [RecipeInstaller] — recipe install wizard steps
 * ([09-marketplace.md §8.3], [05-workflow.md §14.1] placeholder binding).
 */
class RecipeInstallerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun recipe(
        workflow: String = """{"step":{"command":"photo.compress","target":"{{placeholder.target}}","quality":"{{placeholder.quality}}"}}""",
        placeholders: List<RecipePlaceholder> = listOf(
            RecipePlaceholder(key = "target", fromMemory = "places.office.wifiSsids", label = "Target", required = true),
            RecipePlaceholder(key = "quality", default = "high"),
        ),
        requiredPlugins: List<String> = listOf("com.example.photo@^1.0.0"),
    ) = RecipeEnvelope(
        recipeId = "recipe.office.vpn",
        name = "Office Wi-Fi → VPN",
        summary = "Turns on VPN on office Wi-Fi",
        version = "1.0.0",
        workflow = Json.parseToJsonElement(workflow).jsonObject,
        placeholders = placeholders,
        requiredPlugins = requiredPlugins,
        triggerPreview = RecipeTriggerPreview(type = "location", inputs = listOf("office")),
    )

    private fun packageAt(packageId: String, version: String) = PackageMetadata(
        packageId = packageId,
        name = packageId,
        version = version,
        minRuntimeVersion = "0.9.0",
        publisherId = "pub_1",
        publisherName = "Pub",
        summary = "s",
        artifact = ArtifactRef(
            url = "https://cdn.example.com/$packageId-$version.mcos",
            sha256 = "ab".repeat(32),
            signature = "sig",
            signingKeyId = "key_2026_01",
        ),
        publishedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    // ── prepare ─────────────────────────────────────────────────────

    @Test
    fun `I1-prepare with satisfied deps and no prompts yields empty plan`() {
        val installer = RecipeInstaller()

        val plan = runBlocking {
            installer.prepare(
                recipe(requiredPlugins = emptyList(), placeholders = emptyList()),
                installedVersion = { null },
                marketplaceLookup = { null },
            )
        }

        assertFalse(plan.blockedOnDependencies)
        assertTrue(plan.missingDependencies.isEmpty())
        assertTrue(plan.prompts.isEmpty())
    }

    @Test
    fun `I2-prepare surfaces missing dependencies`() {
        val installer = RecipeInstaller()

        val plan = runBlocking {
            installer.prepare(
                recipe(requiredPlugins = listOf("com.example.photo@^1.0.0")),
                installedVersion = { null },
                marketplaceLookup = { null },
            )
        }

        assertTrue(plan.blockedOnDependencies)
        assertEquals(listOf("com.example.photo"), plan.missingDependencies.map { it.pluginId })
    }

    @Test
    fun `I3-prepare proposes memory value for fromMemory placeholder`() {
        val installer = RecipeInstaller()

        val plan = runBlocking {
            installer.prepare(
                recipe(),
                installedVersion = { null },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "1.2.0") else null },
                memoryLookup = { path -> if (path == "places.office.wifiSsids") "MyOfficeNet" else null },
            )
        }

        val prompt = plan.prompts.single { it.key == "target" }
        assertEquals("MyOfficeNet", prompt.suggested)
        assertTrue(prompt.required)
        assertEquals("Target", prompt.label)
    }

    @Test
    fun `I4-prepare no memory hit leaves suggested null`() {
        val installer = RecipeInstaller()

        val plan = runBlocking {
            installer.prepare(
                recipe(),
                installedVersion = { null },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "1.2.0") else null },
                memoryLookup = { null },
            )
        }

        assertNull(plan.prompts.single { it.key == "target" }.suggested)
        assertEquals("high", plan.prompts.single { it.key == "quality" }.default)
    }

    // ── submit / compile ────────────────────────────────────────────

    @Test
    fun `I5-submit with full bindings compiles and substitutes tokens`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = emptyList(), prompts = listOf(
            PlaceholderPrompt(key = "target", required = true),
            PlaceholderPrompt(key = "quality", default = "high"),
        ))

        val outcome = installer.submit(
            recipe(),
            plan,
            bindings = mapOf("target" to "MyOfficeNet", "quality" to "lossless"),
        )

        val installed = assertIs<RecipeInstallOutcome.Installed>(outcome)
        val step = installed.recipe.workflow["step"]?.jsonObject
        assertEquals("MyOfficeNet", step?.get("target")?.jsonPrimitive?.content)
        assertEquals("lossless", step?.get("quality")?.jsonPrimitive?.content)
        assertEquals("recipe.office.vpn", installed.recipe.recipeId)
        assertEquals("location", installed.recipe.triggerPreview?.type)
    }

    @Test
    fun `I6-submit missing required binding returns NeedsInput`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = emptyList(), prompts = listOf(
            PlaceholderPrompt(key = "target", required = true),
            PlaceholderPrompt(key = "quality", default = "high"),
        ))

        val outcome = installer.submit(
            recipe(),
            plan,
            bindings = mapOf("quality" to "high"),
        )

        val needsInput = assertIs<RecipeInstallOutcome.NeedsInput>(outcome)
        assertEquals(listOf("target"), needsInput.prompts.map { it.key })
    }

    @Test
    fun `I7-submit skipped non-required falls back to default then empty`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = emptyList(), prompts = listOf(
            PlaceholderPrompt(key = "target", required = true),
            PlaceholderPrompt(key = "quality", default = "high"),
            PlaceholderPrompt(key = "note"),
        ))

        val outcome = installer.submit(
            recipe(
                workflow = """{"s":{"q":"{{placeholder.quality}}","n":"{{placeholder.note}}"}}""",
                placeholders = listOf(
                    RecipePlaceholder(key = "target", required = true),
                    RecipePlaceholder(key = "quality", default = "high"),
                    RecipePlaceholder(key = "note"),
                ),
            ),
            plan,
            bindings = mapOf("target" to "T"),
        )

        val installed = assertIs<RecipeInstallOutcome.Installed>(outcome)
        val s = installed.recipe.workflow["s"]?.jsonObject
        assertEquals("high", s?.get("q")?.jsonPrimitive?.content)
        assertEquals("", s?.get("n")?.jsonPrimitive?.content)
    }

    @Test
    fun `I8-substitution recurses into arrays and nested objects`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = emptyList(), prompts = listOf(
            PlaceholderPrompt(key = "target", required = true),
        ))
        val workflow = """{"list":["{{placeholder.target}}","keep"],"deep":{"inner":{"t":"{{placeholder.target}}"}}}"""

        val installed = assertIs<RecipeInstallOutcome.Installed>(
            installer.submit(
                recipe(workflow = workflow, placeholders = listOf(RecipePlaceholder(key = "target", required = true))),
                plan,
                bindings = mapOf("target" to "X"),
            ),
        )

        assertEquals(listOf("X", "keep"), installed.recipe.workflow["list"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("X", installed.recipe.workflow["deep"]?.jsonObject?.get("inner")?.jsonObject?.get("t")?.jsonPrimitive?.content)
    }

    @Test
    fun `I9-undeclared placeholder token fails the compile`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = emptyList(), prompts = emptyList())
        val workflow = """{"s":{"x":"{{placeholder.undeclared}}"}}"""

        assertFailsWith<RecipeCompileException> {
            installer.compile(
                recipe(workflow = workflow, placeholders = emptyList()),
                bindings = emptyMap(),
            )
        }
    }

    @Test
    fun `I10-submit blocked on missing dependencies regardless of bindings`() {
        val installer = RecipeInstaller()
        val plan = RecipeInstallPlan(missingDependencies = listOf(
            MissingDependency("com.example.photo", "^1.0.0", reason = "not_in_marketplace"),
        ), prompts = emptyList())

        val outcome = installer.submit(
            recipe(),
            plan,
            bindings = mapOf("target" to "T"),
        )

        val needsDeps = assertIs<RecipeInstallOutcome.NeedsDependencies>(outcome)
        assertEquals(listOf("com.example.photo"), needsDeps.missing.map { it.pluginId })
    }

    @Test
    fun `I11-full wizard flow prepare then submit installs`() {
        val installer = RecipeInstaller()

        val plan = runBlocking {
            installer.prepare(
                recipe(),
                installedVersion = { "1.2.0" }, // dependency satisfied
                marketplaceLookup = { null },
                memoryLookup = { path -> if (path == "places.office.wifiSsids") "MyOfficeNet" else null },
            )
        }

        assertFalse(plan.blockedOnDependencies)
        assertEquals("MyOfficeNet", plan.prompts.single { it.key == "target" }.suggested)

        val outcome = installer.submit(recipe(), plan, bindings = mapOf("target" to "MyOfficeNet"))
        val installed = assertIs<RecipeInstallOutcome.Installed>(outcome)
        assertEquals("MyOfficeNet", installed.recipe.workflow["step"]?.jsonObject?.get("target")?.jsonPrimitive?.content)
    }
}
