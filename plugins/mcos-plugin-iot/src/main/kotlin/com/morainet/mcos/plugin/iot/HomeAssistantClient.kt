package com.morainet.mcos.plugin.iot

import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.NetService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Minimal Home Assistant REST client over the host [NetService] — the
 * sanctioned egress channel, so every call passes the kernel's per-call
 * scope check ([08-security.md 12]). No vendor SDK: HA's local REST API is
 * the open, local-first integration surface named by [04-plugin-sdk.md 9].
 *
 * Auth: when [HomeAssistantConfig.tokenSecretKey] is set, requests carry
 * `Authorization: Bearer {{secret.<key>}}` — the executor's Stage-4 secret
 * pipeline resolves the reference per call ([08-security.md 9.2]), so the
 * raw long-lived token never enters plugin config, IR, or the audit trail
 * (same pattern as the MCP adapter). Unauthenticated local hubs simply
 * omit the key.
 */
internal class HomeAssistantClient(
    private val net: NetService,
    config: HomeAssistantConfig,
) {
    private val base = config.baseUrl.trimEnd('/')
    private val headers = config.tokenSecretKey
        ?.let { mapOf("Authorization" to "Bearer {{secret.$it}}") }
        ?: emptyMap()

    /** GET `{base}/api/states` — every entity with its current state. */
    suspend fun states(): HttpResponse = net.request(HttpRequest(url = "$base/api/states", headers = headers))

    /** POST a HA service call, e.g. `light/turn_on` with a JSON payload. */
    suspend fun service(domain: String, service: String, payload: JsonObject): HttpResponse =
        net.request(
            HttpRequest(
                method = "POST",
                url = "$base/api/services/$domain/$service",
                body = payload.toString().encodeToByteArray(),
                headers = headers,
            )
        )

    companion object {
        /**
         * Map a hub HTTP outcome to the command error model. 2xx passes;
         * 401/403 is an auth failure (non-retryable — retrying with the same
         * token cannot succeed); 404 means a wrong baseUrl (retryable: the
         * hub may come back); anything else is a transient hub failure.
         */
        fun ensureSuccess(response: HttpResponse, what: String) {
            when (response.status) {
                in 200..299 -> {}
                401, 403 -> throw McosException(
                    "PERMISSION_DENIED",
                    "Hub rejected the credentials for $what (HTTP ${response.status})",
                    retryable = false,
                )
                404 -> throw McosException(
                    "UNAVAILABLE",
                    "Hub endpoint not found for $what — check the configured baseUrl (HTTP 404)",
                    retryable = true,
                )
                else -> throw McosException(
                    "UNAVAILABLE",
                    "Hub error for $what (HTTP ${response.status})",
                    retryable = true,
                )
            }
        }

        /**
         * Parse a hub JSON object body; a malformed body fails closed as a
         * schema violation rather than being treated as empty success data.
         */
        fun parseObject(body: String): JsonObject =
            Json.parseToJsonElement(checkNotNull(body.takeIf { it.isNotEmpty() }) {
                "Hub returned an empty body"
            }).jsonObject

        /** Parse a hub JSON array body (e.g. `/api/states`). */
        fun parseArray(body: String): JsonArray =
            Json.parseToJsonElement(checkNotNull(body.takeIf { it.isNotEmpty() }) {
                "Hub returned an empty body"
            }).jsonArray
    }
}
