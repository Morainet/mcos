package com.mcos.runtime.llm

/**
 * Configuration for an LLM backend.
 *
 * @param apiKey API key for the provider (e.g. OpenAI API key).
 * @param model Model name, e.g. "gpt-4o-mini", "gpt-4o".
 * @param endpoint Chat completions endpoint URL.
 * @param maxTokens Maximum tokens in the response.
 * @param temperature Sampling temperature (0.0 = deterministic).
 * @param systemPromptExtra Extra instructions appended to the system prompt.
 * @param connectTimeoutMs HTTP connect timeout in milliseconds.
 * @param requestTimeoutMs HTTP request timeout in milliseconds.
 */
data class LlmConfig(
    val apiKey: String,
    val model: String = "gpt-4o-mini",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val maxTokens: Int = 1024,
    val temperature: Double = 0.0,
    val systemPromptExtra: String = "",
    val connectTimeoutMs: Long = 15_000,
    val requestTimeoutMs: Long = 60_000,
)
