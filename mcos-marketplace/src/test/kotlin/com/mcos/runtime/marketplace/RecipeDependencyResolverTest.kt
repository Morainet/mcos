package com.mcos.runtime.marketplace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/**
 * Unit tests for [RecipeDependencyResolver] — recipe `requiredPlugins`
 * resolution against the local registry and marketplace ([09-marketplace.md §7.4]).
 */
class RecipeDependencyResolverTest {

    private fun recipe(requiredPlugins: List<String>) = RecipeEnvelope(
        recipeId = "com.example.recipe",
        name = "Recipe",
        version = "1.0.0",
        workflow = Json.parseToJsonElement("""{}""").jsonObject,
        requiredPlugins = requiredPlugins,
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

    // ── parseDependency ──────────────────────────────────────────────

    @Test
    fun `D1-spec with range parses into pluginId and range`() {
        val dep = RecipeDependencyResolver.parseDependency("com.example.photo@^1.0.0")

        assertEquals(RecipeDependency("com.example.photo", "^1.0.0"), dep)
    }

    @Test
    fun `D2-bare pluginId gets a wildcard range`() {
        val dep = RecipeDependencyResolver.parseDependency("mcos.plugin.system")

        assertEquals(RecipeDependency("mcos.plugin.system", "*"), dep)
    }

    @Test
    fun `D3-blank or empty pluginId is unparseable`() {
        assertNull(RecipeDependencyResolver.parseDependency(""))
        assertNull(RecipeDependencyResolver.parseDependency("   "))
        assertNull(RecipeDependencyResolver.parseDependency("@1.0.0"))
    }

    // ── resolve ─────────────────────────────────────────────────────

    @Test
    fun `D4-all installed versions satisfy the ranges`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@^1.0.0", "com.example.files@>=2.0.0")),
                installedVersion = { id -> if (id == "com.example.photo") "1.5.0" else "2.3.0" },
                marketplaceLookup = { null },
            )
        }

        assertEquals(RecipeResolveResult.Resolved, result)
    }

    @Test
    fun `D5-bare dependency matches any installed version`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("mcos.plugin.system")),
                installedVersion = { "3.0.0" },
                marketplaceLookup = { null },
            )
        }

        assertEquals(RecipeResolveResult.Resolved, result)
    }

    @Test
    fun `D6-missing but available in marketplace proposes the version`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@^1.0.0")),
                installedVersion = { null },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "1.2.0") else null },
            )
        }

        val unresolved = assertIs<RecipeResolveResult.Unresolved>(result)
        val missing = unresolved.missing.single()
        assertEquals("com.example.photo", missing.pluginId)
        assertEquals("^1.0.0", missing.range)
        assertEquals("1.2.0", missing.suggestedVersion)
    }

    @Test
    fun `D7-not installed and absent from marketplace reports not_in_marketplace`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@^1.0.0")),
                installedVersion = { null },
                marketplaceLookup = { null },
            )
        }

        val unresolved = assertIs<RecipeResolveResult.Unresolved>(result)
        val missing = unresolved.missing.single()
        assertEquals("not_in_marketplace", missing.reason)
        assertNull(missing.suggestedVersion)
    }

    @Test
    fun `D8-installed below range and marketplace version satisfies`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@^1.0.0")),
                installedVersion = { "0.9.0" },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "1.2.0") else null },
            )
        }

        val unresolved = assertIs<RecipeResolveResult.Unresolved>(result)
        assertEquals("1.2.0", unresolved.missing.single().suggestedVersion)
    }

    @Test
    fun `D9-marketplace latest does not satisfy the range`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@<2.0.0")),
                installedVersion = { null },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "2.5.0") else null },
            )
        }

        val unresolved = assertIs<RecipeResolveResult.Unresolved>(result)
        val missing = unresolved.missing.single()
        assertEquals("not_in_marketplace", missing.reason)
        assertNull(missing.suggestedVersion)
    }

    @Test
    fun `D10-mixed resolution keeps only the missing dependency`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(listOf("com.example.photo@^1.0.0", "com.example.files@^2.0.0")),
                installedVersion = { id -> if (id == "com.example.files") "2.1.0" else null },
                marketplaceLookup = { id -> if (id == "com.example.photo") packageAt(id, "1.0.0") else null },
            )
        }

        val unresolved = assertIs<RecipeResolveResult.Unresolved>(result)
        assertEquals(listOf("com.example.photo"), unresolved.missing.map { it.pluginId })
    }

    @Test
    fun `D11-unparseable range fails with SCHEMA_VIOLATION`() {
        val error = runBlocking {
            assertFailsWith<RecipeSchemaException> {
                RecipeDependencyResolver.resolve(
                    recipe(listOf("com.example.photo@not-a-range")),
                    installedVersion = { null },
                    marketplaceLookup = { null },
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("SCHEMA_VIOLATION"))
    }

    @Test
    fun `D12-empty requiredPlugins resolves immediately`() {
        val result = runBlocking {
            RecipeDependencyResolver.resolve(
                recipe(emptyList()),
                installedVersion = { null },
                marketplaceLookup = { null },
            )
        }

        assertEquals(RecipeResolveResult.Resolved, result)
    }
}
