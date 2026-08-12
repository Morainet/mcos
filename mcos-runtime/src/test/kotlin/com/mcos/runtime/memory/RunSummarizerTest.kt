package com.mcos.runtime.memory

import com.mcos.runtime.api.ExecuteRequest
import com.mcos.runtime.api.ExecutionStatus
import com.mcos.runtime.api.McosRuntime
import com.mcos.runtime.api.Payload
import com.mcos.runtime.api.Source
import com.mcos.runtime.workflow.WorkflowStep
import com.mcos.sdk.Artifact
import com.mcos.sdk.CommandHandler
import com.mcos.sdk.CommandManifestEntry
import com.mcos.sdk.CommandResult
import com.mcos.sdk.ExecutionContext
import com.mcos.sdk.HostServices
import com.mcos.sdk.McosPlugin
import com.mcos.sdk.PluginManifest
import com.mcos.sdk.ProviderInfo
import com.mcos.sdk.SideEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSummarizerTest {

    private val permissions = com.mcos.runtime.permission.PermissionKernel()

    private fun newEpisodic(): EpisodicMemory = EpisodicMemory()

    private fun argsOf(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        buildJsonObject {
            pairs.forEach { (k, v) -> put(k, v) }
        }

    // ─── buildSummary ──────────────────────────────────────────────────

    @Test
    fun `S1-buildSummary keeps single-line DSL text truncated to 160 chars`() {
        val long = "a".repeat(300)
        val out = RunSummarizer.buildSummary("camera.capture\nquality=\"high\"\n$long", listOf("camera.capture"))
        assertEquals(160, out.length)
        // prefix "camera.capture quality=\"high\" " is 30 chars → 130 'a' remain.
        assertEquals("camera.capture quality=\"high\" ${"a".repeat(130)}", out)
    }

    @Test
    fun `S2-buildSummary falls back to command-id listing when text is blank`() {
        assertEquals(
            "mail.send, photo.compress",
            RunSummarizer.buildSummary("  ", listOf("mail.send", "photo.compress"))
        )
    }

    // ─── extractMemoryPaths ────────────────────────────────────────────

    @Test
    fun `S3-extractMemoryPaths finds known-namespace paths in args`() {
        val args = argsOf(
            "to" to "people.tom",
            "location" to "places.office",
            "device" to "devices.tv",
            "email" to "tom@example.com", // not a memory path
            "count" to "42",              // not a path
        )
        val paths = RunSummarizer.extractMemoryPaths(JsonObject(args))
        assertTrue(paths.contains("people.tom"))
        assertTrue(paths.contains("places.office"))
        assertTrue(paths.contains("devices.tv"))
        assertEquals(3, paths.size)
    }

    @Test
    fun `S4-extractMemoryPaths recurses into nested objects and arrays`() {
        val args = buildJsonObject {
            put(
                "recipients",
                JsonArray(
                    listOf(
                        JsonObject(mapOf("ref" to JsonPrimitive("people.tom"))),
                        JsonObject(mapOf("ref" to JsonPrimitive("people.jerry"))),
                    )
                )
            )
            put("prefs", JsonObject(mapOf("ns" to JsonPrimitive("preferences.theme.dark"))))
        }
        val paths = RunSummarizer.extractMemoryPaths(args)
        assertTrue(paths.contains("people.tom"))
        assertTrue(paths.contains("people.jerry"))
        assertTrue(paths.contains("preferences.theme.dark"))
        assertEquals(3, paths.size)
    }

    @Test
    fun `S5-extractMemoryPaths ignores single-segment and unknown-namespace values`() {
        val args = argsOf(
            "a" to "people",
            "b" to "unknown.ns",
            "c" to "user.name.first", // user namespace is allowed
        )
        val paths = RunSummarizer.extractMemoryPaths(JsonObject(args))
        assertTrue(paths.contains("user.name.first"))
        assertEquals(1, paths.size)
    }

    // ─── summarize ─────────────────────────────────────────────────────

    @Test
    fun `S6-summarize records success episode with ids entities and truncated summary`() {
        val episodic = newEpisodic()
        val summarizer = RunSummarizer(episodic)
        val args = listOf(argsOf("to" to "people.tom"))
        val now = System.currentTimeMillis()

        val rec = summarizer.summarize(
            runId = "run-1",
            summary = "Compressed 12 photos and emailed Tom",
            commandIds = listOf("photo.search", "mail.send"),
            argsByCommand = args,
            outcome = EpisodicOutcome.SUCCESS,
            timestamp = now,
        )

        assertEquals("run-1", rec.runId)
        assertEquals(now, rec.timestamp)
        assertEquals("Compressed 12 photos and emailed Tom", rec.summary)
        assertEquals(listOf("photo.search", "mail.send"), rec.commandIds)
        assertEquals(listOf("people.tom"), rec.entities)
        assertEquals(EpisodicOutcome.SUCCESS, rec.outcome)
        assertEquals(1, episodic.count())
        assertTrue(episodic.hasExecuted("mail.send"))
        assertTrue(!episodic.hasExecuted("camera.capture"))
    }

    @Test
    fun `S7-summarize records failed outcome`() {
        val episodic = newEpisodic()
        RunSummarizer(episodic).summarize(
            runId = "run-2",
            summary = "",
            commandIds = listOf("mail.send"),
            outcome = EpisodicOutcome.FAILED,
            timestamp = System.currentTimeMillis(),
        )
        assertEquals(1, episodic.count())
        assertEquals("FAILED", episodic.exportEpisodic()[0].jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S8-summarize deduplicates entities across commands`() {
        val episodic = newEpisodic()
        val args = listOf(
            argsOf("to" to "people.tom"),
            argsOf("cc" to "people.tom"),
        )
        val rec = RunSummarizer(episodic).summarize(
            runId = "run-3",
            summary = "x",
            commandIds = listOf("mail.send"),
            argsByCommand = args,
            outcome = EpisodicOutcome.SUCCESS,
            timestamp = 3_000L,
        )
        assertEquals(listOf("people.tom"), rec.entities)
    }

    // ─── McosRuntime integration (§9.4) ────────────────────────────────

    @Test
    fun `S9-runtime records an episode after a successful DSL run`() = runBlocking {
        val runtime = McosRuntime.Builder()
            .withPermissionKernel(permissions)
            .withEpisodicMemory(newEpisodic())
            .build()
        runtime.registry().register(echoPlugin("echo", listOf("mail.send")))

        val handle = runtime.execute(
            ExecuteRequest(source = Source.CHAT, payload = Payload.DslText("mail.send(to=\"people.tom\")"))
        )
        assertEquals(ExecutionStatus.RUNNING, handle.status)

        // Give the background coroutine time to finish.
        kotlinx.coroutines.delay(500)
        assertEquals(1, runtime.episodicMemory().count())

        val ep = runtime.episodicMemory().exportEpisodic()[0].jsonObject
        assertEquals("mail.send", ep["commandIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("people.tom", ep["entities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("SUCCESS", ep["outcome"]!!.jsonPrimitive.content)
        assertTrue(runtime.episodicMemory().hasExecuted("mail.send"))
    }

    @Test
    fun `S10-runtime records a FAILED episode when a command errors`() = runBlocking {
        val runtime = McosRuntime.Builder()
            .withPermissionKernel(permissions)
            .withEpisodicMemory(newEpisodic())
            .build()
        runtime.registry().register(echoPlugin("echo", listOf("mail.send"), fail = true))

        runtime.execute(
            ExecuteRequest(source = Source.CHAT, payload = Payload.DslText("mail.send()"))
        )
        kotlinx.coroutines.delay(500)
        assertEquals(1, runtime.episodicMemory().count())
        val ep = runtime.episodicMemory().exportEpisodic()[0].jsonObject
        assertEquals("FAILED", ep["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S11-runtime records a SUCCESS episode for a workflow run`() = runBlocking {
        val runtime = McosRuntime.Builder()
            .withPermissionKernel(permissions)
            .withEpisodicMemory(newEpisodic())
            .build()
        runtime.registry().register(echoPlugin("echo", listOf("photo.compress")))
        runtime.workflowStore().register(
            "wf-sum",
            WorkflowStep.Command("photo.compress", JsonObject(emptyMap()))
        )

        runtime.execute(
            ExecuteRequest(
                source = Source.CHAT,
                payload = Payload.WorkflowRef("wf-sum")
            )
        )
        kotlinx.coroutines.delay(500)
        assertEquals(1, runtime.episodicMemory().count())
        val ep = runtime.episodicMemory().exportEpisodic()[0].jsonObject
        assertEquals("photo.compress", ep["commandIds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("SUCCESS", ep["outcome"]!!.jsonPrimitive.content)
    }

    private fun echoPlugin(
        name: String,
        commandIds: List<String>,
        fail: Boolean = false,
    ): McosPlugin {
        val provider = ProviderInfo("TestOrg", "https://example.com")
        return object : McosPlugin {
            override val manifest: PluginManifest = PluginManifest(
                id = "test-plugin-$name",
                name = "Test Plugin $name",
                version = "1.0.0",
                minRuntimeVersion = "1.0",
                description = "Test plugin",
                provider = provider,
                entry = "com.mcos.plugin.test.TestPlugin",
                commands = commandIds.map {
                    CommandManifestEntry(
                        id = it,
                        version = "1.0",
                        title = it,
                        description = "Test command $it",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = JsonObject(emptyMap())
                    )
                }
            )
            override fun handlers(): Map<String, CommandHandler> = commandIds.associateWith { cid ->
                object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                        if (fail) return CommandResult.Err(code = "TEST_ERROR", message = "boom")
                        return CommandResult.Ok(
                            value = JsonObject(emptyMap()),
                            artifacts = listOf(Artifact("text", "echo:$cid", "text/plain"))
                        )
                    }
                }
            }
            override suspend fun onLoad(services: HostServices) {
                permissions.grant(manifest.id, "mcos:all")
            }
            override suspend fun onUnload() {}
        }
    }
}


