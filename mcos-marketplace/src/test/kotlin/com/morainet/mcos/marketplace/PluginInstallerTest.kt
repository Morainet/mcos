package com.morainet.mcos.marketplace

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * Unit tests for [PluginInstaller] — §7.1 install, §7.2 update, §7.3 uninstall.
 */
class PluginInstallerTest {

    private val payload = "hello-mcos-plugin".encodeToByteArray()
    private val downloadDir = Files.createTempDirectory("mcos-install-test").toString()

    // ─── Fixtures ─────────────────────────────────────────────────────────

    private fun keyPair() = run {
        val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
        kpg.generateKeyPair()
    }

    private fun pubKey(pair: java.security.KeyPair, status: KeyStatus = KeyStatus.ACTIVE): PublisherKey =
        PublisherKey(
            keyId = "key_2026_01",
            publisherId = "pub_1",
            publicKeyFingerprint = "ff".repeat(32),
            algorithm = "Ed25519",
            publicKeyEncoded = java.util.Base64.getEncoder().encodeToString(pair.public.encoded),
            createdAt = "2026-01-01T00:00:00Z",
            status = status,
        )

    private fun sign(pair: java.security.KeyPair, payload: ByteArray): ArtifactSignature {
        val signer = java.security.Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload)
        return ArtifactSignature(
            payloadSha256 = sha256Hex(payload),
            signature = java.util.Base64.getEncoder().encodeToString(signer.sign()),
            signingKeyId = "key_2026_01",
            algorithm = "Ed25519",
            signedAt = "2026-02-01T00:00:00Z",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun metadata(
        pair: java.security.KeyPair,
        packageId: String = "com.example.a",
        version: String = "1.0.0",
        permissions: List<MarketplacePermissionEntry> = emptyList(),
        tamperedSha: Boolean = false,
    ): PackageMetadata {
        val sig = sign(pair, payload)
        return PackageMetadata(
            packageId = packageId,
            name = packageId,
            version = version,
            minRuntimeVersion = "0.9.0",
            publisherId = "pub_1",
            publisherName = "Pub",
            summary = "s",
            permissionsPreview = permissions,
            artifact = ArtifactRef(
                url = "https://cdn.example.com/$packageId-$version.mcos",
                sha256 = if (tamperedSha) "00".repeat(32) else sha256Hex(payload),
                signature = sig.signature,
                signingKeyId = "key_2026_01",
            ),
            publishedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-02-01T00:00:00Z",
        )
    }

    private fun createPlugin(id: String, version: String): McosPlugin = object : McosPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            version = version,
            minRuntimeVersion = "0.1.0",
            description = "Test plugin $id",
            provider = ProviderInfo("Test", "https://test.local"),
            entry = "com.morainet.mcos.plugin.test.TestPlugin",
        )
        override suspend fun onLoad(services: HostServices) {}
        override suspend fun onUnload() {}
        override fun handlers(): Map<String, CommandHandler> = mapOf(
            "$id.ping" to object : CommandHandler {
                override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                    CommandResult.Ok(kotlinx.serialization.json.JsonPrimitive("pong"))
            },
        )
    }

    private open class FakeTransport(
        var artifactBytes: ByteArray = "hello-mcos-plugin".encodeToByteArray(),
        var transportException: MarketplaceTransportException? = null,
    ) : MarketplaceHttpTransport {
        override suspend fun getJson(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): MarketplaceHttpResponse = error("not used in installer tests")

        override suspend fun getBytes(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): ByteArray {
            transportException?.let { throw it }
            return artifactBytes
        }
    }

    private fun installer(
        store: InMemoryPublisherKeyStore,
        transport: FakeTransport = FakeTransport(),
        debugBuild: Boolean = false,
        recordStore: InstallRecordStore? = null,
    ): PluginInstaller {
        val gate = PluginTrustGate(verifier = ArtifactVerifier(store), debugBuild = debugBuild)
        val registry = CommandRegistry()
        val loader = PluginLoader(gate, registry)
        return PluginInstaller(
            transport = transport,
            verifier = ArtifactVerifier(store),
            keyStore = store,
            loader = loader,
            registry = registry,
            downloadDir = downloadDir,
            installRecordStore = recordStore,
        )
    }

    /**
     * A "restarted process" over the same download dir and record store:
     * empty publisher key store, fresh registry/loader — only the pinned
     * record file and staged artifact survive.
     */
    private fun restartInstaller(
        recordStore: InstallRecordStore,
    ): Triple<PluginInstaller, InMemoryPublisherKeyStore, CommandRegistry> {
        val keys = InMemoryPublisherKeyStore()
        val registry = CommandRegistry()
        val loader = PluginLoader(PluginTrustGate(verifier = ArtifactVerifier(keys)), registry)
        val inst = PluginInstaller(
            transport = FakeTransport(),
            verifier = ArtifactVerifier(keys),
            keyStore = keys,
            loader = loader,
            registry = registry,
            downloadDir = downloadDir,
            installRecordStore = recordStore,
        )
        return Triple(inst, keys, registry)
    }

    // ─── T1-T3: Install pipeline ──────────────────────────────────────────

    @Test
    fun `T1-valid artifact installs as MARKETPLACE_VERIFIED`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val meta = metadata(pair)
        val progress = mutableListOf<InstallState>()

        inst.installPackage(meta) { bytes ->
            assertEquals(payload.toList(), bytes.toList(), "factory receives verified artifact bytes")
            createPlugin(meta.packageId, meta.version)
        }.also { result ->
            assertIs<InstallResult.Installed>(result)
            assertEquals(TrustLevel.MARKETPLACE_VERIFIED, result.trustLevel)
            assertEquals(1, result.commandsRegistered)
        }

        assertEquals(InstallState.INSTALLED, inst.stateOf(meta.packageId))
        assertTrue(java.io.File(downloadDir, "${meta.packageId}-${meta.version}.mcos").exists())
    }

    @Test
    fun `T2-sha mismatch fails at VERIFYING and cleans up`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val meta = metadata(pair, tamperedSha = true)

        val result = inst.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        assertIs<InstallResult.Failed>(result)
        assertEquals("hash_mismatch", result.code)
        assertEquals(InstallState.FAILED, inst.stateOf(meta.packageId))
    }

    @Test
    fun `T3-download timeout fails at DOWNLOADING`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val transport = FakeTransport(transportException = MarketplaceTransportException("MARKETPLACE_TIMEOUT", "timeout", true))
        val inst = installer(store, transport)
        val meta = metadata(pair)

        val result = inst.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        assertIs<InstallResult.Failed>(result)
        assertEquals("MARKETPLACE_TIMEOUT", result.code)
        assertEquals(InstallState.FAILED, inst.stateOf(meta.packageId))
    }

    // ─── T4-T6: Update flow ───────────────────────────────────────────────

    @Test
    fun `T4-silent update with no new permissions`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val old = metadata(pair, version = "1.0.0")
        val new = metadata(pair, version = "1.1.0")

        inst.installPackage(old) { createPlugin(old.packageId, old.version) }

        val result = inst.updatePackage(old, new, pluginFactory = { createPlugin(new.packageId, new.version) })

        assertIs<UpdateResult.Installed>(result)
        assertEquals("1.1.0", result.version)
        assertEquals(InstallState.INSTALLED, inst.stateOf(new.packageId))
    }

    @Test
    fun `T5-update adding elevated permission needs consent`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val old = metadata(pair, version = "1.0.0")
        val new = metadata(
            pair,
            version = "1.1.0",
            permissions = listOf(MarketplacePermissionEntry("android", "CAMERA", "elevated")),
        )

        inst.installPackage(old) { createPlugin(old.packageId, old.version) }

        val result = inst.updatePackage(old, new, consentGiven = false, pluginFactory = { createPlugin(new.packageId, new.version) })

        assertIs<UpdateResult.NeedsConsent>(result)
        assertTrue(result.diff.consentRequired)
        // Nothing was downloaded/installed yet.
        assertEquals(InstallState.INSTALLED, inst.stateOf(new.packageId))
    }

    @Test
    fun `T6-update proceeds after consent`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val old = metadata(pair, version = "1.0.0")
        val new = metadata(
            pair,
            version = "1.1.0",
            permissions = listOf(MarketplacePermissionEntry("android", "CAMERA", "elevated")),
        )

        inst.installPackage(old) { createPlugin(old.packageId, old.version) }

        val result = inst.updatePackage(old, new, consentGiven = true, pluginFactory = { createPlugin(new.packageId, new.version) })

        assertIs<UpdateResult.Installed>(result)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, result.trustLevel)
    }

    // ─── T7-T8: Uninstall ─────────────────────────────────────────────────

    @Test
    fun `T7-uninstall removes descriptors and artifact`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val inst = installer(store)
        val meta = metadata(pair)

        inst.installPackage(meta) { createPlugin(meta.packageId, meta.version) }
        val staged = java.io.File(downloadDir, "${meta.packageId}-${meta.version}.mcos")
        assertTrue(staged.exists())

        var hookCalled = false
        val result = inst.uninstallPackage(meta.packageId) { hookCalled = true }

        assertIs<UninstallResult.Done>(result)
        assertTrue(hookCalled, "onUninstall hook should run")
        assertFalse(staged.exists(), "staged artifact should be deleted")
        assertEquals(InstallState.NOT_INSTALLED, inst.stateOf(meta.packageId))
    }

    @Test
    fun `T8-revoked key install fails`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair, KeyStatus.REVOKED)) }
        val inst = installer(store, debugBuild = true)
        val meta = metadata(pair)

        val result = inst.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        assertIs<InstallResult.Failed>(result)
        assertEquals(InstallState.FAILED, inst.stateOf(meta.packageId))
    }

    // ─── T9-T14: durable records + rehydration ────────────────────────────

    @Test
    fun `T9-install persists record and rehydrates after restart`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val recordStore = InstallRecordStore(java.io.File(downloadDir, "records.json"))
        val meta = metadata(pair)
        installer(store, recordStore = recordStore)
            .installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        // Restart: empty key store, fresh registry — only the record file
        // and the staged artifact survive.
        val (restarted, keys, registry) = restartInstaller(recordStore)
        assertEquals(InstallState.INSTALLED, restarted.stateOf(meta.packageId))

        val outcomes = restarted.rehydrateInstalled(
            pluginFactory = { _ -> { _ -> createPlugin(meta.packageId, meta.version) } },
            seedKey = { keys.put(it) },
        )
        assertEquals(1, outcomes.size)
        assertEquals(InstallState.INSTALLED, outcomes.single().state)
        assertNotNull(outcomes.single().plugin, "plugin instance handed back for onLoad")
        assertEquals(1, registry.allCommands().size, "commands re-registered after rehydrate")
    }

    @Test
    fun `T10-uninstall persists removal across restart`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val recordStore = InstallRecordStore(java.io.File(downloadDir, "records.json"))
        val meta = metadata(pair)
        installer(store, recordStore = recordStore)
            .installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        val (restarted, _, _) = restartInstaller(recordStore)
        val staged = java.io.File(downloadDir, "${meta.packageId}-${meta.version}.mcos")
        assertTrue(staged.exists(), "staged artifact survives the restart")

        assertIs<UninstallResult.Done>(restarted.uninstallPackage(meta.packageId))
        assertFalse(staged.exists(), "uninstall after restart deletes the staged artifact")

        val afterRestart = restartInstaller(recordStore)
        assertEquals(InstallState.NOT_INSTALLED, afterRestart.first.stateOf(meta.packageId), "record removed")
    }

    @Test
    fun `T11-tampered staged artifact fails closed on rehydrate`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val recordStore = InstallRecordStore(java.io.File(downloadDir, "records.json"))
        val meta = metadata(pair)
        installer(store, recordStore = recordStore)
            .installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        // Attack: swap the staged bytes after the fact.
        java.io.File(downloadDir, "${meta.packageId}-${meta.version}.mcos")
            .writeBytes("evil-payload".encodeToByteArray())

        val (restarted, keys, registry) = restartInstaller(recordStore)
        val outcomes = restarted.rehydrateInstalled(
            pluginFactory = { _ -> { _ -> createPlugin(meta.packageId, meta.version) } },
            seedKey = { keys.put(it) },
        )
        assertEquals(InstallState.FAILED, outcomes.single().state, "tampered bytes never load")
        assertNull(outcomes.single().plugin)
        assertTrue(registry.allCommands().isEmpty(), "nothing registered from tampered bytes")

        // The record is dropped: a later restart reads NOT_INSTALLED.
        assertEquals(
            InstallState.NOT_INSTALLED,
            restartInstaller(recordStore).first.stateOf(meta.packageId),
        )
    }

    @Test
    fun `T12-disabled record survives restart and stays unloaded`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val recordStore = InstallRecordStore(java.io.File(downloadDir, "records.json"))
        val meta = metadata(pair)
        val inst = installer(store, recordStore = recordStore)
        inst.installPackage(meta) { createPlugin(meta.packageId, meta.version) }
        inst.markDisabled(meta.packageId)

        val (restarted, keys, registry) = restartInstaller(recordStore)
        assertEquals(InstallState.DISABLED, restarted.stateOf(meta.packageId))

        val outcomes = restarted.rehydrateInstalled(
            pluginFactory = { _ -> { _ -> createPlugin(meta.packageId, meta.version) } },
            seedKey = { keys.put(it) },
        )
        assertEquals(InstallState.DISABLED, outcomes.single().state)
        assertNull(outcomes.single().plugin, "disabled plugins stay unloaded (§14.4)")
        assertTrue(registry.allCommands().isEmpty())
    }

    @Test
    fun `T13-corrupt record store fails closed`() = runBlocking {
        val recordFile = java.io.File(downloadDir, "records.json")
        recordFile.writeText("not json at all\n")
        val (restarted, _, _) = restartInstaller(InstallRecordStore(recordFile))

        assertEquals(InstallState.NOT_INSTALLED, restarted.stateOf("com.example.a"))
        assertTrue(restarted.rehydrateInstalled(pluginFactory = { _ -> null }).isEmpty())
    }

    @Test
    fun `T14-tampered record file is discarded via hmac`() = runBlocking {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val hmacKey = "device-bound".toByteArray()
        val recordFile = java.io.File(downloadDir, "records.json")
        val recordStore = InstallRecordStore(recordFile, hmacKey)
        val meta = metadata(pair)
        installer(store, recordStore = recordStore)
            .installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        // Attack: rewrite the payload line (e.g. bump the version), keep
        // the signature line — HMAC verification must discard the file.
        val lines = recordFile.readLines().toMutableList()
        lines[0] = lines[0].replace("1.0.0", "9.9.9")
        recordFile.writeText(lines.joinToString("\n") + "\n")

        val (restarted, _, _) = restartInstaller(InstallRecordStore(recordFile, hmacKey))
        assertEquals(InstallState.NOT_INSTALLED, restarted.stateOf(meta.packageId))
        assertTrue(restarted.rehydrateInstalled(pluginFactory = { _ -> null }).isEmpty())
    }
}
