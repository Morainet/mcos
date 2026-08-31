package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.NamespacedSandbox
import com.morainet.mcos.runtime.core.executor.SecretResolvingNetService
import com.morainet.mcos.runtime.core.executor.StampScopedNetService
import com.morainet.mcos.security.AuthStampSigner
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.NetResponse
import com.morainet.mcos.sdk.SandboxEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The main-process side of the isolation boundary (08-security.md §8.3):
 * serves the plugin→main op vocabulary over the shared host facade, with the
 * *same* decorator stack the in-process Executor hands its plugins — so the
 * isolated and in-process boundaries cannot drift:
 *
 * - `net.request` → [StampScopedNetService] wrapping
 *   [SecretResolvingNetService] — §8.2 signature/TTL/scope re-verification
 *   *per call*, then `{{secret.*}}` resolution (§9.2);
 * - `sandbox.*` → [NamespacedSandbox] rooted at `<pluginId>/` (04 §6.1);
 * - `secureStore.*` / `clock.now` / `memory.*` → forwarded to the host.
 *
 * One server instance is bound to one plugin connection: [pluginId] and
 * [expectedUid] come from the server's own constructor (who it agreed to
 * serve), never from the wire. Check 1 of §8.2 — Binder caller identity —
 * runs FIRST via [BinderIdentityPolicy]; on mismatch the host facade is
 * never touched and the reply is a `PERMISSION_DENIED` envelope carrying
 * `details.reason = "plugin.identity_mismatch"`.
 *
 * [handle] never throws: every failure comes back as the shared error
 * envelope, preserving code/message/retryable/details of the original
 * [McosException] so the plugin-side proxy (and through it the Stage-10
 * audit) sees the true denial reason.
 *
 * @param host the real host facade (the runtime's [HostServices]).
 * @param signer the runtime's AuthStamp signer for per-call re-verification.
 * @param pluginId the plugin this connection was admitted to serve.
 * @param expectedUid the Linux UID the plugin process is expected to run
 *        under (same-app isolated process UID on Android).
 * @param nowMs injectable clock (tests).
 */
class IsolatedFacadeServer(
    private val host: HostServices,
    private val signer: AuthStampSigner,
    private val pluginId: String,
    private val expectedUid: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Serve one plugin→main call. [callingUid] is the transport-reported
     * caller identity (Binder `getCallingUid()`; tests pass it directly).
     */
    suspend fun handle(op: String, envelope: JsonObject, callingUid: Int): JsonObject {
        // §8.2 check 1 — identity, before anything else touches the host.
        if (!BinderIdentityPolicy.check(callingUid, expectedUid)) {
            return IsolationCodec.encodeError(
                code = McosErrorCode.PERMISSION_DENIED.name,
                message = "Calling uid $callingUid does not match the uid admitted for plugin '$pluginId'",
                retryable = false,
                details = buildJsonObject { put("reason", BinderIdentityPolicy.AUDIT_REASON) },
            )
        }
        val (args, stamp) = IsolationCodec.decodeCall(envelope)
        return try {
            dispatch(op, args, stamp)
        } catch (e: McosException) {
            // Preserve the denial verbatim — code and details.reason flow
            // back across the wire to the plugin and its audit trail.
            IsolationCodec.encodeError(e.code, e.message ?: "isolated call failed", e.retryable, e.details)
        } catch (e: Exception) {
            IsolationCodec.encodeError(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Isolated facade op '$op' failed: ${e.message ?: e.javaClass.simpleName}",
                retryable = false,
                details = buildJsonObject { put("reason", "facade_internal_error") },
            )
        }
    }

    private suspend fun dispatch(op: String, args: JsonObject, stamp: com.morainet.mcos.sdk.AuthStamp?): JsonObject =
        when (op) {
            IsolationOps.OP_NET_REQUEST -> serveNet(args, stamp)

            IsolationOps.OP_SECURE_GET -> buildJsonObject {
                put("value", host.secureStore.get(args.str("key") ?: ""))
            }

            IsolationOps.OP_SECURE_PUT -> {
                host.secureStore.put(args.str("key") ?: "", args.str("value") ?: "")
                JsonObject(emptyMap())
            }

            IsolationOps.OP_SECURE_REMOVE -> {
                host.secureStore.remove(args.str("key") ?: "")
                JsonObject(emptyMap())
            }

            IsolationOps.OP_SANDBOX_READ -> {
                val sandbox = sandboxOrUnavailable()
                val data = sandbox.read(args.str("path") ?: "")
                if (data != null) {
                    buildJsonObject { put("dataB64", Base64.getEncoder().encodeToString(data)) }
                } else {
                    JsonObject(emptyMap())
                }
            }

            IsolationOps.OP_SANDBOX_WRITE -> {
                val sandbox = sandboxOrUnavailable()
                sandbox.write(
                    path = args.str("path") ?: "",
                    data = Base64.getDecoder().decode(args.str("dataB64") ?: ""),
                    append = args["append"]?.jsonPrimitive?.contentOrNull == "true",
                )
                JsonObject(emptyMap())
            }

            IsolationOps.OP_SANDBOX_STAT -> {
                val entry = sandboxOrUnavailable().stat(args.str("path") ?: "")
                if (entry != null) buildJsonObject { put("entry", encodeEntry(entry)) } else JsonObject(emptyMap())
            }

            IsolationOps.OP_SANDBOX_DELETE -> buildJsonObject {
                put("deleted", sandboxOrUnavailable().delete(args.str("path") ?: ""))
            }

            IsolationOps.OP_SANDBOX_LIST -> buildJsonObject {
                val entries = sandboxOrUnavailable().list(args.str("dir") ?: "")
                put("entries", buildJsonArray { entries.forEach { add(encodeEntry(it)) } })
            }

            IsolationOps.OP_SANDBOX_TEMP -> buildJsonObject {
                put("name", sandboxOrUnavailable().tempFile(args.str("prefix") ?: "mcos", args.str("suffix") ?: ".tmp"))
            }

            IsolationOps.OP_CLOCK_NOW -> buildJsonObject { put("nowMs", host.clock.nowMs()) }

            IsolationOps.OP_MEMORY_GET -> {
                val value = host.memory.get(args.str("path") ?: "")
                if (value != null) buildJsonObject { put("value", value) } else JsonObject(emptyMap())
            }

            IsolationOps.OP_MEMORY_RESOLVE -> {
                val result = host.memory.resolveRef(
                    ref = args.str("ref") ?: "",
                    semanticType = args.str("semanticType"),
                )
                buildJsonObject {
                    put(
                        "resolved",
                        when (result) {
                            is com.morainet.mcos.sdk.ResolveResult.Resolved -> buildJsonObject {
                                put("kind", "resolved")
                                put("id", result.id)
                                put("confidence", result.confidence)
                            }
                            is com.morainet.mcos.sdk.ResolveResult.Ambiguous -> buildJsonObject {
                                put("kind", "ambiguous")
                                put("candidates", buildJsonArray {
                                    result.candidates.forEach { add(JsonPrimitive(it)) }
                                })
                            }
                            is com.morainet.mcos.sdk.ResolveResult.NotFound -> buildJsonObject {
                                put("kind", "notFound")
                                put("reason", result.reason)
                            }
                        },
                    )
                }
            }

            else -> IsolationCodec.encodeError(
                code = McosErrorCode.UNAVAILABLE.name,
                message = "Unknown isolation op '$op'",
                retryable = false,
                details = JsonObject(emptyMap()),
            )
        }

    /**
     * §8.2 checks 2-4 + §9.2 secret resolution, composed identically to the
     * in-process Executor's Stage-4 facade (ScopedFacade.kt) — the gate sits
     * outermost so scope is judged before any store read or egress.
     */
    private suspend fun serveNet(args: JsonObject, stamp: com.morainet.mcos.sdk.AuthStamp?): JsonObject {
        val gated = StampScopedNetService(
            SecretResolvingNetService(host.net, host.secureStore),
            stamp,
            signer,
            nowMs,
        )
        val response: NetResponse = gated.request(
            method = args.str("method") ?: "GET",
            url = args.str("url") ?: "",
            body = args.str("body"),
            headers = (args["headers"] as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: it.value.toString() }
                ?: emptyMap(),
        )
        return buildJsonObject {
            put("status", response.status)
            response.body?.let { put("body", it) }
            if (response.headers.isNotEmpty()) {
                put("headers", buildJsonObject { response.headers.forEach { (k, v) -> put(k, v) } })
            }
        }
    }

    /** Namespaced sandbox view for this connection's plugin, or an honest UNAVAILABLE envelope. */
    private fun sandboxOrUnavailable(): com.morainet.mcos.sdk.SandboxFileService =
        host.sandbox?.let { NamespacedSandbox(it, pluginId) }
            ?: throw McosException(
                code = McosErrorCode.UNAVAILABLE.name,
                message = "Host provides no sandbox storage (08 §8.3)",
            )

    private fun encodeEntry(entry: SandboxEntry): JsonObject = buildJsonObject {
        put("path", entry.path)
        put("isDir", entry.isDir)
        entry.size?.let { put("size", it) }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
