package com.morainet.mcos.indexserver

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Escalation & moderation interop (12-index-server.md §5.4 / §9).
 *
 * The first fixture starts WITHOUT the AV denylist seam so gate 9 reports
 * UNSCANNED — an honest warning that routes every clean submission to
 * HUMAN_REVIEW, exercising the operator decision surface end to end.
 */
class IndexModerationInteropTest {

    private val noAv = ServerFixture(withAvDenylist = false)

    @Test
    fun `unscanned submission escalates to human review then approve then publish`() {
        noAv.use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
                .contains("\"state\":\"HUMAN_REVIEW\"")
                .contains("\"overall\":\"HUMAN_REVIEW\"")

            // A HUMAN_REVIEW submission cannot self-publish yet.
            val premature = s.publishSubmission(alpha, packageId, submission.submissionId)
            assertEquals(409, premature.status)

            // Operator approves; publisher publishes; the client discovers it.
            s.adminApprove(packageId, submission.submissionId).ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            runBlocking {
                val pkg = assertNotNull(s.client().getPackage(packageId))
                assertEquals("1.0.0", pkg.version)
                val results = s.client().search(query = "alpha")
                assertEquals(1, results.results.size)
            }
        }
    }

    @Test
    fun `admin reject lands the submission in REJECTED and blocks publishing`() {
        noAv.use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok().contains("HUMAN_REVIEW")

            s.adminReject(packageId, submission.submissionId).ok()

            // Publisher sees the state via the submission-status endpoint.
            val status = s.get(
                "/v1/publishers/alpha/plugins/$packageId/submissions/${submission.submissionId}",
                alpha.token,
            ).ok()
            assertEquals("REJECTED", status.field("state"))

            // REJECTED is terminal: publishing it fails.
            assertEquals(409, s.publishSubmission(alpha, packageId, submission.submissionId).status)
        }
    }

    @Test
    fun `admin queue reports state and human-review inbox`() {
        noAv.use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()

            val inbox = s.get("/v1/admin/submissions?state=HUMAN_REVIEW", s.adminToken).ok()
            val hits = Regex(""""state":"HUMAN_REVIEW"""").findAll(inbox.body).count()
            assertEquals(1, hits, inbox.body)
        }
    }

    @Test
    fun `operator publish publishes the latest approved submission on the publisher's behalf`() {
        noAv.use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok().contains("HUMAN_REVIEW")

            s.adminApprove(packageId, submission.submissionId).ok()
            s.adminPublish(packageId).ok()

            runBlocking {
                val search = s.client().search(query = "alpha")
                assertEquals(1, search.results.size)
                assertEquals(packageId, search.results.single().packageId)
            }
        }
    }

    @Test
    fun `unlist hides the package from discovery`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            s.adminUnlist(packageId).ok()

            runBlocking {
                val client = s.client()
                assertNull(client.getPackage(packageId))
                val search = client.search(query = "alpha")
                assertEquals(0, search.results.size)
            }
        }
    }

    @Test
    fun `revoke hides the package and propagates a signed blocklist entry`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            s.adminRevoke(packageId, reason = "SIGNATURE_KEY_COMPROMISED").ok()

            runBlocking {
                val client = s.client()
                // Blocked package disappears from the index.
                assertNull(client.getPackage(packageId))

                // ...and the revocation shows up as an operator-signed entry.
                val entries = client.fetchBlocklist().entries
                val entry = entries.single { it.packageId == packageId }
                assertEquals("SIGNATURE_KEY_COMPROMISED", entry.reason.name)
                assertEquals("*", entry.versionRange)
            }
        }
    }
}
