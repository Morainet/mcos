package com.morainet.mcos.android.demo.settings

/**
 * A selectable LLM vendor preset. Every entry speaks the OpenAI Chat
 * Completions wire format, so a single [com.morainet.mcos.llm.OpenAiLlmProvider]
 * — registered under [id] — serves all of them; only the [endpoint] and
 * [models] differ. The vendor's API key and chosen model are persisted
 * per-vendor by [com.morainet.mcos.android.demo.shell.McosViewModel] (SecureStore keys `llm_vendor_<id>_*`).
 *
 * @param id Stable registry/persistence id (also the [com.morainet.mcos.llm.LlmProvider.id]).
 * @param name Human label shown in the settings list.
 * @param endpoint Default chat-completions endpoint (editable in the UI).
 * @param models Suggested model names; the first is the default selection.
 * @param keyHint Placeholder shown in the API-key field.
 * @param keyOptional True when the endpoint needs no key (e.g. a local Ollama).
 * @param custom True for the free-form "Custom" entry — endpoint and model are
 *   blank and both are required before it can be probed or used.
 */
data class LlmVendor(
    val id: String,
    val name: String,
    val endpoint: String,
    val models: List<String>,
    val keyHint: String,
    val keyOptional: Boolean = false,
    val custom: Boolean = false,
) {
    /** The default model for a fresh selection (first suggestion, or blank). */
    val defaultModel: String get() = models.firstOrNull() ?: ""
}

/**
 * Built-in vendor catalog. All are OpenAI-compatible chat-completions
 * endpoints; switching between them only changes the endpoint/model/key that
 * [com.morainet.mcos.android.demo.shell.McosViewModel] feeds into `LlmConfig`.
 */
object LlmVendors {

    val openai = LlmVendor(
        id = "openai",
        name = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
        keyHint = "sk-…",
    )

    val deepseek = LlmVendor(
        id = "deepseek",
        name = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1/chat/completions",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        keyHint = "sk-…",
    )

    val qwen = LlmVendor(
        id = "qwen",
        name = "通义千问 Qwen",
        endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        models = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
        keyHint = "sk-…",
    )

    val moonshot = LlmVendor(
        id = "moonshot",
        name = "Kimi (Moonshot)",
        endpoint = "https://api.moonshot.cn/v1/chat/completions",
        models = listOf("moonshot-v1-8k", "kimi-k2-0905-preview"),
        keyHint = "sk-…",
    )

    val zhipu = LlmVendor(
        id = "zhipu",
        name = "智谱 GLM",
        endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        models = listOf("glm-4-flash", "glm-4-plus"),
        keyHint = "…api key",
    )

    val gemini = LlmVendor(
        id = "gemini",
        name = "Gemini (OpenAI-compat)",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        models = listOf("gemini-2.0-flash", "gemini-1.5-flash"),
        keyHint = "AIza…",
    )

    val ollama = LlmVendor(
        id = "ollama",
        name = "Ollama (本地)",
        // 10.0.2.2 is the host loopback as seen from the Android emulator.
        endpoint = "http://10.0.2.2:11434/v1/chat/completions",
        models = listOf("llama3.2", "qwen2.5"),
        keyHint = "(通常无需 key)",
        keyOptional = true,
    )

    val custom = LlmVendor(
        id = "custom",
        name = "自定义 Custom",
        endpoint = "",
        models = emptyList(),
        keyHint = "api key",
        custom = true,
    )

    /** Presets in display order; "Custom" is pinned last. */
    val all: List<LlmVendor> = listOf(openai, deepseek, qwen, moonshot, zhipu, gemini, ollama, custom)

    /** Default selection when nothing is persisted yet. */
    const val DEFAULT_ID: String = "openai"

    fun byId(id: String): LlmVendor? = all.firstOrNull { it.id == id }
}
