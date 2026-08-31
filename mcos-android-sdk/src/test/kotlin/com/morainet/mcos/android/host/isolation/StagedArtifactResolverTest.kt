package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.marketplace.InstallState
import com.morainet.mcos.marketplace.PersistedInstallRecord
import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.PublisherKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Tests for [StagedArtifactResolver] — the pure half of the isolation
 * activation seam (item 44): install records → the staged artifact
 * [BinderIsolationHost] hands the plugin process at bind time.
 */
class StagedArtifactResolverTest {

    private val downloadDir = File("/data/marketplace")

    private fun record(
        packageId: String,
        state: InstallState = InstallState.INSTALLED,
        artifactFileName: String = "$packageId.mcos",
    ) = PersistedInstallRecord(
        packageId = packageId,
        version = "1.0.0",
        state = state.name,
        trustLevel = "MARKETPLACE_VERIFIED",
        installedAt = 1_700_000_000_000L,
        artifactFileName = artifactFileName,
        signature = ArtifactSignature(
            payloadSha256 = "00", signature = "sig", signingKeyId = "k1",
            algorithm = "Ed25519", signedAt = "2026-01-01T00:00:00Z",
        ),
        publisherKey = PublisherKey(
            keyId = "k1", publisherId = "pub", publicKeyFingerprint = "ff",
            algorithm = "Ed25519", publicKeyEncoded = "AA==",
            createdAt = "2026-01-01T00:00:00Z",
        ),
    )

    @Test
    fun resolvesAnInstalledRecordUnderTheDownloadDir() {
        val file = StagedArtifactResolver.resolve(
            listOf(record("market.plugin")), downloadDir, "market.plugin",
        )
        assertEquals(File(downloadDir, "market.plugin.mcos"), file)
    }

    @Test
    fun skipsDisabledRecords() {
        // defense in depth: a DISABLED plugin is not registered, so it never
        // dispatches — but if anything asks, refuse here too
        assertNull(
            StagedArtifactResolver.resolve(
                listOf(record("market.plugin", state = InstallState.DISABLED)),
                downloadDir,
                "market.plugin",
            ),
        )
    }

    @Test
    fun unknownPluginYieldsNull() {
        assertNull(StagedArtifactResolver.resolve(listOf(record("other")), downloadDir, "market.plugin"))
    }

    @Test
    fun emptyRecordsYieldNull() {
        assertNull(StagedArtifactResolver.resolve(emptyList(), downloadDir, "market.plugin"))
    }

    @Test
    fun picksTheMatchingRecordAmongMany() {
        val records = listOf(
            record("other.one", artifactFileName = "one.mcos"),
            record("market.plugin", artifactFileName = "the-one.mcos"),
            record("other.two", artifactFileName = "two.mcos"),
        )
        assertEquals(
            File(downloadDir, "the-one.mcos"),
            StagedArtifactResolver.resolve(records, downloadDir, "market.plugin"),
        )
    }
}
