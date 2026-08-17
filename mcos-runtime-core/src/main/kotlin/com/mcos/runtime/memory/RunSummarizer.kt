package com.mcos.runtime.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Creates an [EpisodicRecord] when a run completes (07-memory.md §9.4:
 * "Workflow run completes → `episodes` index → Summarizer creates
 * [EpisodicRecord]"). Called by the runtime after `runCommands` /
 * `runWorkflow` settle into their terminal outcome.
 *
 * The summary is derived from the raw DSL text when available (truncated to
 * a single readable line), falling back to a command-id listing. Entities
 * are the memory paths referenced by the command arguments (e.g.
 * `people.tom`, `places.office`).
 */
class RunSummarizer(private val episodic: EpisodicMemory) {

    /**
     * Record the outcome of a completed run.
     *
     * @param runId        run identifier (correlates with the audit log).
     * @param summary      human-readable summary; blank falls back to the
     *                     command-id listing.
     * @param commandIds   commands executed by the run.
     * @param argsByCommand raw argument maps per command (entities extracted
     *                     from their values).
     * @param outcome      terminal outcome of the run.
     * @param timestamp    completion time (epoch millis).
     */
    fun summarize(
        runId: String,
        summary: String,
        commandIds: List<String>,
        argsByCommand: List<Map<String, JsonElement>> = emptyList(),
        outcome: EpisodicOutcome,
        timestamp: Long,
    ): EpisodicRecord {
        val entities = argsByCommand.flatMap { extractMemoryPaths(JsonObject(it)) }.distinct()
        return episodic.record(
            runId = runId,
            summary = buildSummary(summary, commandIds),
            commandIds = commandIds,
            entities = entities,
            outcome = outcome,
            timestamp = timestamp,
        )
    }

    companion object {
        /** Cap for the recorded summary text (single-line, ~160 chars). */
        private const val MAX_SUMMARY_LENGTH = 160

        /** Memory namespaces whose paths are tracked as entities (§8.0). */
        private val ENTITY_NAMESPACES =
            listOf("people", "places", "devices", "user", "preferences", "app", "tags")

        private val ENTITY_PATH_REGEX = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z0-9_\\-]+)+$")

        /**
         * Build the stored summary: raw DSL text normalized to a single
         * truncated line, or the command-id listing when blank.
         */
        fun buildSummary(raw: String?, commandIds: List<String>): String {
            val text = raw?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text.replace('\n', ' ').replace(Regex("\\s+"), " ")
                    .take(MAX_SUMMARY_LENGTH)
            }
            return commandIds.joinToString(", ").take(MAX_SUMMARY_LENGTH)
        }

        /**
         * Collect memory paths referenced by a JSON value (07-memory.md
         * §8.0 `entities`). Walks objects and arrays recursively; only
         * scalar strings shaped like `namespace.path` under a known memory
         * namespace count as entities (e.g. `people.tom`,
         * `places.office`).
         */
        fun extractMemoryPaths(value: JsonElement): Set<String> {
            val found = LinkedHashSet<String>()
            fun walk(el: JsonElement) {
                when (el) {
                    is JsonObject -> el.values.forEach { walk(it) }
                    is JsonArray -> el.forEach { walk(it) }
                    is JsonPrimitive -> {
                        val s = el.contentOrNull ?: return
                        val dot = s.indexOf('.')
                        if (dot > 0 && dot < s.length - 1) {
                            val ns = s.substring(0, dot)
                            if (ns in ENTITY_NAMESPACES && ENTITY_PATH_REGEX.matches(s)) {
                                found.add(s)
                            }
                        }
                    }
                }
            }
            walk(value)
            return found
        }
    }
}
