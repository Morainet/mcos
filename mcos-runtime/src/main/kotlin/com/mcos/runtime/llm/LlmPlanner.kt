package com.mcos.runtime.llm

import com.mcos.runtime.executor.Command
import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.ParseResult
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.parse.DslParser
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.CommandDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Natural-language planner that uses an LLM to translate user intent
 * into executable MCOS DSL commands.
 *
 * ## Pipeline
 *
 * ```
 * NL input -> buildSystemPrompt(+memory) -> LLM chat -> response DSL -> DslParser.parse() -> List<Command>
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val planner = LlmPlanner(OpenAiLlmProvider(config), registry, memoryStore)
 * val plan = planner.plan("take a photo and share it")
 * if (plan.isSuccess) {
 *     executor.executeSequence(plan.commands)
 * }
 * ```
 *
 * @param provider The primary LLM backend (e.g. [OpenAiLlmProvider]).
 * @param registry The [CommandRegistry] used to build the system prompt with
 *        available commands and their input schemas.
 * @param memory Optional [MemoryStore] for injecting user context (preferences,
 *        places, devices) into the system prompt.
 * @param parser The DSL parser; defaults to [DslParser].
 * @param fallbacks Additional [LlmProvider]s tried in order when the primary
 *        fails with a retryable error (§17 V1 multi-provider fallback chain,
 *        §18.1 "On-device fallback": on-device failure routes to cloud).
 */
class LlmPlanner(
    private val provider: LlmProvider,
    private val registry: CommandRegistry,
    private val memory: MemoryStore? = null,
    private val parser: DslParser = DslParser,
    private val fallbacks: List<LlmProvider> = emptyList(),
) {

    // ---- System prompt ---------------------------------------------------

    /**
     * Build the system prompt that tells the LLM which commands are available
     * and how to format the DSL output.
     */
    suspend fun buildSystemPrompt(): String {
        val commands = registry.allCommands()
        return buildString {
            appendLine("You are MCOS Agent -- a mobile command operating system assistant.")
            appendLine()
            appendLine("Your job: convert the user's natural language request into MCOS DSL commands.")
            appendLine()
            appendLine(buildCommandsSection(commands))
            appendLine()
            appendLine(buildMemorySection())
            appendLine("## DSL Format (v0.1)")
            appendLine()
            appendLine("Output valid MCOS DSL. Every command uses named parameters:")
            appendLine()
            appendLine("  command.id(param1=\"value\", param2=123)")
            appendLine()
            appendLine("Multiple commands execute in sequence (one per line):")
            appendLine()
            appendLine("  command.one(param=\"x\")")
            appendLine("  command.two(param=\"y\")")
            appendLine()
            appendLine("## Critical Rules")
            appendLine()
            appendLine("1. ONLY use commands listed above. Never invent new command IDs.")
            appendLine("2. ALL parameters must use named syntax (name=value).")
            appendLine("3. Output ONLY the DSL. No explanations, no markdown fences, no extra text.")
            appendLine("4. If a request is impossible with the available commands, output an empty response.")
            appendLine("5. Use correct parameter types: strings must be quoted, numbers/bools unquoted.")
        }
    }

    private fun buildCommandsSection(commands: List<CommandDescriptor>): String {
        if (commands.isEmpty()) return "## Available Commands\n\n(None registered yet)"

        val sb = StringBuilder()
        sb.appendLine("## Available Commands")

        for (cmd in commands) {
            sb.appendLine()
            sb.appendLine("### ${cmd.id}")
            if (cmd.description.isNotEmpty()) {
                sb.appendLine("  ${cmd.description}")
            }

            val params = cmd.inputSchema.jsonObject["properties"]?.jsonObject
            val required = cmd.inputSchema.jsonObject["required"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet() ?: emptySet()

            if (params != null && params.isNotEmpty()) {
                sb.appendLine("  Parameters:")
                for ((name, schema) in params) {
                    val type = schema.jsonObject["type"]?.jsonPrimitive?.content ?: "any"
                    val desc = schema.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                    val req = if (name in required) " [required]" else ""
                    val descStr = if (desc.isNotEmpty()) " -- $desc" else ""
                    sb.appendLine("    - $name ($type)$req$descStr")
                }
            }

            if (cmd.examples.isNotEmpty()) {
                sb.appendLine("  Examples:")
                for (ex in cmd.examples) {
                    sb.appendLine("    ${ex}")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Build a memory context section from the [MemoryStore].
     * Injects user facts (preferences, places, devices) so the LLM can
     * use them to disambiguate references like "导航回公司" → resolve "公司".
     */
    private suspend fun buildMemorySection(): String {
        val store = memory ?: return ""
        val paths = store.list()
        if (paths.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## Memory Context (User Facts)")
        sb.appendLine()
        sb.appendLine("The following facts are known about the user. Use them to disambiguate references.")
        sb.appendLine()

        for (path in paths.sorted()) {
            val value = store.get(path) ?: continue
            val displayValue = when (value) {
                is JsonObject -> if (value.isNotEmpty()) value.toString() else "{}"
                is JsonArray -> value.toString()
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
            sb.appendLine("- $path: $displayValue")
        }

        // Also include semantic tags
        val tags = store.tags()
        if (tags.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Semantic tags: ${tags.joinToString(", ")}")
        }

        return sb.toString()
    }

    // ---- Planning --------------------------------------------------------

    /**
     * Translate natural language into a plan of executable commands.
     *
     * Tries the primary [provider] first; on a retryable error, walks the
     * [fallbacks] chain in order (§17 V1). Non-retryable errors stop the
     * chain immediately.
     *
     * @param naturalLanguage The user's request, e.g. "take a photo and share it".
     * @return [LlmPlan] with parsed commands or error details.
     */
    suspend fun plan(naturalLanguage: String): LlmPlan {
        val systemPrompt = buildSystemPrompt()
        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", naturalLanguage)
        )

        val chain = listOf(provider) + fallbacks
        var lastErr: LlmResponse.Err? = null
        var attemptedIds = mutableListOf<String>()

        for (p in chain) {
            attemptedIds += p.id
            val response = p.chat(messages)
            when (response) {
                is LlmResponse.Ok -> return parseResponse(response.content, providerId = p.id)
                is LlmResponse.Err -> {
                    lastErr = response
                    if (!response.retryable) break // fatal: no point falling back
                }
            }
        }

        return LlmPlan(
            commands = emptyList(),
            rawDsl = "",
            thoughts = "LLM call failed: ${lastErr?.message ?: "no provider attempted"}" +
                " (tried: ${attemptedIds.joinToString(", ")})",
            error = lastErr,
            providerId = attemptedIds.lastOrNull()
        )
    }

    // ---- Response parsing ------------------------------------------------

    /**
     * Parse the LLM's text response into executable [Command]s.
     *
     * Handles common LLM output artifacts:
     * - Code fences (```mcos, ```dsl, ```)
     * - Leading/trailing whitespace
     * - Explanatory text (best-effort: tries to extract DSL block)
     */
    internal fun parseResponse(raw: String, providerId: String? = null): LlmPlan {
        var dsl = raw.trim()

        // Extract code block if LLM wrapped output in markdown fences
        val fenced = extractFencedBlock(dsl)
        if (fenced != null) {
            dsl = fenced
        } else {
            // If no fences, try to strip non-DSL lines (keep lines that look like commands)
            val commandLines = dsl.lines().filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                    (trimmed.contains("(") || trimmed.contains("="))
            }
            if (commandLines.isNotEmpty()) {
                dsl = commandLines.joinToString("\n").trim()
            }
        }

        // Empty or whitespace-only
        if (dsl.isBlank()) {
            return LlmPlan(
                commands = emptyList(),
                rawDsl = raw,
                thoughts = "LLM returned empty response -- request may be out of scope for available commands",
                error = null,
                providerId = providerId
            )
        }

        val result = parser.parse(dsl)
        return when (result) {
            is ParseResult.Ok -> {
                val commands = extractCommands(result.ir)
                if (commands.isEmpty()) {
                    LlmPlan(
                        commands = emptyList(),
                        rawDsl = raw,
                        thoughts = "Parsed DSL contains no executable commands",
                        error = null,
                        providerId = providerId
                    )
                } else {
                    LlmPlan(
                        commands = commands,
                        rawDsl = raw,
                        thoughts = "Parsed ${commands.size} command(s) from LLM output",
                        error = null,
                        providerId = providerId
                    )
                }
            }
            is ParseResult.Err -> LlmPlan(
                commands = emptyList(),
                rawDsl = raw,
                thoughts = null,
                error = LlmResponse.Err(
                    "LLM_PARSE_ERROR",
                    "Failed to parse LLM DSL output: ${result.message}",
                    true
                ),
                providerId = providerId
            )
        }
    }

    /**
     * Extract the content of a fenced code block (```mcos, ```dsl, or plain ```).
     */
    private fun extractFencedBlock(text: String): String? {
        val fenceRegex = Regex("```(?:mcos|dsl)?\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val match = fenceRegex.find(text)
        return match?.groupValues?.get(1)?.trim()
    }

    // ---- IR -> Command conversion ----------------------------------------

    /**
     * Convert parsed [ExecutionIr] into a flat list of [Command]s.
     */
    private fun extractCommands(ir: ExecutionIr): List<Command> {
        return when (ir) {
            is ExecutionIr.Invoke -> {
                val args = ir.invoke.args
                listOf(Command(ir.invoke.id, args))
            }
            is ExecutionIr.Sequence -> {
                ir.sequence.steps.map { step ->
                    Command(step.id, step.args)
                }
            }
            is ExecutionIr.Workflow -> emptyList() // Workflow IR not yet supported
        }
    }
}
