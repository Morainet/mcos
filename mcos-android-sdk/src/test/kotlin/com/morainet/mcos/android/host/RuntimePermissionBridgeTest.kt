package com.morainet.mcos.android.host

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuntimePermissionBridge] semantics (the in-app runtime-permission prompt
 * flow, 04-plugin-sdk §6.3): pure Kotlin — the Compose launcher reaches the
 * bridge only through the [RuntimePermissionBridge.Prompter] seam, so these
 * JVM tests cover the exact code the Android shell runs.
 */
class RuntimePermissionBridgeTest {

    private val perm = "android.permission.ACCESS_FINE_LOCATION"

    /** Records prompted permissions; answers are driven by the test. */
    private class RecordingPrompter : RuntimePermissionBridge.Prompter {
        val prompted = mutableListOf<String>()
        override fun prompt(permission: String) {
            prompted += permission
        }
    }

    /** Awaits the moment the prompter recorded its first (next) call. */
    private suspend fun awaitPrompt(prompter: RecordingPrompter, count: Int) {
        withTimeout(1_000) {
            while (prompter.prompted.size < count) delay(1)
        }
    }

    // ─── Headless / no-Activity paths ───────────────────────────────────

    @Test
    fun `request with no attached prompter returns null - headless run`() = runBlocking {
        val bridge = RuntimePermissionBridge()

        assertNull(bridge.request(perm))
    }

    @Test
    fun `a prompt while one is already pending returns null without disturbing it`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        val first = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)
        assertTrue(first.isActive) // still awaiting the user

        // A second command hitting a missing grant must not clobber the
        // in-flight dialog — it gets null and surfaces an honest denial.
        assertNull(bridge.request("android.permission.CAMERA"))

        bridge.onResult(false)
        assertEquals(false, first.await())
        assertEquals(listOf(perm), prompter.prompted) // CAMERA never prompted
    }

    // ─── Prompt round-trip ──────────────────────────────────────────────

    @Test
    fun `request suspends until the user grants`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        val pending = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)
        assertTrue(pending.isActive) // prompted, still suspended on the dialog

        bridge.onResult(true)
        assertEquals(true, pending.await())
        assertEquals(listOf(perm), prompter.prompted)
    }

    @Test
    fun `request surfaces the user's denial as false`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        val pending = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)

        bridge.onResult(false)
        assertEquals(false, pending.await())
    }

    @Test
    fun `a completed round clears state - the next request prompts again`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        val first = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)
        bridge.onResult(true)
        assertEquals(true, first.await())

        val second = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 2)
        bridge.onResult(false)
        assertEquals(false, second.await())
        assertEquals(listOf(perm, perm), prompter.prompted)
    }

    // ─── Cancellation & stray answers ───────────────────────────────────

    @Test
    fun `cancelPending completes the await with null and the bridge stays usable`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        val pending = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)

        bridge.cancelPending()
        assertNull(pending.await())

        // The stray answer for the cancelled dialog is a no-op...
        bridge.onResult(true)
        // ...and the next request prompts cleanly.
        val next = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 2)
        bridge.onResult(true)
        assertEquals(true, next.await())
    }

    @Test
    fun `onResult with no pending request is a no-op`() = runBlocking {
        val prompter = RecordingPrompter()
        val bridge = RuntimePermissionBridge().apply { attach(prompter) }

        bridge.onResult(true)

        val pending = async { bridge.request(perm) }
        awaitPrompt(prompter, count = 1)
        bridge.onResult(false)
        assertEquals(false, pending.await())
    }

    // ─── Re-attach ──────────────────────────────────────────────────────

    @Test
    fun `attach replaces the prompter - latest Activity wins`() = runBlocking {
        val first = RecordingPrompter()
        val second = RecordingPrompter()
        val bridge = RuntimePermissionBridge()
        bridge.attach(first)
        bridge.attach(second)

        val pending = async { bridge.request(perm) }
        awaitPrompt(second, count = 1)

        bridge.onResult(true)
        assertEquals(true, pending.await())
        assertTrue(first.prompted.isEmpty()) // only the latest prompter runs
    }
}
