package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.ReportReason
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end discovery interop (12-index-server.md §9) driven by the REAL
 * shipped client ([MarketplaceIndex] + [com.morainet.mcos.marketplace.JdkMarketplaceHttpTransport])
 * against a live server on an ephemeral port.
 *
 * The full happy path here is: publisher onboarding → key registration →
 * signed submission (gate-8 verified) → all-green CI APPROVED → publisher
 * self-publish → search / by-command / metadata / artifact download /
 * install-telemetry / user report — exactly the surface the runtime uses.
 */
class IndexDiscoveryInteropTest {

    @Test
    fun `green submission is approved then self-published and discovered by the shipped client`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok().contains("\"state\":\"APPROVED\"")

            // Publisher publishes its own APPROVED submission.
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            runBlocking {
                val client = s.client()

                // Search: query + category filtering return the listed package.
                val search = client.search(query = "alpha", pageSize = 20)
                assertEquals(1L, search.total, "one package matches 'alpha': ${search.results}")
                assertEquals(packageId, search.results.single().packageId)

                // by-command resolution (recommendations source, 09 §9.2).
                val byCommand = client.byCommand("hello.world")
                assertEquals(listOf(packageId), byCommand.map { it.packageId })

                // Package metadata from the decoded manifest + publisher payload.
                val pkg = assertNotNull(client.getPackage(packageId))
                assertEquals("1.0.0", pkg.version)
                assertEquals("alpha", pkg.publisherId)
                assertEquals("0.2.0", pkg.minRuntimeVersion)
                assertEquals(listOf("hello.world"), pkg.commandsPreview)
                assertEquals(0L, pkg.downloadCount)

                // Artifact download round-trip is byte-identical to the upload.
                val bytes = s.downloadBytes(pkg.artifact.url)
                assertContentEquals(submission.artifact, bytes)
                assertEquals(IndexTestKit.sha256Hex(bytes), pkg.artifact.sha256)
                assertEquals(pkg.artifact.sizeBytes, bytes.size.toLong())
            }
        }
    }

    @Test
    fun `install telemetry increments download count on a fresh client`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            runBlocking {
                s.client().recordInstallTelemetry(
                    packageId = packageId,
                    version = "1.0.0",
                    anonymizedClientId = "a".repeat(64),
                    timestamp = IndexTestKit.nowIso(),
                )
            }

            // A brand-new client has no cache and must see the updated count.
            runBlocking {
                val pkg = assertNotNull(s.client().getPackage(packageId))
                assertEquals(1L, pkg.downloadCount)
            }
            // The telemetry NDJSON line is persisted on disk.
            val telemetry = assertNotNull(s.readDataFile("telemetry.ndjson"))
            assertTrue(telemetry.contains(packageId), telemetry)
            assertTrue(telemetry.contains("\"event\":\"install\""), telemetry)
        }
    }

    @Test
    fun `user report is acknowledged and persisted`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            val reportId = runBlocking {
                s.client().reportPlugin(
                    packageId = packageId,
                    version = "1.0.0",
                    reason = ReportReason.Broken,
                    description = "crashes on boot",
                )
            }
            assertTrue(reportId.startsWith("rpt_"), "acknowledgement carries a tracking id: $reportId")

            val reports = assertNotNull(s.readDataFile("reports.ndjson"))
            assertTrue(reports.contains(packageId), reports)
            assertTrue(reports.contains("\"reason\":\"broken\""), reports)
            assertTrue(reports.contains("crashes on boot"), reports)
        }
    }

    @Test
    fun `version list endpoint returns every listed version`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"

            val v1 = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            v1.response.ok()
            s.publishSubmission(alpha, packageId, v1.submissionId).ok()

            val v2 = s.submitPackage(alpha, pluginManifest(packageId, "1.0.1"))
            v2.response.ok()
            s.publishSubmission(alpha, packageId, v2.submissionId).ok()

            val versions = s.get("/v1/plugins/$packageId/versions").ok()
            assertTrue(versions.body.contains("\"packageId\":\"$packageId\""))
            assertTrue(versions.body.contains("\"version\":\"1.0.0\""), versions.body)
            assertTrue(versions.body.contains("\"version\":\"1.0.1\""), versions.body)
        }
    }

    @Test
    fun `publisher profile lists the publisher's visible packages`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            val profile = s.get("/v1/publishers/alpha").ok()
            assertTrue(profile.body.contains("\"id\":\"alpha\""), profile.body)
            assertTrue(profile.body.contains(packageId), profile.body)

            // Unknown publishers are 404, not empty profiles.
            assertEquals(404, s.get("/v1/publishers/nobody").status)
        }
    }
}
