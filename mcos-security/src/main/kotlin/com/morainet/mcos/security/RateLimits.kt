package com.morainet.mcos.security

/**
 * The three rate-limit knobs surfaced by [03-runtime.md §19]'s `rateLimits`.
 *
 * These are the same three limits already enforced by [TokenBucketRateLimiter]
 * (invoke + destructive) and the trigger managers (background fires); this type
 * carries them together so `RuntimeConfig` can hot-retune all three from one
 * place. Defaults match the historical constructor defaults exactly, so
 * `RateLimits()` is the pre-§19 behaviour.
 *
 * @property maxInvokesPerMinute token-bucket cap on total invocations/minute.
 * @property maxDestructivePerHour token-bucket cap on DESTRUCTIVE-class
 *   invocations/hour.
 * @property maxBackgroundFiresPerHour cap on background trigger fires/hour
 *   (event + schedule managers share this knob).
 */
data class RateLimits(
    val maxInvokesPerMinute: Int = 60,
    val maxDestructivePerHour: Int = 5,
    val maxBackgroundFiresPerHour: Int = 20,
)
