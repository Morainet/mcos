package com.mcos.runtime.marketplace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for client-side search ranking and recommendations
 * ([09-marketplace.md §9.1, §9.2, §16.6]).
 */
class SearchRankingTest {

    private fun perm(tier: String) = MarketplacePermissionEntry(
        type = "mcos",
        name = "test.scope",
        riskTier = tier,
    )

    private fun pkg(
        packageId: String,
        name: String = packageId,
        summary: String = "",
        commandsPreview: List<String> = emptyList(),
        permissionsPreview: List<MarketplacePermissionEntry> = emptyList(),
        categories: List<String> = emptyList(),
        downloadCount: Long = 0,
    ) = PackageMetadata(
        packageId = packageId,
        name = name,
        version = "1.0.0",
        minRuntimeVersion = "1.0.0",
        publisherId = "pub",
        publisherName = "Publisher",
        categories = categories,
        summary = summary,
        permissionsPreview = permissionsPreview,
        commandsPreview = commandsPreview,
        artifact = ArtifactRef(
            url = "https://cdn.example/a.aar",
            sha256 = "aa".repeat(32),
            signature = "c2ln",
            signingKeyId = "key1",
        ),
        publishedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        downloadCount = downloadCount,
    )

    // ── §9.1 computeSafetyWeight ────────────────────────────────────

    @Test
    fun `R1-no permissions weigh 1`() {
        assertEquals(1.0f, SearchRanking.computeSafetyWeight(emptyList()), 0.001f)
    }

    @Test
    fun `R2-destructive permission penalizes more than elevated`() {
        val destructive = SearchRanking.computeSafetyWeight(listOf(perm("destructive")))
        val elevated = SearchRanking.computeSafetyWeight(listOf(perm("elevated")))

        assertEquals(0.85f, destructive, 0.001f)
        assertEquals(0.95f, elevated, 0.001f)
    }

    @Test
    fun `R3-safety weight floors at 0_3 and never hides a plugin`() {
        val heavy = List(10) { perm("destructive") }

        assertEquals(0.3f, SearchRanking.computeSafetyWeight(heavy), 0.001f)
    }

    // ── §16.6 SafetyWeight_DampensNotHides ─────────────────────────

    @Test
    fun `R4-high permission plugin ranks below low permission with same text score`() {
        val query = "camera"
        // Identical text fields → identical textScore; only permissions differ.
        val risky = pkg(
            "risky",
            name = "Camera Plus",
            summary = "Take photos with your camera",
            commandsPreview = listOf("camera.shoot"),
            permissionsPreview = listOf(perm("destructive"), perm("destructive"), perm("destructive")),
        )
        val safe = pkg(
            "safe",
            name = "Camera Plus",
            summary = "Take photos with your camera",
            commandsPreview = listOf("camera.shoot"),
            permissionsPreview = listOf(perm("normal")),
        )

        assertEquals(SearchRanking.textScore(query, risky), SearchRanking.textScore(query, safe), 0.001f)
        assertTrue(SearchRanking.rank(query, risky) < SearchRanking.rank(query, safe))
    }

    @Test
    fun `R5-safety weight dampens but does not hide a plugin`() {
        val query = "camera"
        val risky = pkg(
            "risky",
            name = "Camera Plus",
            summary = "Take photos with your camera",
            commandsPreview = listOf("camera.shoot"),
            permissionsPreview = List(10) { perm("destructive") }, // weight floored at 0.3
        )

        assertTrue(SearchRanking.rank(query, risky) > 0f)
    }

    // ── §16.6 ExactMatch_BeforeWildcard ─────────────────────────────

    @Test
    fun `R6-exact command match ranks above summary mention`() {
        val query = "camera.capture"
        val provider = pkg(
            "a",
            name = "Cam",
            summary = "Camera controls",
            commandsPreview = listOf("camera.capture"),
        )
        val mentioner = pkg(
            "b",
            name = "Cam",
            summary = "Camera controls for your smart home",
            commandsPreview = listOf("camera.start"),
        )

        assertEquals(1.0f, SearchRanking.textScore(query, provider), 0.001f)
        assertTrue(SearchRanking.textScore(query, mentioner) < SearchRanking.textScore(query, provider))
        assertTrue(SearchRanking.rank(query, provider) > SearchRanking.rank(query, mentioner))
    }

    // ── §9.1 composite rank: category bonus & popularity ────────────

    @Test
    fun `R7-category match and popularity boost rank`() {
        val query = "media"
        // Keep downloadCount below the popularity saturation point (~1M) so
        // the differences are observable (min(1.0, log10(n+1) / 6)).
        val inCategory = pkg("a", summary = "media stuff", categories = listOf("media"), downloadCount = 1_000)
        val outOfCategory = pkg("b", summary = "media stuff", downloadCount = 1_000)
        val popular = pkg("c", summary = "media stuff", categories = listOf("media"), downloadCount = 100_000_000)

        assertTrue(SearchRanking.rank(query, inCategory) > SearchRanking.rank(query, outOfCategory))
        assertTrue(SearchRanking.rank(query, popular) > SearchRanking.rank(query, inCategory))
    }

    // ── §16.6 Recommendation_MissingCommand ─────────────────────────

    @Test
    fun `R8-recommends provider of a missing command, excluding installed`() {
        val compressor = pkg("compressor", commandsPreview = listOf("photo.compress"))
        val trimmer = pkg("trimmer", commandsPreview = listOf("video.trim"))
        val installedProvider = pkg("compressor-pro", commandsPreview = listOf("photo.compress"))

        val result = SearchRanking.recommendPlugins(
            missingCommandIds = setOf("photo.compress"),
            candidates = listOf(compressor, trimmer, installedProvider),
            isInstalled = { it == "compressor-pro" },
        )

        assertEquals(listOf("compressor"), result.map { it.packageId })
    }

    @Test
    fun `R9-same-publisher familiarity bonus breaks ties`() {
        val familiar = pkg("fam", commandsPreview = listOf("photo.compress"))
        val unfamiliar = pkg("unfam", commandsPreview = listOf("photo.compress"))

        val result = SearchRanking.recommendPlugins(
            missingCommandIds = setOf("photo.compress"),
            candidates = listOf(unfamiliar, familiar),
            isSamePublisher = { it == familiar },
        )

        assertEquals(listOf("fam", "unfam"), result.map { it.packageId })
    }

    @Test
    fun `R10-recommendations cap at topN and prefer safer providers`() {
        val safe = pkg("safe", commandsPreview = listOf("photo.compress"), permissionsPreview = listOf(perm("normal")))
        val risky = pkg("risky", commandsPreview = listOf("photo.compress"), permissionsPreview = listOf(perm("destructive")))

        val result = SearchRanking.recommendPlugins(
            missingCommandIds = setOf("photo.compress"),
            candidates = listOf(risky, safe),
            topN = 1,
        )

        assertEquals(listOf("safe"), result.map { it.packageId })
    }
}
