package com.morainet.mcos.marketplace

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Recipe placeholder binding ([05-workflow.md §14.1]).
 *
 * @param key placeholder key referenced in the recipe workflow IR.
 * @param fromMemory memory-command identifier used to seed the value when available.
 * @param label human-readable label for the install wizard.
 * @param default default value when the user leaves the field empty.
 * @param required whether the wizard must reject an empty value.
 */
@Serializable
data class RecipePlaceholder(
    val key: String,
    val fromMemory: String? = null,
    val label: String? = null,
    val default: String? = null,
    val required: Boolean = false,
)

/**
 * Recipe trigger preview ([05-workflow.md §14.1]).
 *
 * @param type trigger command type (e.g. `voice_command`).
 * @param inputs command-specific trigger inputs (e.g. activation phrases).
 */
@Serializable
data class RecipeTriggerPreview(
    val type: String,
    val inputs: List<String> = emptyList(),
)

/**
 * Marketplace signature over a recipe envelope ([05-workflow.md §14.1]
 * constraint 3, [09-marketplace.md §8.3] step 5).
 *
 * @param signingKeyId id of the marketplace key ([09-marketplace.md §6.0]).
 * @param algorithm "Ed25519" (preferred) or "RSA-PSS-4096" (legacy).
 * @param signedAt ISO-8601 signature time.
 * @param signature base64 signature over the envelope's canonical payload
 *   (all fields except `signature`, serialized the way the client parses it).
 */
@Serializable
data class RecipeEnvelopeSignature(
    val signingKeyId: String,
    val algorithm: String,
    val signedAt: String,
    val signature: String,
)

/**
 * Signed recipe envelope served by the marketplace ([05-workflow.md §14.1],
 * [09-marketplace.md §8]). The marketplace signs the envelope at publish time
 * and the Runtime verifies the signature before compiling (05 §14.1
 * constraint 3); [RecipeSignatureVerifier] implements the fail-closed check.
 *
 * @param workflow raw workflow IR object ([05-workflow.md §14.2]).
 * @param requiredPlugins plugin dependencies as `pluginId@semverRange` specs
 *                        ([09-marketplace.md §7.4]); bare `pluginId` means `*`.
 * @param signature marketplace signature; null when the envelope is unsigned.
 */
@Serializable
data class RecipeEnvelope(
    val recipeId: String,
    val name: String,
    val summary: String? = null,
    val version: String,
    val workflow: JsonObject,
    val placeholders: List<RecipePlaceholder> = emptyList(),
    val requiredPlugins: List<String> = emptyList(),
    val triggerPreview: RecipeTriggerPreview? = null,
    val signature: RecipeEnvelopeSignature? = null,
) {
    /**
     * Canonical payload the marketplace signs and the Runtime verifies
     * (all fields except `signature`, serialized the way the client parses
     * it). Deterministic for a given envelope; verification does not depend on
     * transport-level whitespace or field ordering.
     */
    fun canonicalPayload(): ByteArray {
        val canonical = Json { encodeDefaults = true; explicitNulls = false }
        return canonical.encodeToString(copy(signature = null)).encodeToByteArray()
    }
}

/**
 * Paginated recipe search response ([09-marketplace.md §8.2]).
 */
@Serializable
data class RecipeSearchResponse(
    val results: List<RecipeEnvelope>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val cacheTtlSeconds: Long = 86_400,
)
