package com.morainet.mcos.marketplace

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Serialization round-trip tests for the marketplace wire types
 * ([09-marketplace.md §4.0, §11, §14]).
 */
class MarketplaceTypesTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ═══════════════════════════════════════════════════════════════
    // V1: MarketplacePermissionEntry (market type, not SDK PermissionEntry)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V1-marketplace permission entry round-trips with risk tier`() {
        val entry = MarketplacePermissionEntry(
            type = "android",
            name = "CAMERA",
            riskTier = "elevated",
            justification = "capture plugin screenshot",
        )

        val decoded = json.decodeFromString<MarketplacePermissionEntry>(json.encodeToString(MarketplacePermissionEntry.serializer(), entry))

        assertEquals(entry, decoded)
        assertEquals("android:CAMERA", "${decoded.type}:${decoded.name}")
    }

    @Test
    fun `V2-marketplace permission entry tolerates unknown fields`() {
        val raw = """{"type":"mcos","name":"network.egress","riskTier":"normal","extra":"ignored"}"""

        val decoded = json.decodeFromString<MarketplacePermissionEntry>(raw)

        assertEquals("network.egress", decoded.name)
        assertEquals("normal", decoded.riskTier)
        assertNull(decoded.justification)
    }

    // ═══════════════════════════════════════════════════════════════
    // V3: PackageMetadata + ArtifactRef
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V3-package metadata round-trips with artifact reference`() {
        val metadata = PackageMetadata(
            packageId = "com.example.weather",
            name = "Weather",
            version = "1.2.0",
            minRuntimeVersion = "0.9.0",
            publisherId = "pub_42",
            publisherName = "Example Labs",
            categories = listOf("utility"),
            summary = "Weather forecasts",
            description = "Long description",
            permissionsPreview = listOf(
                MarketplacePermissionEntry("mcos", "network.openapi.example.com", "normal"),
            ),
            commandsPreview = listOf("weather.now"),
            artifact = ArtifactRef(
                url = "https://cdn.example.com/packages/weather-1.2.0.mcos",
                sha256 = "ab".repeat(32),
                signature = "sig-base64",
                signingKeyId = "key_2026_01",
                sizeBytes = 4096,
            ),
            publishedAt = "2026-01-15T09:00:00Z",
            updatedAt = "2026-02-01T12:30:00Z",
            downloadCount = 1024,
            safetyScore = 4.2f,
        )

        val wire = json.encodeToString(PackageMetadata.serializer(), metadata)
        val decoded = json.decodeFromString<PackageMetadata>(wire)

        assertEquals(metadata, decoded)
        assertEquals(4096L, decoded.artifact.sizeBytes)
        assertEquals("key_2026_01", decoded.artifact.signingKeyId)
        assertEquals("1.2.0", decoded.version)
    }

    @Test
    fun `V4-package metadata accepts omitted optional fields`() {
        val raw = """
            {
              "packageId": "com.example.mini",
              "name": "Mini",
              "version": "0.1.0",
              "minRuntimeVersion": "0.9.0",
              "publisherId": "pub_1",
              "publisherName": "Mini Pub",
              "summary": "Small",
              "artifact": {
                "url": "https://cdn.example.com/mini-0.1.0.mcos",
                "sha256": "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
                "signature": "sig",
                "signingKeyId": "key_2026_01"
              },
              "publishedAt": "2026-03-01T00:00:00Z",
              "updatedAt": "2026-03-01T00:00:00Z"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<PackageMetadata>(raw)

        assertEquals(emptyList(), decoded.categories)
        assertEquals(0L, decoded.artifact.sizeBytes)
        assertEquals(0L, decoded.downloadCount)
        assertEquals(0f, decoded.safetyScore)
    }

    // ═══════════════════════════════════════════════════════════════
    // V5-V7: SearchResponse
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V5-search response round-trips`() {
        val meta = PackageMetadata(
            packageId = "com.example.a",
            name = "A",
            version = "1.0.0",
            minRuntimeVersion = "0.9.0",
            publisherId = "pub_1",
            publisherName = "P",
            summary = "s",
            artifact = ArtifactRef("https://cdn.example.com/a.mcos", "ef".repeat(32), "sig", "key_1"),
            publishedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val response = SearchResponse(results = listOf(meta), total = 1, page = 1, pageSize = 20)

        val wire = json.encodeToString(SearchResponse.serializer(), response)
        val decoded = json.decodeFromString<SearchResponse>(wire)

        assertEquals(response, decoded)
        assertEquals(1, decoded.total)
        assertEquals(20, decoded.pageSize)
    }

    // ═══════════════════════════════════════════════════════════════
    // V8-V10: Blocklist (§14)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `V8-blocklist entry round-trips with permanent expiry null`() {
        val entry = BlocklistEntry(
            packageId = "com.example.malware",
            versionRange = "*",
            reason = BlocklistReason.MALWARE,
            detailUrl = "https://example.com/report/123",
            blockedAt = "2026-05-01T00:00:00Z",
            expiresAt = null,
        )

        val wire = json.encodeToString(BlocklistEntry.serializer(), entry)
        val decoded = json.decodeFromString<BlocklistEntry>(wire)

        assertEquals(entry, decoded)
        assertNull(decoded.expiresAt)
        assertEquals(BlocklistReason.MALWARE, decoded.reason)
    }

    @Test
    fun `V9-blocklist document round-trips with signature`() {
        val blocklist = Blocklist(
            entries = listOf(
                BlocklistEntry(
                    packageId = "com.example.old",
                    versionRange = "<1.4.0",
                    reason = BlocklistReason.SIGNATURE_KEY_COMPROMISED,
                    blockedAt = "2026-06-01T00:00:00Z",
                ),
            ),
            version = "2026.06.01",
            issuedAt = "2026-06-01T08:00:00Z",
            signature = "sig-base64",
        )

        val wire = json.encodeToString(Blocklist.serializer(), blocklist)
        val decoded = json.decodeFromString<Blocklist>(wire)

        assertEquals(blocklist, decoded)
        assertEquals(1, decoded.entries.size)
        assertEquals("sig-base64", decoded.signature)
    }

    @Test
    fun `V10-blocklist reason deserializes from wire names`() {
        // Enums serialize as their wire name (a plain JSON string).
        val raw = "\"LEGAL_TAKEDOWN\""

        val decoded = json.decodeFromString<BlocklistReason>(raw)

        assertEquals(BlocklistReason.LEGAL_TAKEDOWN, decoded)
    }
}
