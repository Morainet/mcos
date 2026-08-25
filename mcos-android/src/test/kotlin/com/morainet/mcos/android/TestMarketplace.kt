package com.morainet.mcos.android

import com.morainet.mcos.android.host.ActivityResultBridge
import com.morainet.mcos.android.host.InMemoryFacade
import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.marketplace.ArtifactRef
import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.InstallProgress
import com.morainet.mcos.marketplace.MarketplaceHttpResponse
import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplacePermissionEntry
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.marketplace.PluginInstaller
import com.morainet.mcos.marketplace.RecipeEnvelope
import com.morainet.mcos.marketplace.RecipeInstaller
import com.morainet.mcos.marketplace.RecipePlaceholder
import com.morainet.mcos.marketplace.RecipeSearchResponse
import com.morainet.mcos.marketplace.SearchResponse
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.EnterprisePolicy
import com.morainet.mcos.security.EnterprisePolicySource
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.NullAuditLog
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.sdk.SecureStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * Shared JVM fixtures for the marketplace wiring tests: a real Ed25519
 * publisher key + artifact signature (mirrors `PluginInstallerTest`'s fixture
 * recipe), an in-memory index transport, and an [AppDeps] builder that uses
 * the production security posture (signed artifacts only, `disableSideload`).
 */
object TestMarketplace {

    const val PAYLOAD_TEXT = "hello-mcos-plugin"

    fun payload(): ByteArray = PAYLOAD_TEXT.encodeToByteArray()

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun pubKey(pair: KeyPair, keyId: String = "key_2026_01"): PublisherKey = PublisherKey(
        keyId = keyId,
        publisherId = "pub_1",
        publicKeyFingerprint = "ff".repeat(32),
        algorithm = "Ed25519",
        publicKeyEncoded = Base64.getEncoder().encodeToString(pair.public.encoded),
        createdAt = "2026-01-01T00:00:00Z",
    )

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun signatureBase64(pair: KeyPair, bytes: ByteArray): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(bytes)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** Wire metadata whose artifact reference matches [bytes] signed by [pair]. */
    fun metadata(
        pair: KeyPair,
        packageId: String = "example.hello",
        version: String = "1.0.0",
        bytes: ByteArray = payload(),
    ): PackageMetadata = PackageMetadata(
        packageId = packageId,
        name = "Hello Plugin",
        version = version,
        minRuntimeVersion = "0.1.0",
        publisherId = "pub_1",
        publisherName = "Acme",
        summary = "Reference sample plugin",
        permissionsPreview = emptyList(),
        commandsPreview = listOf("hello.world"),
        artifact = ArtifactRef(
            url = "https://cdn.example.com/$packageId-$version.mcos",
            sha256 = sha256Hex(bytes),
            signature = signatureBase64(pair, bytes),
            signingKeyId = "key_2026_01",
        ),
        publishedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        downloadCount = 42,
    )

    fun searchResponseJson(vararg metas: PackageMetadata): String =
        Json { encodeDefaults = true }.encodeToString(
            SearchResponse.serializer(),
            SearchResponse(results = metas.toList(), total = metas.size.toLong(), page = 1, pageSize = 20),
        )

    /** A single elevated-tier permission entry for update-diff fixtures. */
    fun permission(name: String, tier: String = "elevated"): MarketplacePermissionEntry =
        MarketplacePermissionEntry(type = "android", name = name, riskTier = tier)

    /**
     * A one-command workflow whose only string arg optionally carries a
     * `{{placeholder.<key>}}` token — decodable by `WorkflowJson.fromJson`.
     */
    fun commandWorkflow(text: String = "static"): JsonObject = buildJsonObject {
        put("type", "command")
        put("commandId", "sys.notify")
        put("args", buildJsonObject { put("text", text) })
    }

    /**
     * A one-command workflow carrying an event trigger envelope (05 §9.2) —
     * `specFromJson` keeps the trigger, and the wizard arms it on install.
     */
    fun triggeredWorkflow(
        commandId: String = "sys.notify",
        eventType: String = "wifi.connected",
    ): JsonObject = buildJsonObject {
        put("type", "command")
        put("commandId", commandId)
        put("args", buildJsonObject { put("text", "static") })
        put("trigger", buildJsonObject {
            put("type", "event")
            put("filter", buildJsonObject { put("type", eventType) })
        })
    }

    fun recipeEnvelope(
        recipeId: String = "hello.recipe",
        name: String = "Hello Recipe",
        version: String = "1.0.0",
        workflow: JsonObject = commandWorkflow(),
        placeholders: List<RecipePlaceholder> = emptyList(),
        requiredPlugins: List<String> = emptyList(),
    ): RecipeEnvelope = RecipeEnvelope(
        recipeId = recipeId,
        name = name,
        summary = "A reference recipe",
        version = version,
        workflow = workflow,
        placeholders = placeholders,
        requiredPlugins = requiredPlugins,
    )

    fun recipeSearchJson(vararg recipes: RecipeEnvelope): String =
        Json { encodeDefaults = true }.encodeToString(
            RecipeSearchResponse.serializer(),
            RecipeSearchResponse(results = recipes.toList(), total = recipes.size.toLong(), page = 1, pageSize = 20),
        )

    /** Wire body for `GET /v1/keys/revoked` (§6.3). */
    fun revokedKeysJson(vararg keys: PublisherKey): String =
        Json { encodeDefaults = true }.encodeToString(
            ListSerializer(PublisherKey.serializer()),
            keys.toList(),
        )

    /** Placeholder verifier with an unrelated key — blocklist fetch is not exercised by these tests. */
    private fun placeholderBlocklistVerifier(): BlocklistVerifier = BlocklistVerifier(pubKey(keyPair()))

    /**
     * In-memory marketplace index: search (`GET /v1/plugins?…`) serves
     * [searchBody]; artifact downloads serve [artifactBytes] regardless of URL.
     */
    class FakeIndexTransport(
        var searchBody: String = "",
        var searchStatus: Int = 200,
        var artifactBytes: ByteArray = payload(),
        var recipeBody: String = "",
        var recipeStatus: Int = 200,
        var revokedBody: String = "[]",
        var revokedStatus: Int = 200,
    ) : MarketplaceHttpTransport {
        val getJsonUrls = mutableListOf<String>()
        val getBytesUrls = mutableListOf<String>()

        override suspend fun getJson(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): MarketplaceHttpResponse {
            getJsonUrls += url
            return when {
                url.contains("/v1/plugins?") -> MarketplaceHttpResponse(searchStatus, searchBody)
                url.contains("/v1/recipes?") -> MarketplaceHttpResponse(recipeStatus, recipeBody)
                url.contains("/v1/keys/revoked") -> MarketplaceHttpResponse(revokedStatus, revokedBody)
                else -> MarketplaceHttpResponse(404, """{"error":"not_found"}""")
            }
        }

        override suspend fun getBytes(
            url: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long,
        ): ByteArray {
            getBytesUrls += url
            return artifactBytes
        }
    }

    /**
     * Full [AppDeps] with the production marketplace chain: real verifier +
     * trust-gated loader (the CompositionRoot trap-fix shape), real installer
     * against the fake transport, and `disableSideload = true`. The kernel is
     * pure-memory here (persistence has its own suite); tests that assert
     * install-time grants pass their own instance in.
     */
    fun deps(
        transport: MarketplaceHttpTransport = FakeIndexTransport(),
        secureStore: SecureStore,
        keyPair: KeyPair = keyPair(),
        permissionKernel: DefaultPermissionKernel = DefaultPermissionKernel(),
    ): AppDeps {
        val registry = CommandRegistry()
        val keyStore = InMemoryPublisherKeyStore().apply { put(pubKey(keyPair)) }
        val verifier = ArtifactVerifier(keyStore)
        val policy = EnterprisePolicySource.fixed(EnterprisePolicy(disableSideload = true))
        val loader = PluginLoader(
            trustGate = PluginTrustGate(verifier = verifier) { policy.current() },
            registry = registry,
        )
        val installProgress = MutableSharedFlow<InstallProgress>(extraBufferCapacity = 64)
        val installer = PluginInstaller(
            transport = transport,
            verifier = verifier,
            keyStore = keyStore,
            loader = loader,
            registry = registry,
            downloadDir = Files.createTempDirectory("mcos-android-market-test").toString(),
            onProgress = { installProgress.tryEmit(it) },
        )
        val eventBus = TypedEventBus()
        val runtime = McosRuntime.Builder()
            .withRegistry(registry)
            .withPluginLoader(loader)
            .withEventBus(eventBus)
            .build()
        return AppDeps(
            runtime = runtime,
            hostServices = StubHostServices(InMemoryFacade()),
            registry = registry,
            plugins = listOf(HelloPlugin()),
            resultBridge = ActivityResultBridge(),
            secureStore = secureStore,
            // Tests stay decoupled from the audit trail (demo-shell wiring
            // uses FileAuditLog; the fixture takes the named no-op).
            auditLog = NullAuditLog,
            permissionKernel = permissionKernel,
            eventBus = eventBus,
            marketplace = MarketplaceDeps(
                transport = transport,
                keyStore = keyStore,
                verifier = verifier,
                installer = installer,
                pluginFactory = MarketplacePluginFactory(),
                blocklistVerifier = placeholderBlocklistVerifier(),
                installProgress = installProgress,
                recipeInstaller = RecipeInstaller(),
            ),
        )
    }

    /** In-memory [SecureStore] with optional pre-seeded entries. */
    class FakeSecureStore(initial: Map<String, String> = emptyMap()) : SecureStore {
        private val entries = mutableMapOf<String, String>().apply { putAll(initial) }
        override suspend fun get(key: String): String? = entries[key]
        override suspend fun put(key: String, value: String) { entries[key] = value }
        override suspend fun remove(key: String) { entries.remove(key) }
        fun entriesForTest(): Map<String, String> = entries.toMap()
    }
}
