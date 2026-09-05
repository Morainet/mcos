package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.review.AvVerdict
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate-9 AV seam (12-index-server.md §6 gate 9 / §8.3). The scanner never
 * fabricates a CLEAN verdict: with nothing wired the verdict is UNSCANNED, a
 * denylist hit is MALICIOUS, and an external command that misbehaves falls
 * back rather than passing bytes it did not scan.
 */
class AvScannerTest {

    private fun tempDir(): Path = Files.createTempDirectory("mcos-avscan-test")

    private fun stageArtifact(dir: Path, bytes: ByteArray): Path {
        val p = dir.resolve("artifact.mcos")
        Files.write(p, bytes)
        return p
    }

    /** Writes an executable shell wrapper that ignores stdin and prints [verdict]. */
    private fun scannerScript(dir: Path, verdict: String, exitCode: Int = 0): String {
        val script = dir.resolve("scanner.sh")
        Files.writeString(script, "#!/bin/sh\ncat > /dev/null\necho $verdict\nexit $exitCode\n")
        script.toFile().setExecutable(true)
        return "/bin/sh ${script.absolutePathString()}"
    }

    @Test
    fun `no engine and no denylist reports UNSCANNED`() {
        val dir = tempDir()
        val scanner = CompositeAvScanner(denylistFile = null, scannerCommand = null)
        val bytes = "hello".toByteArray()
        assertEquals(AvVerdict.UNSCANNED, scanner.scan(stageArtifact(dir, bytes), bytes).verdict)
    }

    @Test
    fun `denylist hit is MALICIOUS and checked before the external scanner`() {
        val dir = tempDir()
        val bytes = "evil".toByteArray()
        val denylist = dir.resolve("av-denylist.txt")
        Files.writeString(denylist, sha256Hex(bytes) + "\n")
        // Even a CLEAN-printing scanner must not override the denylist hit.
        val scanner = CompositeAvScanner(denylist, scannerScript(dir, "CLEAN"))
        val scan = scanner.scan(stageArtifact(dir, bytes), bytes)
        assertEquals(AvVerdict.MALICIOUS, scan.verdict)
        assertEquals("sha256-denylist", scan.engineLabel)
    }

    @Test
    fun `wired denylist with no hit reports CLEAN`() {
        val dir = tempDir()
        val denylist = dir.resolve("av-denylist.txt")
        Files.writeString(denylist, "00".repeat(32) + "\n")
        val scanner = CompositeAvScanner(denylist, scannerCommand = null)
        val bytes = "benign".toByteArray()
        assertEquals(AvVerdict.CLEAN, scanner.scan(stageArtifact(dir, bytes), bytes).verdict)
    }

    @Test
    fun `external scanner CLEAN and MALICIOUS verdicts are honoured`() {
        val dir = tempDir()
        val bytes = "payload".toByteArray()
        val clean = CompositeAvScanner(null, scannerScript(dir, "CLEAN"))
        assertEquals(AvVerdict.CLEAN, clean.scan(stageArtifact(dir, bytes), bytes).verdict)

        val malicious = CompositeAvScanner(null, scannerScript(dir, "MALICIOUS"))
        assertEquals(AvVerdict.MALICIOUS, malicious.scan(stageArtifact(dir, bytes), bytes).verdict)
    }

    @Test
    fun `external scanner failure falls back to UNSCANNED not a fabricated CLEAN`() {
        val dir = tempDir()
        val bytes = "payload".toByteArray()
        // Non-zero exit → the verdict is not trusted → UNSCANNED.
        val failing = CompositeAvScanner(null, scannerScript(dir, "CLEAN", exitCode = 3))
        assertEquals(AvVerdict.UNSCANNED, failing.scan(stageArtifact(dir, bytes), bytes).verdict)

        // Unlaunchable command → UNSCANNED.
        val missing = CompositeAvScanner(null, "/nonexistent/scanner-binary")
        assertEquals(AvVerdict.UNSCANNED, missing.scan(stageArtifact(dir, bytes), bytes).verdict)

        // Unrecognised output → UNSCANNED.
        val garbage = CompositeAvScanner(null, scannerScript(dir, "MAYBE"))
        assertEquals(AvVerdict.UNSCANNED, garbage.scan(stageArtifact(dir, bytes), bytes).verdict)
    }

    @Test
    fun `external MALICIOUS routes a real submission to CI_REJECTED end to end`() {
        val dir = tempDir()
        // The scanner flags everything malicious; the artifact bytes are unknown
        // ahead of time, so this proves the seam runs during submission.
        val script = dir.resolve("scanner.sh")
        Files.writeString(script, "#!/bin/sh\ncat > /dev/null\necho MALICIOUS\n")
        script.toFile().setExecutable(true)
        ServerFixture(withAvDenylist = false, avScannerCommand = "/bin/sh ${script.absolutePathString()}").use { s ->
            val alpha = s.createPublisherSession("alpha")
            val submission = s.submitPackage(alpha, pluginManifest("com.example.alpha", "1.0.0"))
            assertTrue(
                submission.response.body.contains("CI_REJECTED"),
                "expected CI_REJECTED for a malicious verdict, got ${submission.response.body}",
            )
        }
    }
}
