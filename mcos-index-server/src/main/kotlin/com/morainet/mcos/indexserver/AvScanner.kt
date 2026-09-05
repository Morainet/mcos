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
 *     artifact path on stdin and prints `CLEAN` / `MALICIOUS`;
 *  3. otherwise [AvVerdict.UNSCANNED] (with a denylist present but no hit, the file
 *     acts as a wired engine and reports CLEAN — preserving prior behaviour).
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

        scannerCommand?.let { command ->
            runExternal(command, artifactPath)?.let { return it }
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
