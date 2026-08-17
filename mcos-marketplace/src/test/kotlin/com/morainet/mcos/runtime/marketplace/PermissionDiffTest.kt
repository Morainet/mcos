package com.morainet.mcos.runtime.marketplace

import kotlin.test.*

/**
 * Unit tests for [computePermissionDiff] — §7.2 update permission comparison.
 */
class PermissionDiffTest {

    private fun meta(
        packageId: String = "com.example.a",
        permissions: List<MarketplacePermissionEntry> = emptyList(),
        version: String = "1.0.0",
    ) = PackageMetadata(
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
        permissionsPreview = permissions,
        publishedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-02-01T00:00:00Z",
    )

    private fun perm(type: String, name: String, tier: String, justification: String? = null) =
        MarketplacePermissionEntry(type, name, tier, justification)

    @Test
    fun `T1-identical permissions produce empty diff and silent update`() {
        val perms = listOf(
            perm("mcos", "network.openapi.x.com", "normal"),
            perm("android", "CAMERA", "elevated"),
        )
        val diff = computePermissionDiff(meta(permissions = perms), meta(permissions = perms, version = "1.1.0"))

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty())
        assertFalse(diff.consentRequired)
        assertTrue(diff.isSilent)
    }

    @Test
    fun `T2-adding only normal tier permission does not require consent`() {
        val old = meta(permissions = listOf(perm("mcos", "network.openapi.x.com", "normal")))
        val new = meta(
            version = "1.1.0",
            permissions = listOf(
                perm("mcos", "network.openapi.x.com", "normal"),
                perm("mcos", "contacts.read", "normal"),
            ),
        )

        val diff = computePermissionDiff(old, new)

        assertEquals(listOf("mcos:contacts.read"), diff.added.map { "${it.type}:${it.name}" })
        assertFalse(diff.consentRequired)
        assertFalse(diff.isSilent)
    }

    @Test
    fun `T3-adding elevated permission requires consent`() {
        val old = meta(permissions = listOf(perm("mcos", "network.openapi.x.com", "normal")))
        val new = meta(
            version = "1.1.0",
            permissions = listOf(
                perm("mcos", "network.openapi.x.com", "normal"),
                perm("android", "CAMERA", "elevated"),
            ),
        )

        val diff = computePermissionDiff(old, new)

        assertTrue(diff.consentRequired, "added elevated permission must require fresh consent")
    }

    @Test
    fun `T4-risk tier escalation on existing scope requires consent`() {
        val old = meta(permissions = listOf(perm("android", "CAMERA", "normal")))
        val new = meta(version = "1.1.0", permissions = listOf(perm("android", "CAMERA", "destructive")))

        val diff = computePermissionDiff(old, new)

        assertEquals(1, diff.changed.size)
        assertEquals(ChangeType.RISK_TIER_ESCALATED, diff.changed[0].changeType)
        assertTrue(diff.consentRequired)
    }

    @Test
    fun `T5-removed permissions are reported and do not require consent`() {
        val old = meta(
            permissions = listOf(
                perm("mcos", "network.openapi.x.com", "normal"),
                perm("mcos", "contacts.read", "elevated"),
            ),
        )
        val new = meta(version = "1.1.0", permissions = listOf(perm("mcos", "network.openapi.x.com", "normal")))

        val diff = computePermissionDiff(old, new)

        assertEquals(listOf("mcos:contacts.read"), diff.removed.map { "${it.type}:${it.name}" })
        assertFalse(diff.consentRequired, "removing a permission never requires consent")
    }

    @Test
    fun `T6-justification change alone is flagged but no consent`() {
        val old = meta(permissions = listOf(perm("android", "CAMERA", "elevated", "old rationale")))
        val new = meta(version = "1.1.0", permissions = listOf(perm("android", "CAMERA", "elevated", "new rationale")))

        val diff = computePermissionDiff(old, new)

        assertEquals(1, diff.changed.size)
        assertEquals(ChangeType.JUSTIFICATION_CHANGED, diff.changed[0].changeType)
        assertFalse(diff.consentRequired)
    }

    @Test
    fun `T7-downgrade of risk tier is not an escalation`() {
        val old = meta(permissions = listOf(perm("android", "CAMERA", "destructive")))
        val new = meta(version = "1.1.0", permissions = listOf(perm("android", "CAMERA", "normal")))

        val diff = computePermissionDiff(old, new)

        assertEquals(1, diff.changed.size)
        assertEquals(ChangeType.JUSTIFICATION_CHANGED, diff.changed[0].changeType)
        assertFalse(diff.consentRequired)
    }
}
