package com.mcos.runtime.marketplace

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Unit tests for [RecipeEnvelope] / [RecipeSearchResponse] wire format
 * ([05-workflow.md §14.1], [09-marketplace.md §8]).
 */
class RecipeEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `R1-full envelope decodes with nested structures`() {
        val body = """
            {
              "recipeId": "com.example.photo.compress",
              "name": "Photo Compressor",
              "summary": "Compress photos by voice",
              "version": "1.2.0",
              "workflow": {
                "nodes": [
                  {"id": "n1", "type": "command_call", "command": "photo.compress"}
                ]
              },
              "placeholders": [
                {"key": "quality", "label": "Quality", "default": "high", "required": true}
              ],
              "requiredPlugins": ["com.example.photo@^1.0.0", "mcos.plugin.system"],
              "triggerPreview": {"type": "voice_command", "inputs": ["compress photos"]}
            }
        """.trimIndent()

        val envelope = json.decodeFromString<RecipeEnvelope>(body)

        assertEquals("com.example.photo.compress", envelope.recipeId)
        assertEquals("Compress photos by voice", envelope.summary)
        assertEquals("1.2.0", envelope.version)
        assertEquals("n1", envelope.workflow["nodes"]?.jsonArray?.first()?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("quality", envelope.placeholders.single().key)
        assertEquals("high", envelope.placeholders.single().default)
        assertTrue(envelope.placeholders.single().required)
        assertEquals(listOf("com.example.photo@^1.0.0", "mcos.plugin.system"), envelope.requiredPlugins)
        assertEquals("voice_command", envelope.triggerPreview?.type)
        assertEquals(listOf("compress photos"), envelope.triggerPreview?.inputs)
    }

    @Test
    fun `R2-envelope round-trips through serialization`() {
        val envelope = RecipeEnvelope(
            recipeId = "com.example.photo.compress",
            name = "Photo Compressor",
            version = "1.0.0",
            workflow = Json.parseToJsonElement("""{"nodes":[]}""").jsonObject,
            placeholders = listOf(RecipePlaceholder(key = "target", fromMemory = "mymem", required = true)),
            requiredPlugins = listOf("com.example.photo@^1.0.0"),
            triggerPreview = RecipeTriggerPreview(type = "voice_command"),
        )

        val decoded = json.decodeFromString<RecipeEnvelope>(json.encodeToString(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `R3-minimal envelope uses defaults`() {
        val body = """{"recipeId":"r1","name":"Minimal","version":"0.1.0","workflow":{}}"""

        val envelope = json.decodeFromString<RecipeEnvelope>(body)

        assertEquals("r1", envelope.recipeId)
        assertNull(envelope.summary)
        assertTrue(envelope.placeholders.isEmpty())
        assertTrue(envelope.requiredPlugins.isEmpty())
        assertNull(envelope.triggerPreview)
        assertEquals(0, envelope.workflow.size)
    }

    @Test
    fun `R4-search response decodes pagination`() {
        val body = """
            {"results":[],"total":42,"page":2,"pageSize":10,"cacheTtlSeconds":86400}
        """.trimIndent()

        val response = json.decodeFromString<RecipeSearchResponse>(body)

        assertEquals(42, response.total)
        assertEquals(2, response.page)
        assertEquals(10, response.pageSize)
        assertEquals(86_400, response.cacheTtlSeconds)
    }
}
