package com.morainet.mcos.security

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Unit tests for [SecretResolver] — `{{secret.<key>}}` template resolution
 * (08-security.md §9.2).
 */
class SecretResolverTest {

    @Test
    fun `resolves a single template`() = runBlocking {
        val out = SecretResolver.resolve("Bearer {{secret.token}}") { key ->
            if (key == "token") "s3cr3t" else null
        }
        assertEquals("Bearer s3cr3t", out)
    }

    @Test
    fun `unknown key leaves the template inert`() = runBlocking {
        val out = SecretResolver.resolve("{{secret.missing}}") { null }
        assertEquals("{{secret.missing}}", out)
    }

    @Test
    fun `resolves multiple distinct keys`() = runBlocking {
        val store = mapOf("token" to "t", "apiKey" to "k")
        val out = SecretResolver.resolve("{{secret.token}}|{{secret.apiKey}}") { store[it] }
        assertEquals("t|k", out)
    }

    @Test
    fun `tolerates whitespace inside the template`() = runBlocking {
        val out = SecretResolver.resolve("{{ secret.api_key }}") { "v" }
        assertEquals("v", out)
    }

    @Test
    fun `returns input unchanged when no template present`() {
        val input = "no secrets here"
        assertEquals(input, runBlocking { SecretResolver.resolve(input) { "x" } })
    }

    @Test
    fun `containsTemplate detects templates`() {
        assertTrue(SecretResolver.containsTemplate("{{secret.token}}"))
        assertFalse(SecretResolver.containsTemplate("plain text"))
    }

    @Test
    fun `referencedKeys returns keys in order of appearance`() {
        assertEquals(
            listOf("a", "b", "a"),
            SecretResolver.referencedKeys("{{secret.a}}/{{secret.b}}/{{secret.a}}")
        )
        assertTrue(SecretResolver.referencedKeys("plain").isEmpty())
    }

    @Test
    fun `non-secret braces are left untouched`() = runBlocking {
        val input = "{{ not a secret }} and {{secret.token}}"
        val out = SecretResolver.resolve(input) { "v" }
        assertEquals("{{ not a secret }} and v", out)
    }
}
