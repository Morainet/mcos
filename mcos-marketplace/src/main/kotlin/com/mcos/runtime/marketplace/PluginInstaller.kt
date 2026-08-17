package com.mcos.runtime.marketplace

import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.security.ArtifactSignature
import com.mcos.runtime.security.ArtifactVerifier
import com.mcos.runtime.security.Blocklist as SecurityBlocklist
import com.mcos.runtime.security.EmptyBlocklist
import com.mcos.runtime.security.PublisherKeyStore
import com.mcos.runtime.security.VerifyResult
import com.mcos.runtime.plugin.LoadResult
import com.mcos.runtime.plugin.PluginLoader
import com.mcos.sdk.McosPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of [PluginInstaller.installPackage].
 */
sealed class InstallResult {
    /** Plugin downloaded, verified, staged and registered. */
    data class Installed(
        val packageId: String,
        val version: String,
        val trustLevel: com.mcos.runtime.security.TrustLevel,
        val commandsRegistered: Int,
        val aliasesRegistered: Int,
    ) : InstallResult()

    /** Download / verification / load failure. */
    data class Failed(
        val packageId: String,
        val reason: String,
        val code: String,
    ) : InstallResult()
}

/**
 * Result of [PluginInstaller.updatePackage].
 */
sealed class UpdateResult {
    /** Update adds or escalates elevated/destructive permissions — needs fresh consent. */
    data class NeedsConsent(val diff: PermissionDiff) : UpdateResult()

    /** Update proceeded (silent or consented) and the plugin was reinstalled. */
    data class Installed(
        val packageId: String,
        val version: String,
        val trustLevel: com.mcos.runtime.security.TrustLevel,
        val commandsRegistered: Int,
        val aliasesRegistered: Int,
    ) : UpdateResult()

    data class Failed(
        val packageId: String,
        val reason: String,
        val code: String,
    ) : UpdateResult()
}

/**
 * Result of [PluginInstaller.uninstallPackage].
 */
sealed class UninstallResult {
    /** Plugin unregistered, artifact removed, back to NOT_INSTALLED. */
    data object Done : UninstallResult()

    data class Failed(
        val packageId: String,
        val reason: String,
        val code: String,
    ) : UninstallResult()
}

/**
 * One package force-disabled by [PluginInstaller.applyBlocklist]
 * ([09-marketplace.md §14.4]).
 *
 * The host uses this to notify the user and write the
 * `plugin.force_disabled { packageId, version, reason }` audit record.
 */
data class ForceDisabled(
    val packageId: String,
    val version: String,
    val reason: BlocklistReason,
)

/**
 * End-to-end plugin install / update / uninstall flow
 * ([09-marketplace.md §7]).
 *
 * Install pipeline (normative, §7.1):
 *
 * ```
 * DOWNLOADING → VERIFYING → STAGING → LOADING → INSTALLED
 * ```
 *
 * - DOWNLOADING: artifact bytes fetched via [MarketplaceHttpTransport.getBytes].
 * - VERIFYING:  SHA-256 + publisher signature via [ArtifactVerifier].
 * - STAGING:    artifact written to [downloadDir].
 * - LOADING:    [PluginLoader] registers the plugin (trust gate re-checked).
 * - INSTALLED:  plugin active. Install consent ≠ runtime grant: permissions
 *               are still requested per invocation (§7.1 steps 6-7).
 *
 * Failure transitions (VERIFYING/LOADING → FAILED) clean up the staged file.
 *
 * @param downloadDir directory where artifacts are staged
 *   (`downloadDir/<packageId>-<version>.mcos`).
 * @param onProgress progress callback, invoked on every state change.
 */
class PluginInstaller(
    private val transport: MarketplaceHttpTransport,
    private val verifier: ArtifactVerifier,
    private val keyStore: PublisherKeyStore,
    private val loader: PluginLoader,
    private val registry: CommandRegistry,
    private val downloadDir: String,
    private val onProgress: (InstallProgress) -> Unit = {},
    initialBlocklist: SecurityBlocklist = EmptyBlocklist,
) {
    private val states = ConcurrentHashMap<String, InstallState>()

    /** Version of the artifact currently installed (or force-disabled) per package. */
    private val installedVersions = ConcurrentHashMap<String, String>()

    /** Active blocklist used for the pre-download check (§14.0). */
    @Volatile
    private var blocklist: SecurityBlocklist = initialBlocklist

    /** Current state of a package; defaults to [InstallState.NOT_INSTALLED]. */
    fun stateOf(packageId: String): InstallState = states[packageId] ?: InstallState.NOT_INSTALLED

    private fun setState(packageId: String, version: String?, state: InstallState, message: String? = null) {
        states[packageId] = state
        onProgress(InstallProgress(packageId, state, version, message = message))
    }

    /**
     * Download, verify, stage and load a plugin
     * ([09-marketplace.md §7.1]).
     *
     * @param metadata package metadata from the marketplace index.
     * @param pluginFactory decodes the verified artifact bytes into a plugin
     *   instance ([PluginLoader] then registers it). The factory is only
     *   called after verification passed.
     */
    suspend fun installPackage(
        metadata: PackageMetadata,
        pluginFactory: (ByteArray) -> McosPlugin,
    ): InstallResult {
        val packageId = metadata.packageId
        val version = metadata.version

        // Blocklist gate (§14.0): never fetch or stage a known-bad artifact.
        if (blocklist.isBlocklisted(packageId, version)) {
            setState(packageId, version, InstallState.FAILED, "package is blocklisted")
            return InstallResult.Failed(packageId, "package is blocklisted", "blocklisted")
        }

        // ── DOWNLOADING ────────────────────────────────────────────────────
        setState(packageId, version, InstallState.DOWNLOADING)
        val bytes = try {
            transport.getBytes(metadata.artifact.url, CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS)
        } catch (e: MarketplaceTransportException) {
            setState(packageId, version, InstallState.FAILED, e.message)
            return InstallResult.Failed(packageId, e.message, e.code)
        } catch (e: Exception) {
            setState(packageId, version, InstallState.FAILED, e.message)
            return InstallResult.Failed(packageId, e.message ?: "download failed", "download_failed")
        }

        // ── VERIFYING ──────────────────────────────────────────────────────
        setState(packageId, version, InstallState.VERIFYING)
        val signature = signatureFor(metadata)
        if (signature == null) {
            setState(packageId, version, InstallState.FAILED, "unknown signing key")
            return InstallResult.Failed(packageId, "unknown signing key", "unknown_key")
        }
        val verify = verifier.verify(bytes, signature, packageId, version)
        if (verify is VerifyResult.Rejected) {
            setState(packageId, version, InstallState.FAILED, verify.reason)
            return InstallResult.Failed(packageId, verify.reason, verify.reason)
        }

        // ── STAGING ────────────────────────────────────────────────────────
        setState(packageId, version, InstallState.STAGING)
        val staged = File(downloadDir, "$packageId-$version.mcos")
        try {
            staged.parentFile?.mkdirs()
            staged.writeBytes(bytes)
        } catch (e: Exception) {
            setState(packageId, version, InstallState.FAILED, e.message)
            return InstallResult.Failed(packageId, e.message ?: "staging failed", "staging_failed")
        }

        // ── LOADING ────────────────────────────────────────────────────────
        setState(packageId, version, InstallState.LOADING)
        val plugin = try {
            pluginFactory(bytes)
        } catch (e: Exception) {
            staged.delete()
            setState(packageId, version, InstallState.FAILED, e.message)
            return InstallResult.Failed(packageId, e.message ?: "plugin decode failed", "decode_failed")
        }
        return when (val result = loader.load(packageId, version, bytes, signature, builtin = false, plugin = plugin)) {
            is LoadResult.Installed -> {
                installedVersions[packageId] = version
                setState(packageId, version, InstallState.INSTALLED)
                InstallResult.Installed(
                    packageId = packageId,
                    version = version,
                    trustLevel = result.trustLevel,
                    commandsRegistered = result.commandsRegistered,
                    aliasesRegistered = result.aliasesRegistered,
                )
            }
            is LoadResult.Denied -> {
                staged.delete()
                setState(packageId, version, InstallState.FAILED, result.reason)
                InstallResult.Failed(packageId, result.reason, result.code)
            }
            is LoadResult.Failed -> {
                staged.delete()
                setState(packageId, version, InstallState.FAILED, result.message)
                InstallResult.Failed(packageId, result.message, "load_failed")
            }
        }
    }

    /**
     * Update a plugin to a new marketplace version
     * ([09-marketplace.md §7.2]).
     *
     * Computes the permission diff between [oldMeta] and [newMeta]. If the
     * diff requires fresh consent (added/changed elevated or destructive
     * permissions) and [consentGiven] is false, returns
     * [UpdateResult.NeedsConsent] without downloading anything. Otherwise
     * re-runs the install pipeline for [newMeta].
     *
     * @param consentGiven set to true after the user accepted the diff
     *   (returned by a previous [UpdateResult.NeedsConsent] call).
     */
    suspend fun updatePackage(
        oldMeta: PackageMetadata,
        newMeta: PackageMetadata,
        consentGiven: Boolean = false,
        pluginFactory: (ByteArray) -> McosPlugin,
    ): UpdateResult {
        val diff = computePermissionDiff(oldMeta, newMeta)
        if (diff.consentRequired && !consentGiven) {
            return UpdateResult.NeedsConsent(diff)
        }
        return when (val result = installPackage(newMeta, pluginFactory)) {
            is InstallResult.Installed -> UpdateResult.Installed(
                packageId = result.packageId,
                version = result.version,
                trustLevel = result.trustLevel,
                commandsRegistered = result.commandsRegistered,
                aliasesRegistered = result.aliasesRegistered,
            )
            is InstallResult.Failed -> UpdateResult.Failed(result.packageId, result.reason, result.code)
        }
    }

    /**
     * Uninstall a plugin ([09-marketplace.md §7.3]).
     *
     * Unregisters all descriptors, revokes grants (handled by the host via
     * [onUninstall] if provided), deletes the staged artifact, and returns to
     * [InstallState.NOT_INSTALLED].
     *
     * @param onUninstall optional host hook invoked between unregistering and
     *   deleting the artifact (e.g. grant revocation, SecureStore cleanup).
     */
    suspend fun uninstallPackage(
        packageId: String,
        onUninstall: (suspend () -> Unit)? = null,
    ): UninstallResult {
        setState(packageId, null, InstallState.UNINSTALLING)
        try {
            registry.unregister(packageId)
            onUninstall?.invoke()

            // Delete every staged artifact for this package.
            File(downloadDir).listFiles()?.forEach { file ->
                if (file.name.startsWith("$packageId-")) file.delete()
            }
        } catch (e: Exception) {
            setState(packageId, null, InstallState.FAILED, e.message)
            return UninstallResult.Failed(packageId, e.message ?: "uninstall failed", "uninstall_failed")
        }
        installedVersions.remove(packageId)
        setState(packageId, null, InstallState.NOT_INSTALLED)
        return UninstallResult.Done
    }

    /**
     * Apply the marketplace blocklist ([09-marketplace.md §14.4]).
     *
     * Every installed plugin whose `(packageId, version)` matches an entry is
     * force-disabled: its descriptors are drained from the registry and its
     * [InstallState] transitions to [InstallState.DISABLED] (the artifact
     * stays on disk — the user can uninstall or wait for a patched version).
     *
     * The returned [ForceDisabled] list is the host's cue to notify the user
     * and write the `plugin.force_disabled { packageId, version, reason }`
     * audit record. The active blocklist is also replaced, so subsequent
     * installs of affected versions are rejected by [installPackage].
     *
     * A [BlocklistReason.SECURITY_VULNERABILITY] disable is auto-lifted by
     * installing a version outside the entry's range (normal re-install).
     */
    suspend fun applyBlocklist(blocklistDoc: Blocklist): List<ForceDisabled> {
        this.blocklist = blocklistDoc.asSecurityBlocklist()
        val disabled = mutableListOf<ForceDisabled>()
        for (entry in blocklistDoc.entries) {
            val version = installedVersions[entry.packageId] ?: continue
            when (stateOf(entry.packageId)) {
                InstallState.INSTALLED, InstallState.UPDATE_AVAILABLE -> {
                    if (!VersionRange(entry.versionRange).matches(version)) continue
                    registry.unregister(entry.packageId)
                    setState(entry.packageId, version, InstallState.DISABLED, "blocked: ${entry.reason}")
                    disabled += ForceDisabled(entry.packageId, version, entry.reason)
                }
                // Already disabled / not installed / in-flight: leave alone.
                else -> Unit
            }
        }
        return disabled
    }

    /** Replace the active blocklist used by [installPackage]'s pre-download gate. */
    fun updateBlocklist(blocklist: SecurityBlocklist) {
        this.blocklist = blocklist
    }

    // ─── External transitions (§7.0) ─────────────────────────────────────

    /** Mark a package as having a newer marketplace version. */
    fun markUpdateAvailable(packageId: String, newVersion: String) {
        setState(packageId, newVersion, InstallState.UPDATE_AVAILABLE)
    }

    /** Trust downgrade / quarantine (§14.4): installed but not loaded. */
    fun markDisabled(packageId: String) {
        setState(packageId, null, InstallState.DISABLED)
    }

    /** User re-enables a disabled plugin (with warning). */
    fun markEnabled(packageId: String) {
        setState(packageId, installedVersions[packageId], InstallState.INSTALLED)
    }

    /** Cleanup after a failure: back to NOT_INSTALLED. */
    fun resetFailed(packageId: String) {
        installedVersions.remove(packageId)
        setState(packageId, null, InstallState.NOT_INSTALLED)
    }

    // ─── Internals ────────────────────────────────────────────────────────

    /**
     * Rebuild the [ArtifactSignature] envelope from marketplace metadata.
     * The algorithm is looked up from the publisher key store (the index
     * `ArtifactRef` does not carry the algorithm label).
     */
    private fun signatureFor(metadata: PackageMetadata): ArtifactSignature? {
        val key = keyStore.get(metadata.artifact.signingKeyId) ?: return null
        return ArtifactSignature(
            payloadSha256 = metadata.artifact.sha256,
            signature = metadata.artifact.signature,
            signingKeyId = metadata.artifact.signingKeyId,
            algorithm = key.algorithm,
            signedAt = metadata.updatedAt,
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
