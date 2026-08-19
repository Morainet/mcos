package com.morainet.mcos.security

import com.morainet.mcos.sdk.SideEffectClass
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for MCOS command execution.
 *
 * Implements [08-security.md 10] — per-plugin/per-minute invoke limit and
 * per-plugin/per-hour destructive operation limit.
 *
 * The limiter is an interface so the executor wiring is never `null`
 * (null would silently disable rate limiting — fail-open). Production uses
 * [TokenBucketRateLimiter]; tests use the named [UnlimitedRateLimiter] to
 * opt out explicitly.
 */
interface RateLimiter {

    /**
     * Attempt to consume a token for the given plugin and side effect class.
     *
     * @param pluginId The plugin identifier.
     * @param sideEffectClass The command's [SideEffectClass].
     * @return [RateLimitResult.Allowed] if within limits,
     *         [RateLimitResult.Limited] with retry-after info if rate-limited.
     */
    fun tryConsume(pluginId: String, sideEffectClass: SideEffectClass): RateLimitResult

    /**
     * Get remaining tokens for a plugin's rate limit kind.
     * Useful for diagnostics and UI feedback.
     */
    fun getRemainingTokens(pluginId: String, kind: RateLimitKind): Int

    /** Reset all rate limit state (for testing). */
    fun reset()
}

/**
 * In-memory token bucket [RateLimiter].
 *
 * ## Algorithm
 *
 * Each `(pluginId, kind)` pair maintains a token bucket:
 * - `INVOKE_PER_MINUTE`: refills at `maxInvokesPerMinute` tokens per 60s window
 * - `DESTRUCTIVE_PER_HOUR`: refills at `maxDestructivePerHour` tokens per 3600s window
 *
 * Token consumption is **not** real-time refilling — the bucket refills
 * proportionally based on elapsed time since last consumption (virtual scheduling).
 *
 * ## Thread safety
 *
 * Uses [ConcurrentHashMap] — safe for concurrent access from multiple coroutines.
 * Individual bucket state mutation is synchronized on the bucket object itself.
 *
 * ## P1 scope
 *
 * Pure in-memory implementation. No persistence — limits reset on process restart.
 * P2 may add sliding-window or Redis-backed distributed limiting.
 *
 * @param maxInvokesPerMinute Maximum general invocations per plugin per minute (default: 60).
 * @param maxDestructivePerHour Maximum destructive invocations per plugin per hour (default: 5).
 */
class TokenBucketRateLimiter(
    internal val maxInvokesPerMinute: Int = 60,
    internal val maxDestructivePerHour: Int = 5,
) : RateLimiter {

    private data class TokenBucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    // ─── Public API ────────────────────────────────────────────────────────

    override fun tryConsume(pluginId: String, sideEffectClass: SideEffectClass): RateLimitResult {
        // Check general invoke limit
        val invokeResult = checkLimit(pluginId, RateLimitKind.INVOKE_PER_MINUTE, maxInvokesPerMinute, 60_000L)
        if (invokeResult is RateLimitResult.Limited) {
            return invokeResult
        }

        // Check destructive limit only for destructive+ commands
        if (sideEffectClass >= SideEffectClass.destructive) {
            val destructiveResult = checkLimit(
                pluginId, RateLimitKind.DESTRUCTIVE_PER_HOUR, maxDestructivePerHour, 3_600_000L
            )
            if (destructiveResult is RateLimitResult.Limited) {
                return destructiveResult
            }
        }

        return RateLimitResult.Allowed
    }

    override fun getRemainingTokens(pluginId: String, kind: RateLimitKind): Int {
        val key = bucketKey(pluginId, kind)
        val bucket = buckets[key] ?: return maxTokensFor(kind)
        synchronized(bucket) {
            refill(bucket, maxTokensFor(kind), kind.windowMs)
            return bucket.tokens.toInt().coerceAtLeast(0)
        }
    }

    override fun reset() {
        buckets.clear()
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private fun maxTokensFor(kind: RateLimitKind): Int = when (kind) {
        RateLimitKind.INVOKE_PER_MINUTE -> maxInvokesPerMinute
        RateLimitKind.DESTRUCTIVE_PER_HOUR -> maxDestructivePerHour
    }

    private fun checkLimit(
        pluginId: String,
        kind: RateLimitKind,
        maxTokens: Int,
        windowMs: Long,
    ): RateLimitResult {
        val key = bucketKey(pluginId, kind)
        val bucket = buckets.computeIfAbsent(key) {
            TokenBucket(tokens = maxTokens.toDouble(), lastRefillMs = System.currentTimeMillis())
        }

        synchronized(bucket) {
            refill(bucket, maxTokens, windowMs)

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return RateLimitResult.Allowed
            }

            // Calculate time until next token is available
            val elapsed = System.currentTimeMillis() - bucket.lastRefillMs
            val tokensPerMs = maxTokens.toDouble() / windowMs
            val retryAfterMs = if (tokensPerMs > 0) {
                ((1.0 - bucket.tokens) / tokensPerMs).toLong() + 1
            } else {
                windowMs
            }

            return RateLimitResult.Limited(
                retryAfterMs = retryAfterMs.coerceIn(1, windowMs),
                kind = kind,
            )
        }
    }

    private fun refill(bucket: TokenBucket, maxTokens: Int, windowMs: Long) {
        val now = System.currentTimeMillis()
        val elapsed = now - bucket.lastRefillMs
        if (elapsed <= 0) return

        val tokensPerMs = maxTokens.toDouble() / windowMs
        val newTokens = elapsed * tokensPerMs
        bucket.tokens = (bucket.tokens + newTokens).coerceAtMost(maxTokens.toDouble())
        bucket.lastRefillMs = now
    }

    private fun bucketKey(pluginId: String, kind: RateLimitKind): String =
        "$pluginId:${kind.name}"
}

// ─── Public types ──────────────────────────────────────────────────────────

/** Result of a [RateLimiter.tryConsume] call. */
sealed class RateLimitResult {
    /** The command is allowed to proceed. */
    data object Allowed : RateLimitResult()

    /** The command is rate-limited. [retryAfterMs] indicates when it may be retried. */
    data class Limited(
        val retryAfterMs: Long,
        val kind: RateLimitKind,
    ) : RateLimitResult()
}

/** Kinds of rate limits tracked by [RateLimiter]. */
enum class RateLimitKind(val key: String) {
    INVOKE_PER_MINUTE("invoke_per_minute"),
    DESTRUCTIVE_PER_HOUR("destructive_per_hour"),
    ;

    internal val windowMs: Long
        get() = when (this) {
            INVOKE_PER_MINUTE -> 60_000L
            DESTRUCTIVE_PER_HOUR -> 3_600_000L
        }
}
