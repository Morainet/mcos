package com.mcos.runtime.marketplace

import com.mcos.runtime.security.Blocklist as SecurityBlocklist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Marketplace-facing permission entry ([09-marketplace.md §4.0]).
 *
 * Distinct from the SDK's [com.mcos.sdk.PermissionEntry] (which carries a
 * runtime `reason`): marketplace entries carry the CI-computed `riskTier`
 * and the publisher-provided `justification`.
 *
 * `type` + `name` rebuild the permission scope string: `android:CAMERA` or
 * `mcos:network.openapi.tuya.com`.
 */
@Serializable
data class MarketplacePermissionEntry(
    val type: String,
    val name: String,
    val riskTier: String,
    val justification: String? = null,
)

/**
 * Download reference for a plugin artifact ([09-marketplace.md §4.0]).
 *
 * @param url HTTPS CDN download URL.
 * @param sha256 hex-encoded SHA-256 of the artifact bytes.
 * @param signature base64-encoded publisher signature.
 * @param signingKeyId which publisher key signed this artifact.
 */
@Serializable
data class ArtifactRef(
    val url: String,
    val sha256: String,
    val signature: String,
    val signingKeyId: String,
    val sizeBytes: Long = 0,
)

/**
 * Index-facing package metadata ([09-marketplace.md §4.0]).
 *
 * This is the metadata the marketplace API serves and the client renders;
 * it is distinct from the internal plugin manifest ([04-plugin-sdk.md §4]).
 *
 * Timestamps are ISO-8601 strings to keep the runtime free of a
 * kotlinx-datetime dependency (same convention as [com.mcos.runtime.security.PublisherKey]).
 */
@Serializable
data class PackageMetadata(
    val packageId: String,
    val name: String,
    val version: String,
    val minRuntimeVersion: String,
    val publisherId: String,
    val publisherName: String,
    val categories: List<String> = emptyList(),
    val summary: String,
    val description: String? = null,
    val permissionsPreview: List<MarketplacePermissionEntry> = emptyList(),
    val commandsPreview: List<String> = emptyList(),
    val artifact: ArtifactRef,
    val privacyPolicyUrl: String? = null,
    val homepage: String? = null,
    val publishedAt: String,
    val updatedAt: String,
    val downloadCount: Long = 0,
    val safetyScore: Float = 0f,
)

/**
 * Paginated search response ([09-marketplace.md §11.1]).
 */
@Serializable
data class SearchResponse(
    val results: List<PackageMetadata>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val cacheTtlSeconds: Long = 86_400,
)

/**
 * Search ordering ([09-marketplace.md §11.1]).
 */
enum class SearchSort {
    relevance,
    safety,
    popularity,
    newest,
}

/**
 * Reason a package was blocked ([09-marketplace.md §14.0]).
 */
@Serializable
enum class BlocklistReason {
    /** Confirmed malware in the artifact. */
    MALWARE,

    /** Publisher signing key compromised (§6.3); packages need re-signing. */
    SIGNATURE_KEY_COMPROMISED,

    /** Review-policy violation (e.g. hidden destructive behavior). */
    POLICY_VIOLATION,

    /** Publisher account terminated. */
    PUBLISHER_BANNED,

    /** Exploitable bug, pending fix. */
    SECURITY_VULNERABILITY,

    /** DMCA or similar legal takedown. */
    LEGAL_TAKEDOWN,
}

/**
 * One entry of the marketplace blocklist ([09-marketplace.md §14.0]).
 *
 * @param versionRange SemVer range affected, or `"*"` for all versions.
 * @param blockedAt ISO-8601 timestamp.
 * @param expiresAt ISO-8601 timestamp; null = permanent.
 */
@Serializable
data class BlocklistEntry(
    val packageId: String,
    val versionRange: String,
    val reason: BlocklistReason,
    val detailUrl: String? = null,
    val blockedAt: String,
    val expiresAt: String? = null,
)

/**
 * Signed blocklist document ([09-marketplace.md §14.0]).
 *
 * @param version document version (incrementing).
 * @param issuedAt ISO-8601 timestamp.
 * @param signature signature over the document, base64.
 */
@Serializable
data class Blocklist(
    val entries: List<BlocklistEntry> = emptyList(),
    val version: String,
    val issuedAt: String,
    val signature: String? = null,
) {
    /** Does any entry cover the given (packageId, version)? */
    fun isBlocklisted(packageId: String, version: String): Boolean =
        entries.any { entry ->
            entry.packageId == packageId && VersionRange(entry.versionRange).matches(version)
        }
}

/**
 * Bridge to the security-layer predicate used by
 * [com.mcos.runtime.security.ArtifactVerifier] / [com.mcos.runtime.security.PluginTrustGate].
 */
fun Blocklist.asSecurityBlocklist(): SecurityBlocklist =
    SecurityBlocklist { packageId, version ->
        packageId != null && version != null && isBlocklisted(packageId, version)
    }

/**
 * Reason for a user report ([09-marketplace.md §14.1]).
 *
 * `wireValue` matches the enum sent over the wire (the marketplace uses
 * human-readable values with spaces).
 */
enum class ReportReason(val wireValue: String) {
    Malware("malware"),
    PrivacyViolation("privacy violation"),
    Broken("broken"),
    AbusiveBehavior("abusive behavior"),
    Other("other"),
}

/**
 * Body of `POST /v1/reports` ([09-marketplace.md §14.1]).
 *
 * @param anonymizedInfo optional device context (crash logs, plugin version)
 *                       included only with the user's consent.
 */
@Serializable
data class PluginReportRequest(
    val packageId: String,
    val version: String,
    val reason: String,
    val description: String? = null,
    val anonymizedInfo: JsonObject? = null,
)

/**
 * Acknowledgement with the user's tracking ID ([09-marketplace.md §14.1]).
 */
@Serializable
data class ReportAck(
    val reportId: String,
)

/**
 * Body of `POST /v1/telemetry/install` ([09-marketplace.md §11.3]).
 *
 * Opt-in only: the client sends this when the user enabled
 * "Help improve the marketplace". `anonymizedClientId` is a non-reversible
 * SHA-256 hash of the device-bound id.
 */
@Serializable
data class InstallTelemetryEvent(
    val packageId: String,
    val version: String,
    val event: String,
    val anonymizedClientId: String,
    val timestamp: String,
)
