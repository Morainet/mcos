package com.morainet.mcos.plugin.mcp

import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.FileEntry
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetResponse
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.UiService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A recorded outbound request. */
data class RecordedRequest(
    val method: String,
    val url: String,
    val body: String?,
    val headers: Map<String, String>,
)

/**
 * A [NetService] driven by a caller-supplied handler. The handler may inspect
 * the JSON-RPC body and return a canned [NetResponse], or throw to simulate a
 * connection-level fault.
 */
class FakeNetService(
    private val handler: (RecordedRequest) -> NetResponse,
) : NetService {
    val requests = mutableListOf<RecordedRequest>()

    override suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): NetResponse {
        val req = RecordedRequest(method, url, body, headers)
        requests += req
        return handler(req)
    }

    /** The JSON-RPC `method` of the most recent request body. */
    fun lastRpcMethod(): String? =
        requests.lastOrNull()?.body
            ?.let { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content }
}

/** Build a JSON-RPC success response wrapping [resultJson]. */
fun rpcOk(resultJson: String): NetResponse =
    NetResponse(status = 200, body = """{"jsonrpc":"2.0","id":1,"result":$resultJson}""")

/** A minimal [HostServices] whose only live capability is [net]. */
class TestHostServices(override val net: NetService) : HostServices {
    override val files: FileService = object : FileService {
        override suspend fun list(uri: String, mimeType: String?) = emptyList<FileEntry>()
    }
    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = null
    }
    override val secureStore: SecureStore = object : SecureStore {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }
    override val clock: Clock = object : Clock {
        override fun nowMs(): Long = 0L
    }
    override val json: JsonService = object : JsonService {
        override fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
    }
    override val memory: MemoryFacade = object : MemoryFacade {
        override suspend fun get(path: String): JsonElement? = null
        override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult =
            ResolveResult.NotFound()
    }
}

/** Build an [ExecutionContext] for [commandId] with the given [args]. */
fun execContext(commandId: String, args: JsonObject, net: NetService): ExecutionContext =
    ExecutionContext(
        runId = "run-1",
        commandId = commandId,
        args = args,
        services = TestHostServices(net),
    )
