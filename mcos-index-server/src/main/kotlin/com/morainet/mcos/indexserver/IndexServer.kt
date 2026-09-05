package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.Blocklist
import com.morainet.mcos.marketplace.BlocklistEntry
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.marketplace.SearchResponse
import com.morainet.mcos.marketplace.review.CiReviewReport
import com.morainet.mcos.security.PublisherKey
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Public index server HTTP surface (12-index-server.md §5).
 *
 * Zero third-party runtime dependencies: `com.sun.net.httpserver` + kotlinx
 * serialization (already on the classpath via the shared modules).
 */
class IndexServer(
    dataDir: Path,
    adminToken: String,
    operatorPrivatePem: Path?,
    operatorPublicPem: Path?,
    private val port: Int = 8877,
    private val bindHost: String = "127.0.0.1",
    avDenylistFile: Path? = null,
    avScannerCommand: String? = null,
) {
    private val registry: IndexRegistry = IndexRegistry.open(dataDir)
    private val artifactDir: Path = dataDir.resolve("artifacts")
    private val operatorKey = operatorPrivatePem?.let { OperatorKeys.load(operatorPrivatePem, operatorPublicPem) }
    private val services = IndexServices(
        registry = registry,
        adminTokenDigest = tokenDigest(adminToken),
        operatorKey = operatorKey,
        avDenylistFile = avDenylistFile,
        artifactDir = artifactDir,
        avScannerCommand = avScannerCommand,
    )

    private val router = HttpRouter()
    private var server: HttpServer? = null

    fun start(): Int {
        val http = HttpServer.create(InetSocketAddress(bindHost, port), 0)
        http.executor = Executors.newFixedThreadPool(8)
        registerRoutes()
        http.createContext("/") { exchange -> dispatch(exchange) }
        http.start()
        server = http
        return http.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    fun port(): Int = server?.address?.port ?: 0

    // ── Router ───────────────────────────────────────────────────────────────

    private fun registerRoutes() {
        // Read side (public).
        router.get("/v1/plugins") { exchange, _ -> handleSearch(exchange) }
        router.get("/v1/plugins/by-command/{commandId}") { exchange, params -> handleByCommand(exchange, params["commandId"]!!) }
        router.get("/v1/plugins/{packageId}/versions/{version}") { exchange, params ->
            sendMetadataOr404(exchange, services.packageVersion(params["packageId"]!!, params["version"]!!))
        }
        router.get("/v1/plugins/{packageId}/versions") { exchange, params ->
            sendJson(exchange, 200, encode(services.packageVersions(params["packageId"]!!)))
        }
        router.get("/v1/plugins/{packageId}/artifact") { exchange, params -> handleArtifact(exchange, params["packageId"]!!) }
        router.get("/v1/plugins/{packageId}") { exchange, params ->
            sendMetadataOr404(exchange, services.packageLatest(params["packageId"]!!))
        }
        router.get("/v1/recipes") { exchange, _ ->
            sendJson(exchange, 200, """{"results":[],"total":0,"page":1,"pageSize":10}""")
        }
        router.get("/v1/recipes/{recipeId}") { exchange, _ -> sendNotFound(exchange, "recipe") }
        router.get("/v1/blocklist") { exchange, _ -> handleBlocklist(exchange) }
        router.get("/v1/keys/revoked") { exchange, _ -> handleRevokedKeys(exchange) }
        router.get("/v1/publishers/{id}") { exchange, params -> handlePublisherProfile(exchange, params["id"]!!) }
        router.get("/v1/artifacts/{file}") { exchange, params -> handleArtifactFile(exchange, params["file"]!!) }

        // Client write side.
        router.post("/v1/reports") { exchange, _ -> handleReport(exchange) }
        router.post("/v1/telemetry/install") { exchange, _ -> handleTelemetry(exchange) }

        // Publisher write side.
        router.post("/v1/publishers/{id}/keys") { exchange, params -> handleRegisterKey(exchange, params["id"]!!) }
        router.delete("/v1/publishers/{id}/keys/{keyId}") { exchange, params ->
            handleRotateKey(exchange, params["id"]!!, params["keyId"]!!)
        }
        router.post("/v1/publishers/{id}/plugins") { exchange, params -> handleSubmit(exchange, params["id"]!!) }
        router.post("/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}/publish") { exchange, params ->
            handlePublisherPublish(exchange, params["id"]!!, params["submissionId"]!!)
        }
        router.get("/v1/publishers/{id}/plugins/{packageId}/submissions/{submissionId}") { exchange, params ->
            handleSubmissionStatus(exchange, params["id"]!!, params["submissionId"]!!)
        }

        // Management side (operator).
        router.get("/v1/admin/submissions") { exchange, _ -> handleAdminSubmissions(exchange) }
        router.get("/v1/admin/registry") { exchange, _ -> handleAdminRegistry(exchange) }
        router.post("/v1/admin/publishers") { exchange, _ -> handleCreatePublisher(exchange) }
        router.post("/v1/admin/plugins/{packageId}/submissions/{submissionId}/approve") { exchange, params ->
            handleDecision(exchange, params, approve = true)
        }
        router.post("/v1/admin/plugins/{packageId}/submissions/{submissionId}/reject") { exchange, params ->
            handleDecision(exchange, params, approve = false)
        }
        router.post("/v1/admin/plugins/{packageId}/publish") { exchange, params ->
            handleOperatorPublish(exchange, params["packageId"]!!)
        }
        router.post("/v1/admin/plugins/{packageId}/unlist") { exchange, params ->
            handleAdminStateChange(exchange, "unlist", params["packageId"]!!)
        }
        router.post("/v1/admin/plugins/{packageId}/revoke") { exchange, params ->
            handleAdminStateChange(exchange, "revoke", params["packageId"]!!)
        }
        router.post("/v1/admin/blocklist/entries") { exchange, _ -> handleBlocklistAdd(exchange) }
        router.delete("/v1/admin/blocklist/entries") { exchange, _ -> handleBlocklistRemove(exchange) }
        router.post("/v1/admin/keys/{keyId}/emergency-revoke") { exchange, params ->
            handleEmergencyRevoke(exchange, params["keyId"]!!)
        }
    }

    private fun dispatch(exchange: com.sun.net.httpserver.HttpExchange) {
        try {
            exchange.responseHeaders.set("X-MCOS-INDEX", "1")
            if (!router.dispatch(exchange)) {
                sendNotFound(exchange, "endpoint")
            }
        } catch (e: ApiException) {
            exchange.sendError(e.status, e.code, e.message)
        } catch (e: Exception) {
            System.err.println("index-server handler error: ${e.message}")
            e.printStackTrace(System.err)
            try {
                exchange.sendError(500, "INTERNAL", "server error: ${e.message}")
            } catch (_: Exception) {
                // response already committed
            }
        } finally {
            exchange.close()
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleSearch(exchange: com.sun.net.httpserver.HttpExchange) {
        val q = exchange.queryParams()
        val page = q["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = (q["pageSize"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val sort = q["sort"]?.takeIf { it in setOf("relevance", "safety", "popularity", "newest") } ?: "relevance"
        val minRuntime = q["minRuntimeVersion"]
        val result = services.search(q["query"], q["category"], sort, page, pageSize)
        val filtered = if (minRuntime == null) result else {
            val target = runCatching { com.morainet.mcos.runtime.core.registry.SemanticVersion.parse(minRuntime) }.getOrNull()
            if (target == null) result
            else {
                val kept = result.results.filter { meta ->
                    val v = runCatching { com.morainet.mcos.runtime.core.registry.SemanticVersion.parse(meta.minRuntimeVersion) }.getOrNull()
                    v != null && v <= target
                }
                SearchResponse(kept, kept.size.toLong(), page, pageSize)
            }
        }
        sendJson(exchange, 200, encode(filtered))
    }

    private fun handleByCommand(exchange: com.sun.net.httpserver.HttpExchange, commandId: String) {
        sendJson(exchange, 200, encode(services.byCommand(commandId)))
    }

    private fun sendMetadataOr404(exchange: com.sun.net.httpserver.HttpExchange, metadata: PackageMetadata?) {
        if (metadata == null) sendNotFound(exchange, "package")
        else sendJson(exchange, 200, encode(metadata))
    }

    private fun handleArtifact(exchange: com.sun.net.httpserver.HttpExchange, packageId: String) {
        val metadata = services.packageLatest(packageId)
            ?: return sendNotFound(exchange, "package")
        exchange.responseHeaders.set("Location", metadata.artifact.url)
        exchange.send(302, "")
    }

    private fun handleArtifactFile(exchange: com.sun.net.httpserver.HttpExchange, fileName: String) {
        val path = services.artifactFile(fileName) ?: return sendNotFound(exchange, "artifact")
        exchange.sendBytes(200, Files.readAllBytes(path), "application/octet-stream")
    }

    private fun handleBlocklist(exchange: com.sun.net.httpserver.HttpExchange) {
        val document = services.blocklistDocument()
        exchange.responseHeaders.set("Cache-Control", "public, max-age=3600")
        sendJson(exchange, 200, encode(document))
    }

    private fun handleRevokedKeys(exchange: com.sun.net.httpserver.HttpExchange) {
        sendJson(exchange, 200, encode(services.revokedKeys()))
    }

    private fun handlePublisherProfile(exchange: com.sun.net.httpserver.HttpExchange, id: String) {
        val profile = services.publisherProfile(id) ?: return sendNotFound(exchange, "publisher")
        sendJson(exchange, 200, encode(profile))
    }

    private fun handleReport(exchange: com.sun.net.httpserver.HttpExchange) {
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val obj = tryParse(body) ?: throw ApiException(400, "SCHEMA_VIOLATION", "invalid JSON body")
        val packageId = obj["packageId"]?.let { it as JsonPrimitive }?.content
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "packageId required")
        val version = obj["version"]?.let { it as JsonPrimitive }?.content ?: "*"
        val reason = obj["reason"]?.let { it as JsonPrimitive }?.content ?: "other"
        val description = obj["description"]?.let { it as JsonPrimitive }?.content
        services.recordReport(packageId, version, reason, description)
        val reportId = "rpt_${sha256Hex("$packageId$version${nowIso()}".toByteArray()).take(16)}"
        sendJson(exchange, 201, """{"reportId":"$reportId"}""")
    }

    private fun handleTelemetry(exchange: com.sun.net.httpserver.HttpExchange) {
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val obj = tryParse(body) ?: throw ApiException(400, "SCHEMA_VIOLATION", "invalid JSON body")
        val packageId = obj["packageId"]?.let { it as JsonPrimitive }?.content
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "packageId required")
        val version = obj["version"]?.let { it as JsonPrimitive }?.content ?: ""
        val event = obj["event"]?.let { it as JsonPrimitive }?.content ?: "install"
        val clientId = obj["anonymizedClientId"]?.let { it as JsonPrimitive }?.content ?: ""
        val timestamp = obj["timestamp"]?.let { it as JsonPrimitive }?.content ?: nowIso()
        services.recordInstall(packageId, version, event, clientId, timestamp)
        exchange.send(204, "")
    }

    private fun handleRegisterKey(exchange: com.sun.net.httpserver.HttpExchange, publisherId: String) {
        val snapshot = servicesCurrent().publisher(publisherId) ?: throw ApiException(404, "NOT_FOUND", "unknown publisher")
        services.authorizePublisher(exchange.bearerToken(), publisherId)
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val key = try {
            IndexJson.api.decodeFromString(PublisherKey.serializer(), body)
        } catch (e: Exception) {
            throw ApiException(400, "SCHEMA_VIOLATION", "PublisherKey JSON invalid: ${e.message}")
        }
        if (key.keyId.isBlank() || key.publicKeyEncoded.isBlank()) {
            throw ApiException(400, "SCHEMA_VIOLATION", "keyId and publicKeyEncoded are required")
        }
        val fingerprint = runCatching { sha256Hex(base64Decode(key.publicKeyEncoded)) }.getOrNull()
        if (fingerprint == null || !constantTimeEquals(fingerprint, key.publicKeyFingerprint)) {
            throw ApiException(400, "SCHEMA_VIOLATION", "publicKeyFingerprint does not match publicKeyEncoded")
        }
        services.registerKey(snapshot, key.copy(status = com.morainet.mcos.security.KeyStatus.ACTIVE))
        exchange.send(201, "")
    }

    private fun handleRotateKey(exchange: com.sun.net.httpserver.HttpExchange, publisherId: String, keyId: String) {
        val snapshot = servicesCurrent().publisher(publisherId) ?: throw ApiException(404, "NOT_FOUND", "unknown publisher")
        services.authorizePublisher(exchange.bearerToken(), publisherId)
        services.rotateKey(snapshot, keyId)
        exchange.send(200, "")
    }

    private fun handleSubmit(exchange: com.sun.net.httpserver.HttpExchange, publisherId: String) {
        val snapshot = servicesCurrent().publisher(publisherId) ?: throw ApiException(404, "NOT_FOUND", "unknown publisher")
        services.authorizePublisher(exchange.bearerToken(), publisherId)
        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        val parts = Multipart.parse(exchange.readBody(), contentType)
        val artifact = parts["artifact"]?.value
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "multipart part 'artifact' missing")
        val metadata = parts["metadata"]?.text()
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "multipart part 'metadata' missing")
        val signature = parts["signature"]?.text()
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "multipart part 'signature' missing")
        val response = services.submit(snapshot, artifact, metadata, signature)
        val json = IndexJson.api.encodeToString(SubmitResponse.serializer(), response)
        sendJson(exchange, 200, json)
    }

    private fun handlePublisherPublish(exchange: com.sun.net.httpserver.HttpExchange, publisherId: String, submissionId: String) {
        services.authorizePublisher(exchange.bearerToken(), publisherId)
        val submission = servicesCurrent().document.submissions.firstOrNull { it.submissionId == submissionId }
            ?: throw ApiException(404, "NOT_FOUND", "unknown submission")
        if (submission.publisherId != publisherId) {
            throw ApiException(403, "PERMISSION_DENIED", "submission belongs to another publisher")
        }
        services.publishSubmission(submissionId)
        exchange.send(200, "")
    }

    private fun handleSubmissionStatus(exchange: com.sun.net.httpserver.HttpExchange, publisherId: String, submissionId: String) {
        services.authorizePublisher(exchange.bearerToken(), publisherId)
        val snapshot = servicesCurrent()
        val submission = snapshot.document.submissions.firstOrNull { it.submissionId == submissionId }
            ?: throw ApiException(404, "NOT_FOUND", "unknown submission")
        if (submission.publisherId != publisherId) {
            throw ApiException(403, "PERMISSION_DENIED", "submission belongs to another publisher")
        }
        val view = SubmissionView(
            submissionId = submission.submissionId,
            packageId = submission.packageId,
            publisherId = submission.publisherId,
            state = submission.state.name,
            version = submission.version,
            submittedAt = submission.submittedAt,
            updatedAt = submission.updatedAt,
            reviewReport = submission.reviewReport,
        )
        sendJson(exchange, 200, encode(view))
    }

    private fun handleAdminSubmissions(exchange: com.sun.net.httpserver.HttpExchange) {
        services.authorizeAdmin(exchange.bearerToken())
        val state = exchange.queryParams()["state"]
        val list = services.submissionsQueue(state)
        val views = list.map {
            SubmissionView(
                submissionId = it.submissionId,
                packageId = it.packageId,
                publisherId = it.publisherId,
                state = it.state.name,
                version = it.version,
                submittedAt = it.submittedAt,
                updatedAt = it.updatedAt,
                reviewReport = it.reviewReport,
            )
        }
        sendJson(exchange, 200, encode(views))
    }

    private fun handleAdminRegistry(exchange: com.sun.net.httpserver.HttpExchange) {
        services.authorizeAdmin(exchange.bearerToken())
        val body = IndexJson.document.encodeToString(
            RegistryDocument.serializer(),
            services.registryView(),
        )
        exchange.send(200, body)
    }

    private fun handleCreatePublisher(exchange: com.sun.net.httpserver.HttpExchange) {
        services.authorizeAdmin(exchange.bearerToken())
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val obj = tryParse(body) ?: throw ApiException(400, "SCHEMA_VIOLATION", "invalid JSON body")
        val id = obj["id"]?.let { it as JsonPrimitive }?.content
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "id required")
        val name = obj["name"]?.let { it as JsonPrimitive }?.content ?: id
        val token = services.createPublisher(id, name)
        val json = buildJsonObject {
            put("publisherId", id)
            put("token", token)
            put("note", "store this token; only its SHA-256 digest is kept")
        }
        sendJson(exchange, 201, IndexJson.api.encodeToString(JsonElement.serializer(), json))
    }

    private fun handleDecision(exchange: com.sun.net.httpserver.HttpExchange, params: Map<String, String>, approve: Boolean) {
        services.authorizeAdmin(exchange.bearerToken())
        val submissionId = params["submissionId"]!!
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val note = tryParse(body)?.get("note")?.let { it as JsonPrimitive }?.content
        if (approve) services.approveSubmission(submissionId, "ops", note)
        else services.rejectSubmission(submissionId, "ops", note)
        exchange.send(200, "")
    }

    private fun handleOperatorPublish(exchange: com.sun.net.httpserver.HttpExchange, packageId: String) {
        services.authorizeAdmin(exchange.bearerToken())
        services.operatorPublish(packageId, "ops")
        exchange.send(200, "")
    }

    private fun handleAdminStateChange(exchange: com.sun.net.httpserver.HttpExchange, action: String, packageId: String) {
        services.authorizeAdmin(exchange.bearerToken())
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val reason = tryParse(body)?.get("reason")?.let { it as JsonPrimitive }?.content ?: "POLICY_VIOLATION"
        when (action) {
            "unlist" -> services.unlist(packageId, "ops")
            "revoke" -> services.revoke(packageId, "ops", reason)
        }
        exchange.send(200, "")
    }

    private fun handleBlocklistAdd(exchange: com.sun.net.httpserver.HttpExchange) {
        services.authorizeAdmin(exchange.bearerToken())
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val obj = tryParse(body) ?: throw ApiException(400, "SCHEMA_VIOLATION", "invalid JSON body")
        val packageId = obj["packageId"]?.let { it as JsonPrimitive }?.content
            ?: throw ApiException(400, "SCHEMA_VIOLATION", "packageId required")
        val reason = obj["reason"]?.let { it as JsonPrimitive }?.content ?: "POLICY_VIOLATION"
        val versionRange = obj["versionRange"]?.let { it as JsonPrimitive }?.content ?: "*"
        val detailUrl = obj["detailUrl"]?.let { it as JsonPrimitive }?.content
        val expiresAt = obj["expiresAt"]?.let { it as JsonPrimitive }?.content
        val entry = BlocklistEntry(
            packageId = packageId,
            versionRange = versionRange,
            reason = runCatching { com.morainet.mcos.marketplace.BlocklistReason.valueOf(reason) }
                .getOrElse { throw ApiException(400, "SCHEMA_VIOLATION", "unknown blocklist reason '$reason'") },
            detailUrl = detailUrl,
            blockedAt = nowIso(),
            expiresAt = expiresAt,
        )
        services.addBlocklistEntry(entry)
        exchange.send(200, "")
    }

    private fun handleBlocklistRemove(exchange: com.sun.net.httpserver.HttpExchange) {
        services.authorizeAdmin(exchange.bearerToken())
        val q = exchange.queryParams()
        val packageId = q["packageId"] ?: throw ApiException(400, "SCHEMA_VIOLATION", "packageId required")
        val versionRange = q["versionRange"] ?: "*"
        services.removeBlocklistEntry(packageId, versionRange)
        exchange.send(200, "")
    }

    private fun handleEmergencyRevoke(exchange: com.sun.net.httpserver.HttpExchange, keyId: String) {
        services.authorizeAdmin(exchange.bearerToken())
        val body = exchange.readBody().toString(Charsets.UTF_8)
        val reason = tryParse(body)?.get("reason")?.let { it as JsonPrimitive }?.content ?: "emergency"
        services.emergencyRevokeKey(keyId, reason)
        exchange.send(200, "")
    }

    // ── Serialization plumbing ───────────────────────────────────────────────

    private fun servicesCurrent() = registry.snapshot()

    private fun tryParse(body: String): JsonObject? = runCatching {
        IndexJson.api.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun encode(value: Any): String = when (value) {
        is SearchResponse -> IndexJson.api.encodeToString(SearchResponse.serializer(), value)
        is PackageMetadata -> IndexJson.api.encodeToString(PackageMetadata.serializer(), value)
        is Blocklist -> IndexJson.api.encodeToString(Blocklist.serializer(), value)
        is PublisherKey -> IndexJson.api.encodeToString(PublisherKey.serializer(), value)
        is List<*> -> encodeList(value)
        is JsonProfile -> IndexJson.api.encodeToString(JsonProfile.serializer(), value)
        is SubmissionView -> IndexJson.api.encodeToString(SubmissionView.serializer(), value)
        is JsonElement -> IndexJson.api.encodeToString(JsonElement.serializer(), value)
        else -> throw IllegalArgumentException("unsupported response type ${value::class}")
    }

    private fun encodeList(value: List<*>): String {
        val elements = buildJsonArray {
            for (item in value) {
                when (item) {
                    is PackageMetadata -> add(IndexJson.api.encodeToJsonElement(PackageMetadata.serializer(), item))
                    is PublisherKey -> add(IndexJson.api.encodeToJsonElement(PublisherKey.serializer(), item))
                    is SubmissionView -> add(IndexJson.api.encodeToJsonElement(SubmissionView.serializer(), item))
                    else -> throw IllegalArgumentException("unsupported list item ${item?.javaClass}")
                }
            }
        }
        return IndexJson.api.encodeToString(JsonElement.serializer(), elements)
    }

    private fun sendJson(exchange: com.sun.net.httpserver.HttpExchange, status: Int, json: String) {
        exchange.send(status, json)
    }

    private fun sendNotFound(exchange: com.sun.net.httpserver.HttpExchange, what: String) {
        exchange.sendError(404, "NOT_FOUND", "no such $what")
    }
}

@Serializable
data class SubmissionView(
    val submissionId: String,
    val packageId: String,
    val publisherId: String,
    val state: String,
    val version: String,
    val submittedAt: String,
    val updatedAt: String,
    val reviewReport: CiReviewReport,
)
