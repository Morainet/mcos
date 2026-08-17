package com.morainet.mcos.runtime.security

import com.morainet.mcos.sdk.SideEffectClass
import kotlin.test.*

/**
 * Unit tests for [TokenBucketRateLimiter] token bucket implementation.
 * Matches [08-security.md 10].
 */
class RateLimiterTest {

    // ═══════════════════════════════════════════════════════════════
    // R1-R3: Basic consumption and limits
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R1-normal invocation within limits returns Allowed`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 60, maxDestructivePerHour = 5)
        val result = limiter.tryConsume("plugin.a", SideEffectClass.read)
        assertIs<RateLimitResult.Allowed>(result)
    }

    @Test
    fun `R2-exceeding invoke limit returns Limited`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 3, maxDestructivePerHour = 5)

        // Consume all 3 tokens
        repeat(3) {
            assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.a", SideEffectClass.read))
        }

        // 4th invocation should be limited
        val result = limiter.tryConsume("plugin.a", SideEffectClass.read)
        assertIs<RateLimitResult.Limited>(result)
        assertEquals(RateLimitKind.INVOKE_PER_MINUTE, result.kind)
        assertTrue(result.retryAfterMs > 0)
    }

    @Test
    fun `R3-destructive operations have separate stricter limits`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 60, maxDestructivePerHour = 2)

        // Consume 2 destructive tokens
        repeat(2) {
            assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.a", SideEffectClass.destructive))
        }

        // 3rd destructive should be limited
        val result = limiter.tryConsume("plugin.a", SideEffectClass.destructive)
        assertIs<RateLimitResult.Limited>(result)
        assertEquals(RateLimitKind.DESTRUCTIVE_PER_HOUR, result.kind)
    }

    // ═══════════════════════════════════════════════════════════════
    // R4: Token refill over time
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R4-tokens refill after window passes`() {
        // 600 tokens/min = 10 tokens/sec = 0.01 tokens/ms
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 600, maxDestructivePerHour = 100)

        val pluginId = "plugin.refill"

        // Consume 10 tokens
        repeat(10) {
            assertIs<RateLimitResult.Allowed>(limiter.tryConsume(pluginId, SideEffectClass.read))
        }

        val afterConsume = limiter.getRemainingTokens(pluginId, RateLimitKind.INVOKE_PER_MINUTE)
        // ~590 tokens remain (600 - 10 = 590, plus minimal refill from getRemainingTokens)
        assertTrue(afterConsume <= 590, "Expected ~590 remaining, got $afterConsume")

        // Wait 500ms — at 10 tokens/sec, 5 tokens should refill
        Thread.sleep(500)

        val afterSleep = limiter.getRemainingTokens(pluginId, RateLimitKind.INVOKE_PER_MINUTE)
        assertTrue(afterSleep > afterConsume, "Should refill after sleep: before=$afterConsume, afterSleep=$afterSleep")
    }

    // ═══════════════════════════════════════════════════════════════
    // R5-R6: Plugin isolation and remaining tokens
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R5-different plugins have independent limits`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 1, maxDestructivePerHour = 100)

        // Plugin A consumes its token
        assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.a", SideEffectClass.read))
        // Plugin A should be limited now
        assertIs<RateLimitResult.Limited>(limiter.tryConsume("plugin.a", SideEffectClass.read))
        // Plugin B should still have its token
        assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.b", SideEffectClass.read))
    }

    @Test
    fun `R6-getRemainingTokens reports correct counts`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 10, maxDestructivePerHour = 5)

        val initial = limiter.getRemainingTokens("plugin.a", RateLimitKind.INVOKE_PER_MINUTE)
        assertEquals(10, initial)

        limiter.tryConsume("plugin.a", SideEffectClass.read)
        limiter.tryConsume("plugin.a", SideEffectClass.read)

        val remaining = limiter.getRemainingTokens("plugin.a", RateLimitKind.INVOKE_PER_MINUTE)
        assertEquals(8, remaining)
    }

    // ═══════════════════════════════════════════════════════════════
    // R7: Reset
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R7-reset clears all limits`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 1, maxDestructivePerHour = 100)

        // Consume token
        limiter.tryConsume("plugin.a", SideEffectClass.read)
        assertIs<RateLimitResult.Limited>(limiter.tryConsume("plugin.a", SideEffectClass.read))

        // Reset
        limiter.reset()

        // Should be allowed again
        assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.a", SideEffectClass.read))
    }

    // ═══════════════════════════════════════════════════════════════
    // R8: Destructive limit only for destructive+ side effects
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `R8-read commands do not consume destructive tokens`() {
        val limiter = TokenBucketRateLimiter(maxInvokesPerMinute = 100, maxDestructivePerHour = 5)

        // Many read commands should not trigger destructive limit
        repeat(20) {
            assertIs<RateLimitResult.Allowed>(limiter.tryConsume("plugin.a", SideEffectClass.read))
        }
    }
}
