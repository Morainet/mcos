package com.morainet.mcos.sdk

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance tests for the JVM reference [UserFileGrantService]
 * (04-plugin-sdk.md 6.1 — user-granted out-of-sandbox files).
 */
class UserFileGrantsTest {

    private val service = StaticUserFileGrantService()

    private fun grant(ref: String, name: String? = "note.txt", mime: String? = "text/plain", size: Long? = null) =
        UserFileGrant(ref = ref, name = name, mimeType = mime, sizeBytes = size)

    @Test
    fun `UG1-pickForRead pops seeded grants in FIFO order`() = runBlocking {
        service.seed(grant("ref-a"), "aaa".toByteArray())
        service.seed(grant("ref-b"), "bb".toByteArray())

        val first = service.pickForRead()
        val second = service.pickForRead()

        assertEquals("ref-a", first?.ref)
        assertEquals("ref-b", second?.ref)
    }

    @Test
    fun `UG2-pickForRead with an empty queue is a user cancel`() = runBlocking {
        assertNull(service.pickForRead())
    }

    @Test
    fun `UG3-readGranted and statGranted resolve seeded contents`() = runBlocking {
        service.seed(grant("ref-a", size = 3), "aaa".toByteArray())
        service.pickForRead()

        assertEquals("aaa".toByteArray().toList(), service.readGranted("ref-a")!!.toList())
        val stat = service.statGranted("ref-a")
        assertEquals("ref-a", stat?.ref)
        assertEquals(3L, stat?.sizeBytes)
    }

    @Test
    fun `UG4-readGranted and statGranted of an unknown ref are null`() = runBlocking {
        assertNull(service.readGranted("nope"))
        assertNull(service.statGranted("nope"))
    }

    @Test
    fun `UG5-releaseGranted drops the grant and returns false on repeat`() = runBlocking {
        service.seed(grant("ref-a"), "aaa".toByteArray())
        service.pickForRead()

        assertTrue(service.releaseGranted("ref-a"))
        assertFalse(service.releaseGranted("ref-a"))
        assertNull(service.readGranted("ref-a"))
        assertNull(service.statGranted("ref-a"))
    }

    @Test
    fun `UG6-pickForRead honours the mime filter and unmatched seeds stay cancel-null`() = runBlocking {
        service.seed(grant("img", mime = "image/png"))

        assertNull(service.pickForRead(listOf("text/plain")), "a filtered-out pick is a cancel")
        // The rejected seed is consumed by the attempt either way — the
        // user looked at it and could not choose it.
        assertNull(service.pickForRead())
    }
}
