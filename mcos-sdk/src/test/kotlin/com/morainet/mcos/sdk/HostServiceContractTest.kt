package com.morainet.mcos.sdk

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the §6 value types (04-plugin-sdk.md 6.2/6.5) delivered
 * by the item-46 full-signature alignment: byte-valued request/response
 * bodies with content-based equality, and the Clock default-method
 * derivation that keeps epoch-ms call sites compiling unchanged.
 */
class HostServiceContractTest {

    // ── HttpRequest (04 §6.2) ──────────────────────────────────────────

    @Test
    fun `HC1-HttpRequest defaults match the spec sketch`() {
        val req = HttpRequest(url = "https://api.example.com/v1")
        assertEquals("GET", req.method)
        assertEquals(emptyMap(), req.headers)
        assertEquals(null, req.body)
        assertEquals(30_000L, req.timeoutMs)
    }

    @Test
    fun `HC2-HttpRequest equality is byte-content based`() {
        val a = HttpRequest(method = "POST", url = "https://x.test", body = byteArrayOf(1, 2, 0xFF.toByte()))
        val b = HttpRequest(method = "POST", url = "https://x.test", body = byteArrayOf(1, 2, 0xFF.toByte()))
        val differentBody = HttpRequest(method = "POST", url = "https://x.test", body = byteArrayOf(1, 2, 0xFE.toByte()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, differentBody)
    }

    // ── HttpResponse (04 §6.2) ─────────────────────────────────────────

    @Test
    fun `HC3-HttpResponse bodyText decodes UTF-8 including multibyte`() {
        val response = HttpResponse(status = 200, body = "空调 state=23°C ✓".encodeToByteArray())
        assertEquals("空调 state=23°C ✓", response.bodyText)
    }

    @Test
    fun `HC4-empty body is the absent-body form, not null`() {
        val response = HttpResponse(status = 204)
        assertEquals(0, response.body.size)
        assertEquals("", response.bodyText)
    }

    @Test
    fun `HC5-HttpResponse keeps repeated headers distinct and equals on content`() {
        val cookies = mapOf("set-cookie" to listOf("session=abc; Path=/", "tracker=xyz; Path=/"))
        val a = HttpResponse(status = 200, headers = cookies, body = byteArrayOf(9))
        val b = HttpResponse(status = 200, headers = cookies, body = byteArrayOf(9))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(listOf("session=abc; Path=/", "tracker=xyz; Path=/"), a.headers["set-cookie"])
        assertFalse(a == HttpResponse(status = 200, headers = cookies, body = byteArrayOf(8)))
    }

    // ── Clock default method (04 §6.5) ─────────────────────────────────

    @Test
    fun `HC6-Clock nowMs derives from now without an explicit override`() {
        val fixedInstant = Instant.fromEpochMilliseconds(1_768_000_000_000)
        val clock = object : Clock {
            override fun now(): Instant = fixedInstant
            override fun monotonicMs(): Long = 42L
        }
        assertEquals(1_768_000_000_000L, clock.nowMs())
        assertEquals(42L, clock.monotonicMs())
    }

    @Test
    fun `HC7-instant round-trips through the epoch-ms wire form`() {
        val instant = Instant.parse("2026-08-31T12:00:00Z")
        val clock = object : Clock {
            override fun now(): Instant = instant
            override fun monotonicMs(): Long = 0L
        }
        assertTrue(Instant.fromEpochMilliseconds(clock.nowMs()) == instant)
    }
}
