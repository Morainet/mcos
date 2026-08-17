package com.morainet.mcos.runtime.marketplace

import com.morainet.mcos.runtime.registry.CommandRegistry
import com.morainet.mcos.runtime.security.ArtifactSignature
import com.morainet.mcos.runtime.security.ArtifactVerifier
import com.morainet.mcos.runtime.security.Blocklist as SecurityBlocklist
import com.morainet.mcos.runtime.security.InMemoryPublisherKeyStore
import com.morainet.mcos.runtime.security.KeyStatus
import com.morainet.mcos.runtime.security.PluginTrustGate
import com.morainet.mcos.runtime.security.PublisherKey
import com.morainet.mcos.runtime.plugin.PluginLoader
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
 * Unit tests for §14.4 — force-disable of installed plugins via the blocklist.
 */
class PluginInstallerBlocklistTest {

    private val payload = "hello-mcos-plugin".encodeToByteArray()

    // ─── Fixtures (mirror PluginInstallerTest) ───────────────────────────

    private fun keyPair() = run {
        val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
        kpg.generateKeyPair()
    }

    private fun pubKey(pair: java.security.KeyPair): PublisherKey =
        PublisherKey(
            keyId = "key_2026_01",
            publisherId = "pub_1",
            publicKeyFingerprint = "ff".repeat(32),
            algorithm = "Ed25519",
            publicKeyEncoded = java.util.Base64.getEncoder().encodeToString(pair.public.encoded),
            createdAt = "2026-01-01T00:00:00Z",
            status = KeyStatus.ACTIVE,
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
            artifact = ArtifactRef(
                url = "https://cdn.example.com/$packageId-$version.mcos",
                sha256 = sha256Hex(payload),
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

    private class FakeTransport(
        var artifactBytes: ByteArray = "hello-mcos-plugin".encodeToByteArray(),
        var getBytesCalls: Int = 0,
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
            getBytesCalls++
            return artifactBytes
        }
    }

    private class Harness(
        val transport: FakeTransport = FakeTransport(),
        val registry: CommandRegistry = CommandRegistry(),
    ) {
        val downloadDir = Files.createTempDirectory("mcos-blocklist-test").toString()
        val store = InMemoryPublisherKeyStore()
        val installer: PluginInstaller

        init {
            val gate = PluginTrustGate(verifier = ArtifactVerifier(store), debugBuild = true)
            val loader = PluginLoader(gate, registry)
            installer = PluginInstaller(
                transport = transport,
                verifier = ArtifactVerifier(store),
                keyStore = store,
                loader = loader,
                registry = registry,
                downloadDir = downloadDir,
            )
        }
    }

    private fun blocklistDoc(
        packageId: String,
        versionRange: String,
        reason: BlocklistReason,
    ): Blocklist = Blocklist(
        entries = listOf(
            BlocklistEntry(
                packageId = packageId,
                versionRange = versionRange,
                reason = reason,
                blockedAt = "2026-03-01T00:00:00Z",
            ),
        ),
        version = "3",
        issuedAt = "2026-03-01T00:00:00Z",
        signature = "sig",
    )

    // ─── B1-B6: §14.4 force-disable ──────────────────────────────────────

    @Test
    fun `B1-force-disable disables matching installed plugin and drains descriptors`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))
        val meta = metadata(pair)

        h.installer.installPackage(meta) { createPlugin(meta.packageId, meta.version) }
        assertEquals(1, h.registry.entryCount())

        val disabled = h.installer.applyBlocklist(
            blocklistDoc(meta.packageId, ">=1.0.0 <2.0.0", BlocklistReason.MALWARE),
        )

        assertEquals(1, disabled.size)
        assertEquals(ForceDisabled(meta.packageId, "1.0.0", BlocklistReason.MALWARE), disabled.single())
        assertEquals(InstallState.DISABLED, h.installer.stateOf(meta.packageId))
        assertTrue(h.registry.getByPlugin(meta.packageId).isEmpty(), "descriptors must be drained")
        assertFalse(h.registry.isRegistered("${meta.packageId}.ping"))
        // Artifact stays on disk (DISABLED, not NOT_INSTALLED).
        assertTrue(java.io.File(h.downloadDir, "${meta.packageId}-${meta.version}.mcos").exists())
    }

    @Test
    fun `B2-versions outside the range stay installed`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))
        val meta = metadata(pair, version = "2.0.0")

        h.installer.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        val disabled = h.installer.applyBlocklist(
            blocklistDoc(meta.packageId, "<2.0.0", BlocklistReason.POLICY_VIOLATION),
        )

        assertTrue(disabled.isEmpty(), "no version matches the range")
        assertEquals(InstallState.INSTALLED, h.installer.stateOf(meta.packageId))
        assertEquals(1, h.registry.entryCount())
    }

    @Test
    fun `B3-installing a blocklisted version is rejected without downloading`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))

        // Activate a blocklist covering 1.5.0.
        h.installer.applyBlocklist(
            blocklistDoc("com.example.a", ">=1.5.0 <1.6.0", BlocklistReason.SECURITY_VULNERABILITY),
        )
        val meta = metadata(pair, version = "1.5.0")

        val result = h.installer.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        assertIs<InstallResult.Failed>(result)
        assertEquals("blocklisted", result.code)
        assertEquals(0, h.transport.getBytesCalls, "must not fetch a known-bad artifact")
        assertEquals(InstallState.FAILED, h.installer.stateOf(meta.packageId))
    }

    @Test
    fun `B4-update to a patched version auto re-enables a vulnerability-disabled plugin`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))
        val old = metadata(pair, version = "1.0.0")

        h.installer.installPackage(old) { createPlugin(old.packageId, old.version) }
        h.installer.applyBlocklist(
            blocklistDoc(old.packageId, ">=1.0.0 <1.1.0", BlocklistReason.SECURITY_VULNERABILITY),
        )
        assertEquals(InstallState.DISABLED, h.installer.stateOf(old.packageId))

        val patched = metadata(pair, version = "1.1.0")
        val result = h.installer.updatePackage(
            oldMeta = old,
            newMeta = patched,
            pluginFactory = { createPlugin(patched.packageId, patched.version) },
        )

        assertIs<UpdateResult.Installed>(result)
        assertEquals("1.1.0", result.version)
        assertEquals(InstallState.INSTALLED, h.installer.stateOf(patched.packageId))
        assertEquals(1, h.registry.entryCount())
    }

    @Test
    fun `B5-uninstall still works from DISABLED state`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))
        val meta = metadata(pair)

        h.installer.installPackage(meta) { createPlugin(meta.packageId, meta.version) }
        h.installer.applyBlocklist(
            blocklistDoc(meta.packageId, "*", BlocklistReason.PUBLISHER_BANNED),
        )
        assertEquals(InstallState.DISABLED, h.installer.stateOf(meta.packageId))

        val result = h.installer.uninstallPackage(meta.packageId)

        assertIs<UninstallResult.Done>(result)
        assertEquals(InstallState.NOT_INSTALLED, h.installer.stateOf(meta.packageId))
        assertFalse(java.io.File(h.downloadDir, "${meta.packageId}-${meta.version}.mcos").exists())
    }

    @Test
    fun `B6-updateBlocklist affects subsequent installs`() = runBlocking {
        val pair = keyPair()
        val h = Harness()
        h.store.put(pubKey(pair))

        h.installer.updateBlocklist(
            SecurityBlocklist { packageId, version ->
                packageId == "com.example.a" && version == "9.9.9"
            },
        )
        val meta = metadata(pair, version = "9.9.9")

        val result = h.installer.installPackage(meta) { createPlugin(meta.packageId, meta.version) }

        assertIs<InstallResult.Failed>(result)
        assertEquals("blocklisted", result.code)
        assertEquals(0, h.transport.getBytesCalls)
    }

    @Test
    fun `B7-blocklist doc bridge checks package and version independently`() {
        val doc = blocklistDoc("com.example.a", ">=1.0.0 <2.0.0", BlocklistReason.MALWARE)

        assertTrue(doc.isBlocklisted("com.example.a", "1.5.0"))
        assertFalse(doc.isBlocklisted("com.example.b", "1.5.0"), "other package unaffected")
        assertFalse(doc.isBlocklisted("com.example.a", "2.5.0"), "version outside range unaffected")

        val security = doc.asSecurityBlocklist()
        assertTrue(security.isBlocklisted("com.example.a", "1.5.0"))
        assertFalse(security.isBlocklisted(null, "1.5.0"))
        assertFalse(security.isBlocklisted("com.example.a", null))
    }
}
