package com.morainet.mcos.runtime.core.plugin

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.IsolationRequiredHandler
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/**
 * Unit tests for [PluginLoader] — the load-time pipeline
 * PluginTrustGate → CommandRegistry ([03-runtime.md §16], [09-marketplace.md §7.0]).
 */
class PluginLoaderTest {

    private val fakePayload = byteArrayOf(1, 2, 3, 4)

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
        override fun handlers(): Map<String, CommandHandler> = emptyMap()
    }

    // ═══════════════════════════════════════════════════════════════
    // T1-T2: Builtin plugins
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T1-builtin plugin installs as BUILTIN without payload`() {
        val registry = CommandRegistry()
        val loader = PluginLoader(PluginTrustGate(debugBuild = false), registry)
        val plugin = createPlugin("com.example.core", "1.0.0")

        val result = loader.load("com.example.core", "1.0.0", null, null, builtin = true, plugin)

        assertIs<LoadResult.Installed>(result)
        assertEquals(TrustLevel.BUILTIN, result.trustLevel)
    }

    @Test
    fun `T2-non-builtin missing payload denied`() {
        val loader = PluginLoader(PluginTrustGate(debugBuild = true), CommandRegistry())

        val result = loader.load("com.example.core", "1.0.0", null, null, builtin = false, createPlugin("com.example.core", "1.0.0"))

        assertIs<LoadResult.Denied>(result)
        assertEquals("missing_payload", result.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // T3-T5: Signed artifacts (MARKETPLACE_VERIFIED)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T3-valid signature installs as MARKETPLACE_VERIFIED`() {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val gate = PluginTrustGate(verifier = ArtifactVerifier(store), debugBuild = false)
        val registry = CommandRegistry()
        val loader = PluginLoader(gate, registry)

        val sig = sign(pair, fakePayload)
        val result = loader.load("com.example.plugin", "1.0.0", fakePayload, sig, builtin = false, createPlugin("com.example.plugin", "1.0.0"))

        assertIs<LoadResult.Installed>(result)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, result.trustLevel)
    }

    @Test
    fun `T4-revoked key denied`() {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair, KeyStatus.REVOKED)) }
        val gate = PluginTrustGate(verifier = ArtifactVerifier(store), debugBuild = true)
        val loader = PluginLoader(gate, CommandRegistry())

        val sig = sign(pair, fakePayload)
        val result = loader.load("com.example.plugin", "1.0.0", fakePayload, sig, builtin = false, createPlugin("com.example.plugin", "1.0.0"))

        assertIs<LoadResult.Denied>(result)
        assertEquals("signature_key_revoked", result.code)
    }

    @Test
    fun `T5-unsigned sideload denied when debug off`() {
        val loader = PluginLoader(PluginTrustGate(debugBuild = false), CommandRegistry())

        val result = loader.load("com.example.plugin", "1.0.0", fakePayload, null, builtin = false, createPlugin("com.example.plugin", "1.0.0"))

        assertIs<LoadResult.Denied>(result)
        assertEquals("sideload_production_denied", result.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // T6: Registered plugin is executable through the registry
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T6-registered handler resolves through the registry`() {
        val registry = CommandRegistry()
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "com.example.hello",
                name = "Hello",
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "d",
                provider = ProviderInfo("Test", "https://test.local"),
                entry = "com.morainet.mcos.plugin.test.Hello",
                commands = listOf(
                    com.morainet.mcos.sdk.CommandManifestEntry(
                        id = "hello.greet",
                        version = "1",
                        title = "Greet",
                        description = "g",
                        sideEffectClass = com.morainet.mcos.sdk.SideEffectClass.read,
                    ),
                ),
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                "hello.greet" to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult =
                        CommandResult.Ok(JsonPrimitive("hi"))
                },
            )
        }
        val loader = PluginLoader(PluginTrustGate(debugBuild = false), registry)

        val result = loader.load("com.example.hello", "1.0.0", null, null, builtin = true, plugin)

        assertIs<LoadResult.Installed>(result)
        assertEquals(1, result.commandsRegistered)
        assertIs<ResolveResult.Found>(registry.resolve("hello.greet"))
    }

    // ═══════════════════════════════════════════════════════════════
    // T7-T9: Manifest-only loading (08 §8 — no plugin code in-process)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T7-valid signature installs manifest-only as MARKETPLACE_VERIFIED`() {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val registry = CommandRegistry()
        val loader = PluginLoader(PluginTrustGate(verifier = ArtifactVerifier(store)), registry)

        val sig = sign(pair, fakePayload)
        val result = loader.loadManifest(
            "com.example.plugin", "1.0.0", fakePayload, sig,
            manifest = wireManifest("com.example.plugin"),
        )

        assertIs<LoadResult.Installed>(result)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, result.trustLevel)
        assertEquals(1, result.commandsRegistered)
        // registered from the manifest alone — the entry carries the isolation
        // stub, never plugin code
        val resolved = registry.resolve("wire.fetch")
        assertIs<ResolveResult.Found>(resolved)
        assertEquals(IsolationRequiredHandler, resolved.entry.handler)
    }

    @Test
    fun `T8-loadManifest refuses id masquerade`() {
        val pair = keyPair()
        val store = InMemoryPublisherKeyStore().apply { put(pubKey(pair)) }
        val registry = CommandRegistry()
        val loader = PluginLoader(PluginTrustGate(verifier = ArtifactVerifier(store)), registry)

        val result = loader.loadManifest(
            "com.example.other", "1.0.0", fakePayload, sign(pair, fakePayload),
            manifest = wireManifest("com.example.plugin"),
        )

        assertIs<LoadResult.Failed>(result)
        assertTrue(result.message.contains("does not match"))
        assertIs<ResolveResult.NotFound>(registry.resolve("wire.fetch"))
    }

    @Test
    fun `T9-loadManifest unsigned sideload denied`() {
        val registry = CommandRegistry()
        val loader = PluginLoader(PluginTrustGate(debugBuild = false), registry)

        val result = loader.loadManifest(
            "com.example.plugin", "1.0.0", fakePayload, null,
            manifest = wireManifest("com.example.plugin"),
        )

        assertIs<LoadResult.Denied>(result)
        assertEquals("sideload_production_denied", result.code)
    }

    private fun wireManifest(id: String) = PluginManifest(
        id = id,
        name = id,
        version = "1.0.0",
        minRuntimeVersion = "0.1.0",
        description = "wire manifest",
        provider = ProviderInfo("Test", "https://test.local"),
        entry = "com.test.Wire",
        commands = listOf(
            com.morainet.mcos.sdk.CommandManifestEntry(
                id = "wire.fetch",
                version = "1.0.0",
                title = "Fetch",
                description = "network fetch",
                sideEffectClass = com.morainet.mcos.sdk.SideEffectClass.network,
            ),
        ),
    )

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun sign(pair: java.security.KeyPair, payload: ByteArray): com.morainet.mcos.security.ArtifactSignature {
        val signer = java.security.Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(payload)
        val bytes = signer.sign()
        return com.morainet.mcos.security.ArtifactSignature(
            payloadSha256 = sha256Hex(payload),
            signature = java.util.Base64.getEncoder().encodeToString(bytes),
            signingKeyId = "key_2026_01",
            algorithm = "Ed25519",
            signedAt = "2026-01-01T00:00:00Z",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
