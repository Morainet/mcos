package com.morainet.mcos.marketplace

import kotlinx.serialization.Serializable
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
 * Signed recipe envelope served by the marketplace ([05-workflow.md §14.1],
 * [09-marketplace.md §8]). Signature verification happens before compile and
 * is out of scope for the index client.
 *
 * @param workflow raw workflow IR object ([05-workflow.md §14.2]).
 * @param requiredPlugins plugin dependencies as `pluginId@semverRange` specs
 *                        ([09-marketplace.md §7.4]); bare `pluginId` means `*`.
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
)

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
