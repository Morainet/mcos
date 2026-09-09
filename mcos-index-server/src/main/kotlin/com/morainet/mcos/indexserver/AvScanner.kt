package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.review.ArtifactScan
import com.morainet.mcos.marketplace.review.AvVerdict
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Gate-9 malware-scan seam (12-index-server.md §6 gate 9 / §8.3).
 *
 * The engine never invents a scan result: with no scanner wired and no denylist
 * hit the verdict is [AvVerdict.UNSCANNED], which routes the submission to human
 * review rather than silently passing unscanned bytes.
 */
internal interface AvScanner {
    /** Scans [artifactBytes] (already staged at [artifactPath]) for malware. */
    fun scan(artifactPath: Path, artifactBytes: ByteArray): ArtifactScan
}

/**
 * Composite scanner matching the shipped MVP posture:
 *  1. sha256 denylist file (`<data-dir>/av-denylist.txt`, one hex hash per line) —
 *     a hit is [AvVerdict.MALICIOUS], authoritative and checked first;
 *  2. an external scanner command (`MCOS_AV_SCANNER_CMD`, §8.3) that receives the
 *     artifact path on stdin and prints `CLEAN` / `MALICIOUS`. Once wired it is
 *     authoritative: a failure to produce a verdict yields
 *     [AvVerdict.UNSCANNED], never a fallback to CLEAN;
 *  3. otherwise [AvVerdict.UNSCANNED] (with a denylist present but no hit and no
 *     external engine, the denylist acts as the wired engine and reports CLEAN
 *     — preserving prior behaviour).
 */
internal class CompositeAvScanner(
    private val denylistFile: Path?,
    private val scannerCommand: String?,
    private val timeout: java.time.Duration = java.time.Duration.ofSeconds(30),
) : AvScanner {

    override fun scan(artifactPath: Path, artifactBytes: ByteArray): ArtifactScan {
        val sha = sha256Hex(artifactBytes)
        val denylist = denylistFile?.let { loadSha256Denylist(it) } ?: emptySet()
        if (sha in denylist) return ArtifactScan(AvVerdict.MALICIOUS, "sha256-denylist")

        // An external engine, once wired, is authoritative for CLEAN: if it
        // cannot produce a verdict (launch failure, timeout, non-zero exit,
        // unrecognised output) the outcome is UNSCANNED → human review. The
        // failure must NOT fall through to the denylist's CLEAN below — a hash
        // list that simply did not match is not a scan that happened, and
        // reporting CLEAN here would let a crashed scanner approve malware.
        if (scannerCommand != null) {
            return runExternal(scannerCommand, artifactPath)
                ?: ArtifactScan(AvVerdict.UNSCANNED, "external-scanner-unavailable")
        }

        val denylistWired = denylistFile != null && Files.exists(denylistFile)
        return if (denylistWired) ArtifactScan(AvVerdict.CLEAN, "sha256-denylist")
        else ArtifactScan.Unscanned
    }

    /**
     * Runs the operator's scanner: artifact path on stdin, `CLEAN`/`MALICIOUS`
     * on stdout. Any launch failure, timeout, non-zero exit, or unrecognised
     * output yields `null` → the caller falls back (never a fabricated CLEAN).
     */
    private fun runExternal(command: String, artifactPath: Path): ArtifactScan? {
        val process = try {
            ProcessBuilder(splitCommand(command))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            System.err.println("av-scanner: failed to launch '$command': ${e.message}")
            return null
        }
        return try {
            process.outputStream.use { it.write(artifactPath.toAbsolutePath().toString().toByteArray()) }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                System.err.println("av-scanner: '$command' timed out")
                return null
            }
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            when {
                process.exitValue() != 0 -> {
                    System.err.println("av-scanner: '$command' exited ${process.exitValue()}: $output")
                    null
                }
                output.equals("MALICIOUS", ignoreCase = true) -> ArtifactScan(AvVerdict.MALICIOUS, "external")
                output.equals("CLEAN", ignoreCase = true) -> ArtifactScan(AvVerdict.CLEAN, "external")
                else -> {
                    System.err.println("av-scanner: '$command' unrecognised output: $output")
                    null
                }
            }
        } catch (e: Exception) {
            System.err.println("av-scanner: '$command' failed: ${e.message}")
            null
        } finally {
            process.destroyForcibly()
        }
    }

    /** Minimal whitespace tokeniser — operators point this at a wrapper script. */
    private fun splitCommand(command: String): List<String> =
        command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
