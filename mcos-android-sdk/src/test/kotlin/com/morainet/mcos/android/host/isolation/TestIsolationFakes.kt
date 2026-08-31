package com.morainet.mcos.android.host.isolation

import com.morainet.mcos.sdk.Clock
import com.morainet.mcos.sdk.FileEntry
import com.morainet.mcos.sdk.FileService
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.HttpRequest
import com.morainet.mcos.sdk.HttpResponse
import com.morainet.mcos.sdk.JsonService
import com.morainet.mcos.sdk.MemoryFacade
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.ResolveResult
import com.morainet.mcos.sdk.SandboxEntry
import com.morainet.mcos.sdk.SandboxFileService
import com.morainet.mcos.sdk.SecureStore
import com.morainet.mcos.sdk.UiService
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * In-memory host pieces shared by the isolation slice-3a suites: a flat
 * sandbox (paths as map keys, so namespacing is visible), a capturing
 * NetService, a map SecureStore, and a canned MemoryFacade.
 */
class FakeFlatSandbox : SandboxFileService {
    val files = LinkedHashMap<String, ByteArray>()

    override suspend fun read(path: String): ByteArray? = files[path]

    override suspend fun write(path: String, data: ByteArray, append: Boolean) {
        val existing = if (append) files[path] ?: ByteArray(0) else ByteArray(0)
        files[path] = existing + data
    }

    override suspend fun stat(path: String): SandboxEntry? =
        files[path]?.let { SandboxEntry(path = path, isDir = false, size = it.size.toLong()) }

    override suspend fun delete(path: String): Boolean = files.remove(path) != null

    override suspend fun list(dir: String): List<SandboxEntry> =
        files.keys.filter { it.startsWith("$dir/") }.map { SandboxEntry(it, isDir = false, size = null) }

    override suspend fun tempFile(prefix: String, suffix: String): String {
        var i = 0
        var name = "$prefix$i$suffix"
        while (files.containsKey(name)) name = "$prefix${++i}$suffix"
        files[name] = ByteArray(0)
        return name
    }
}

class CapturingNetService(
    var response: HttpResponse = HttpResponse(200, body = "net-ok".encodeToByteArray()),
) : NetService {
    var lastMethod: String? = null
        private set
    var lastUrl: String? = null
        private set
    var lastBody: String? = null
        private set
    /** Raw request bytes as received — binary-passthrough assertions need
     *  the exact payload, not a lossy text view. */
    var lastRawBody: ByteArray? = null
        private set
    var lastHeaders: Map<String, String> = emptyMap()
        private set
    var calls = 0
        private set

    override suspend fun request(req: HttpRequest): HttpResponse {
        calls++
        lastMethod = req.method
        lastUrl = req.url
        lastBody = req.body?.decodeToString()
        lastRawBody = req.body
        lastHeaders = req.headers
        return response
    }
}

class MapSecureStore : SecureStore {
    val values = LinkedHashMap<String, ByteArray>()
    override suspend fun get(key: String): ByteArray? = values[key]
    override suspend fun put(key: String, value: ByteArray) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun keys(): Set<String> = values.keys.toSet()
}

class CannedMemory(
    private val facts: Map<String, JsonElement> = emptyMap(),
    private val resolver: (String) -> ResolveResult = { ResolveResult.NotFound("ref_unresolvable") },
) : MemoryFacade {
    override suspend fun get(path: String): JsonElement? = facts[path]
    override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult = resolver(ref)
}

/** The minimal [HostServices] the facade server touches; everything else throws. */
class FakeHostServices(
    override val net: NetService = CapturingNetService(),
    override val secureStore: SecureStore = MapSecureStore(),
    override val sandbox: SandboxFileService? = FakeFlatSandbox(),
    override val memory: MemoryFacade = CannedMemory(),
    private val now: () -> Long = { 1_700_000_000_000L },
) : HostServices {
    override val files: FileService = object : FileService {
        override suspend fun list(uri: String, mimeType: String?): List<FileEntry> =
            throw IllegalStateException("files not served in isolation tests")
    }
    override val ui: UiService = object : UiService {
        override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? =
            throw IllegalStateException("ui not served in isolation tests")
    }
    override val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(this@FakeHostServices.now())
        override fun monotonicMs(): Long = this@FakeHostServices.now()
    }
    override val json: JsonService = object : JsonService {
        override fun parse(jsonStr: String): JsonElement = Json.parseToJsonElement(jsonStr)
    }
}
