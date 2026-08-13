package com.mcos.runtime.llm

import com.mcos.runtime.executor.Command
import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.ParseResult
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.parse.DslParser
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.CommandDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * @param cloudFallbackEnabled Whether an ON_DEVICE provider may escalate to a
 *        CLOUD provider on failure (06 §13.2 "Allow cloud planner" opt-in).
 *        Defaults to `false` -- without the opt-in, an on-device failure
 *        surfaces as a refusal and no data leaves the device (privacy-first
 *        default, [08 §9](./08-security.md)).
 */
class LlmPlanner(
    private val provider: LlmProvider,
    private val registry: CommandRegistry,
    private val memory: MemoryStore? = null,
    private val parser: DslParser = DslParser,
    private val fallbacks: List<LlmProvider> = emptyList(),
    private val cloudFallbackEnabled: Boolean = false,
) {

    // ---- System prompt ---------------------------------------------------

    /**
     * Build the system prompt that tells the LLM which commands are available
     * and how to format the DSL output.
     */
    suspend fun buildSystemPrompt(mode: PlanMode = PlanMode.FREEFORM_JSON): String {
        if (mode == PlanMode.CONSTRAINED) {
            return buildConstrainedSystemPrompt()
        }
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

    /**
     * System prompt for CONSTRAINED mode (06 §3.2 V2): the model replies with a
     * single IR JSON object whose shape is enforced by the injected grammar
     * (built by [buildIrJsonSchema]); the schema text itself is appended by the
     * provider's constrained endpoint, so it is not duplicated here.
     */
    private suspend fun buildConstrainedSystemPrompt(): String {
        val commands = registry.allCommands()
        return buildString {
            appendLine("You are MCOS Agent -- a mobile command operating system assistant.")
            appendLine()
            appendLine("Your job: convert the user's natural language request into a single MCOS IR JSON object.")
            appendLine()
            appendLine(buildCommandsSection(commands))
            appendLine()
            appendLine(buildMemorySection())
            appendLine("## Output Format (CONSTRAINED)")
            appendLine()
            appendLine("Reply with exactly one JSON object conforming to the IR JSON Schema that follows your instructions:")
            appendLine()
            appendLine("  {\"type\":\"invoke\",\"command\":\"camera.capture\",\"args\":{\"flash\":\"off\"}}")
            appendLine("  {\"type\":\"sequence\",\"steps\":[{\"command\":\"hello.greet\",\"args\":{\"name\":\"MCOS\"}}]} ")
            appendLine("  {\"type\":\"clarify\",\"question\":\"Which camera -- front or back?\"}")
            appendLine("  {\"type\":\"refuse\",\"reason\":\"No command can perform that request.\"}")
            appendLine()
            appendLine("## Critical Rules")
            appendLine()
            appendLine("1. ONLY use commands listed above. Never invent new command IDs.")
            appendLine("2. `invoke` runs one command; `sequence` runs several in order; `clarify` asks a question; `refuse` declines.")
            appendLine("3. `args` keys must be parameter names from the command schema; values use correct JSON types (strings quoted).")
            appendLine("4. Output ONLY the JSON object. No explanations, no markdown fences, no extra text.")
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
     * Each provider is called through the highest-fidelity mode its
     * capabilities allow (06 §3.2): TOOL_CALL providers via
     * [PlanMode.NATIVE_TOOL_CALL] ([LlmProvider.toolCall]), everything else
     * via [PlanMode.FREEFORM_JSON] ([LlmProvider.chat] + DSL parsing).
     *
     * @param naturalLanguage The user's request, e.g. "take a photo and share it".
     * @return [LlmPlan] with parsed commands or error details.
     */
    suspend fun plan(naturalLanguage: String): LlmPlan {
        val tools = buildToolDescriptors()

        val chain = listOf(provider) + fallbacks
        var lastErr: LlmResponse.Err? = null
        var attemptedIds = mutableListOf<String>()
        var triedOnDevice = false

        for (p in chain) {
            // Privacy gate (06 §13.2): once an ON_DEVICE provider has been
            // attempted, escalation to a CLOUD provider requires the explicit
            // "Allow cloud planner" opt-in. Without it the failure surfaces as
            // a refusal and no data leaves the device.
            if (triedOnDevice && p.tier == ProviderTier.CLOUD && !cloudFallbackEnabled) {
                return LlmPlan(
                    commands = emptyList(),
                    rawDsl = "",
                    thoughts = "On-device provider failed and cloud fallback is disabled -- " +
                        "enable \"Allow cloud planner\" to escalate (privacy gate)",
                    error = LlmResponse.Err(
                        LlmErrorCode.CLOUD_FALLBACK_DISABLED,
                        "Cloud fallback is disabled by privacy policy; enable \"Allow cloud planner\" in settings",
                        false
                    ),
                    providerId = attemptedIds.lastOrNull()
                )
            }

            attemptedIds += p.id
            if (p.tier == ProviderTier.ON_DEVICE) triedOnDevice = true
            val mode = resolveMode(p)
            val messages = when (mode) {
                PlanMode.CONSTRAINED ->
                    listOf(
                        ChatMessage("system", buildSystemPrompt(mode)),
                        ChatMessage("user", naturalLanguage)
                    )
                else ->
                    listOf(
                        ChatMessage("system", buildSystemPrompt()),
                        ChatMessage("user", naturalLanguage)
                    )
            }
            when (mode) {
                PlanMode.NATIVE_TOOL_CALL -> {
                    val response = p.toolCall(messages, tools)
                    when (response) {
                        is ToolCallResponse.Ok -> {
                            if (response.toolCalls.isEmpty()) {
                                return LlmPlan(
                                    commands = emptyList(),
                                    rawDsl = "",
                                    thoughts = "Model returned no tool calls -- request may be out of scope for available commands",
                                    error = null,
                                    providerId = p.id,
                                    planMode = mode
                                )
                            }
                            val commands = response.toolCalls.map { Command(it.command, it.args) }
                            return LlmPlan(
                                commands = commands,
                                rawDsl = "",
                                thoughts = "Model proposed ${commands.size} tool call(s) (native tool calling)",
                                error = null,
                                providerId = p.id,
                                planMode = mode
                            )
                        }
                        is ToolCallResponse.Err -> {
                            lastErr = LlmResponse.Err(response.code, response.message, response.retryable)
                            if (!response.retryable) break // fatal: no point falling back
                        }
                    }
                }
                PlanMode.CONSTRAINED -> {
                    val response = p.constrainedChat(messages, buildIrJsonSchema())
                    when (response) {
                        is LlmResponse.Ok ->
                            return parseIrJson(response.content, providerId = p.id).copy(planMode = mode)
                        is LlmResponse.Err -> {
                            lastErr = response
                            if (!response.retryable) break // fatal: no point falling back
                        }
                    }
                }
                PlanMode.FREEFORM_JSON -> {
                    val response = p.chat(messages)
                    when (response) {
                        is LlmResponse.Ok ->
                            return parseResponse(response.content, providerId = p.id).copy(planMode = mode)
                        is LlmResponse.Err -> {
                            lastErr = response
                            if (!response.retryable) break // fatal: no point falling back
                        }
                    }
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

    // ---- Planning mode ---------------------------------------------------

    /**
     * Pick the highest-fidelity planning mode a provider's capabilities allow
     * (06 §3.2): NATIVE_TOOL_CALL > CONSTRAINED > FREEFORM_JSON.
     */
    private fun resolveMode(p: LlmProvider): PlanMode =
        when {
            Capability.TOOL_CALL in p.capabilities -> PlanMode.NATIVE_TOOL_CALL
            Capability.CONSTRAINED in p.capabilities -> PlanMode.CONSTRAINED
            else -> PlanMode.FREEFORM_JSON
        }

    /**
     * The MCOS IR JSON Schema (06 §3.2 / §9.0) injected as a decoding grammar
     * in CONSTRAINED mode. Providers with real grammar support (llama.cpp GBNF,
     * Outlines) constrain sampling with it; API providers receive it as a
     * structured-output hint / appended to the system message.
     */
    internal fun buildIrJsonSchema(): String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["type"],
          "properties": {
            "type": { "enum": ["invoke", "sequence", "clarify", "refuse"] },
            "command": { "type": "string", "description": "Command ID, e.g. camera.capture (required for invoke)" },
            "args": { "type": "object", "description": "Named arguments matching the command's parameter schema" },
            "steps": {
              "type": "array",
              "description": "Ordered commands for sequence",
              "items": {
                "type": "object",
                "required": ["command"],
                "properties": {
                  "command": { "type": "string" },
                  "args": { "type": "object" }
                }
              }
            },
            "question": { "type": "string", "description": "Clarifying question for clarify" },
            "reason": { "type": "string", "description": "Refusal reason for refuse" }
          }
        }
    """.trimIndent()

    /**
     * Project registry commands onto [ToolDescriptor]s for NATIVE_TOOL_CALL
     * (06 §3.0). Registry command examples (DSL strings) are best-effort
     * parsed into their argument objects; unparseable examples are skipped.
     */
    private fun buildToolDescriptors(): List<ToolDescriptor> =
        registry.allCommands().map { cmd ->
            ToolDescriptor(
                command = cmd.id,
                description = cmd.description,
                inputSchema = cmd.inputSchema,
                examples = cmd.examples.mapNotNull { dsl ->
                    when (val r = parser.parse(dsl)) {
                        is ParseResult.Ok -> when (val ir = r.ir) {
                            is ExecutionIr.Invoke -> ir.invoke.args
                            else -> null
                        }
                        is ParseResult.Err -> null
                    }
                }
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
        val fenceRegex = Regex("```(?:mcos|dsl|json)?\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val match = fenceRegex.find(text)
        return match?.groupValues?.get(1)?.trim()
    }

    /**
     * Parse the LLM's CONSTRAINED-mode reply -- a single IR JSON object -- into
     * executable [Command]s (06 §3.2 V2).
     *
     * Supports the four IR terminal types:
     * - `invoke`: one command with named args
     * - `sequence`: ordered list of `{command, args}` steps
     * - `clarify`: model needs more information (empty plan, no error)
     * - `refuse`: model declines (empty plan, no error)
     *
     * Malformed or unknown output produces a retryable `LLM_PARSE_ERROR` so the
     * fallback chain can try another provider.
     */
    internal fun parseIrJson(raw: String, providerId: String? = null): LlmPlan {
        val trimmed = raw.trim()
        val cleaned = extractFencedBlock(trimmed) ?: trimmed

        val json = try {
            Json.parseToJsonElement(cleaned)
        } catch (e: Exception) {
            return irParseError(raw, providerId, "not valid JSON: ${e.message?.take(160)}")
        }
        if (json !is JsonObject) {
            return irParseError(raw, providerId, "expected a JSON object, got ${json::class.simpleName}")
        }

        val type = json["type"]?.jsonPrimitive?.content
        return when (type) {
            "invoke" -> {
                val command = json["command"]?.jsonPrimitive?.content
                if (command.isNullOrBlank()) {
                    irParseError(raw, providerId, "invoke requires a non-empty 'command'")
                } else {
                    val args = json["args"] as? JsonObject ?: JsonObject(emptyMap())
                    LlmPlan(
                        commands = listOf(Command(command, args)),
                        rawDsl = raw,
                        thoughts = "IR invoke: $command",
                        error = null,
                        providerId = providerId
                    )
                }
            }
            "sequence" -> {
                val steps = json["steps"] as? JsonArray ?: buildJsonArray { }
                val commands = steps.mapNotNull { step ->
                    val obj = step as? JsonObject ?: return@mapNotNull null
                    val command = obj["command"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val args = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
                    Command(command, args)
                }
                if (steps.isEmpty()) {
                    irParseError(raw, providerId, "sequence requires a non-empty 'steps' array")
                } else if (commands.isEmpty()) {
                    irParseError(raw, providerId, "sequence steps must be objects with a 'command'")
                } else {
                    LlmPlan(
                        commands = commands,
                        rawDsl = raw,
                        thoughts = "IR sequence: ${commands.size} step(s)",
                        error = null,
                        providerId = providerId
                    )
                }
            }
            "clarify" -> LlmPlan(
                commands = emptyList(),
                rawDsl = raw,
                thoughts = "IR clarify: ${json["question"]?.jsonPrimitive?.content ?: "no question"}",
                error = null,
                providerId = providerId
            )
            "refuse" -> LlmPlan(
                commands = emptyList(),
                rawDsl = raw,
                thoughts = "IR refuse: ${json["reason"]?.jsonPrimitive?.content ?: "no reason"}",
                error = null,
                providerId = providerId
            )
            else -> irParseError(raw, providerId, "unknown IR type: ${type ?: "<missing>"}")
        }
    }

    private fun irParseError(raw: String, providerId: String?, detail: String): LlmPlan =
        LlmPlan(
            commands = emptyList(),
            rawDsl = raw,
            thoughts = null,
            error = LlmResponse.Err(
                "LLM_PARSE_ERROR",
                "Failed to parse CONSTRAINED IR JSON output: $detail",
                true
            ),
            providerId = providerId
        )

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
