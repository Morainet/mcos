package com.morainet.mcos.android.demo.skills

import com.morainet.mcos.llm.Skill
import com.morainet.mcos.sdk.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One imported skill package in persisted form: the [skill] the planner renders
 * plus the user's [enabled] toggle. Disabled skills stay in the list (so a
 * toggle survives) but are excluded from [SkillStore.enabled].
 */
data class SkillRecord(val skill: Skill, val enabled: Boolean = true)

/**
 * Owns the imported [Skill] packages for the demo host (mirrors
 * [com.morainet.mcos.android.McpServerController]'s persistence style): a JSON
 * list under one SecureStore key, this instance the single writer. Skills are
 * prompt-level augmentation only — [SkillStore] hands the enabled set to
 * [com.morainet.mcos.llm.LlmPlanner]; it registers no commands.
 *
 * The controller is constructed per host attach and re-reads the store on
 * first use, so it stays a plain testable class with no Android types.
 */
class SkillStore(private val secureStore: SecureStore) {

    private var records: MutableList<SkillRecord> = mutableListOf()
    private var loaded = false

    /** All imported skills (enabled + disabled), in import order. */
    suspend fun list(): List<SkillRecord> {
        ensureLoaded()
        return records.toList()
    }

    /** The enabled skills, for injection into the planner prompt. */
    suspend fun enabled(): List<Skill> {
        ensureLoaded()
        return records.filter { it.enabled }.map { it.skill }
    }

    /**
     * Import a skill from raw text — either a Claude-style `SKILL.md` (YAML
     * frontmatter `name`/`description` + markdown body as instructions) or a
     * JSON object `{"name","description","instructions"}`. Returns the parsed
     * skill, or null when neither format yields a usable name+instructions.
     * A duplicate id (same slugified name) replaces the existing record,
     * preserving its enabled flag.
     */
    suspend fun import(text: String): SkillRecord? {
        ensureLoaded()
        val skill = SkillParser.parse(text) ?: return null
        val existingIdx = records.indexOfFirst { it.skill.id == skill.id }
        val record = if (existingIdx >= 0) {
            SkillRecord(skill, records[existingIdx].enabled).also { records[existingIdx] = it }
        } else {
            SkillRecord(skill).also { records += it }
        }
        persist()
        return record
    }

    /** Remove a skill by id. No-op for an unknown id. */
    suspend fun remove(id: String) {
        ensureLoaded()
        if (records.removeAll { it.skill.id == id }) persist()
    }

    /** Toggle a skill on or off. No-op for an unknown id. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureLoaded()
        val idx = records.indexOfFirst { it.skill.id == id }
        if (idx >= 0) {
            records[idx] = records[idx].copy(enabled = enabled)
            persist()
        }
    }

    /**
     * A stable version token for the enabled set — the demo view model folds it
     * into the agent-bridge cache key so a skill change rebuilds the bridge.
     */
    suspend fun enabledVersion(): String {
        ensureLoaded()
        return records.filter { it.enabled }.joinToString("|") { it.skill.id }.hashCode().toString()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        records = loadRecords().toMutableList()
    }

    private suspend fun loadRecords(): List<SkillRecord> {
        val raw = secureStore.get(SKILLS_KEY)?.decodeToString() ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SkillRecord(
                    skill = Skill(
                        id = id,
                        name = name,
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        instructions = o["instructions"]?.jsonPrimitive?.contentOrNull ?: "",
                    ),
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun persist() {
        val arr = buildJsonArray {
            records.forEach { r ->
                add(buildJsonObject {
                    put("id", r.skill.id)
                    put("name", r.skill.name)
                    put("description", r.skill.description)
                    put("instructions", r.skill.instructions)
                    put("enabled", r.enabled)
                })
            }
        }
        secureStore.put(SKILLS_KEY, arr.toString().encodeToByteArray())
    }

    companion object {
        /** SecureStore key holding the imported-skills JSON list. */
        const val SKILLS_KEY = "skills"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Parses imported skill text into a [Skill]. Handles two formats:
 *  - **JSON**: `{"name": …, "description": …, "instructions": …}`.
 *  - **SKILL.md**: a leading `---`-delimited YAML frontmatter block with
 *    `name:`/`description:` keys, followed by the markdown body (instructions).
 *  - Plain markdown with no frontmatter: the first `# Heading` (or first line)
 *    becomes the name, the rest the instructions.
 */
object SkillParser {

    fun parse(text: String): Skill? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val skill = if (trimmed.startsWith("{")) parseJson(trimmed) else parseMarkdown(trimmed)
        return skill?.takeIf { it.name.isNotBlank() && it.instructions.isNotBlank() }
    }

    private fun parseJson(text: String): Skill? = runCatching {
        val o = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        Skill(
            id = slug(name),
            name = name,
            description = o["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: "",
            instructions = (o["instructions"] ?: o["body"])?.jsonPrimitive?.contentOrNull?.trim() ?: "",
        )
    }.getOrNull()

    private fun parseMarkdown(text: String): Skill {
        var name = ""
        var description = ""
        var body = text

        if (text.startsWith("---")) {
            // Split off the frontmatter block: --- … --- then the body.
            val rest = text.removePrefix("---")
            val end = rest.indexOf("\n---")
            if (end >= 0) {
                val front = rest.substring(0, end)
                body = rest.substring(end + 4).trim()
                for (line in front.lines()) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim().trim('"', '\'')
                    when (key) {
                        "name" -> name = value
                        "description" -> description = value
                    }
                }
            }
        }

        if (name.isBlank()) {
            val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            name = firstLine.removePrefix("#").trim().ifBlank { "Imported Skill" }
        }
        return Skill(id = slug(name), name = name, description = description, instructions = body.trim())
    }

    private fun slug(name: String): String =
        name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .trim('-').replace(Regex("-+"), "-").ifBlank { "skill" }
}
