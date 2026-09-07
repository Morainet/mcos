package com.morainet.mcos.runtime.core.config

import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.security.RateLimits
import com.morainet.mcos.security.RedactionLevel
import com.morainet.mcos.security.UserPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RC1-RC10 — [RuntimeConfig] validation + the security-forcing normalization
 * (03-runtime.md §19.1). Validation NEVER silently coerces; an invalid config
 * throws INTERNAL/component=config.
 */
class RuntimeConfigTest {

    @Test
    fun `RC1-default config validates`() {
        RuntimeConfig().validate() // no throw
    }

    @Test
    fun `RC2-maxParallel below range is rejected`() {
        val ex = assertFailsWith<McosException> {
            RuntimeConfig(maxParallel = 0, scheduler = SchedulerConfig(maxConcurrentInvokes = 0)).validate()
        }
        assertEquals("INTERNAL", ex.code)
        assertEquals("config", ex.details["component"].toString().trim('"'))
    }

    @Test
    fun `RC3-maxParallel above range is rejected`() {
        assertFailsWith<McosException> {
            RuntimeConfig(maxParallel = 17, scheduler = SchedulerConfig(maxConcurrentInvokes = 17)).validate()
        }
    }

    @Test
    fun `RC4-defaultTimeoutMs below floor is rejected`() {
        assertFailsWith<McosException> { RuntimeConfig(defaultTimeoutMs = 999).validate() }
    }

    @Test
    fun `RC5-defaultTimeoutMs above ceiling is rejected`() {
        assertFailsWith<McosException> { RuntimeConfig(defaultTimeoutMs = 600_001).validate() }
    }

    @Test
    fun `RC6-maxParallel must equal scheduler maxConcurrentInvokes`() {
        val ex = assertFailsWith<McosException> {
            RuntimeConfig(maxParallel = 8, scheduler = SchedulerConfig(maxConcurrentInvokes = 4)).validate()
        }
        assertTrue(ex.message.contains("must equal scheduler"))
    }

    @Test
    fun `RC7-a non-null enterpriseAllowlist forces auditRedaction to at least STRICT`() {
        val cfg = RuntimeConfig(
            enterpriseAllowlist = listOf("camera.*"),
            auditRedaction = RedactionLevel.DEFAULT,
        ).normalized()
        assertEquals(RedactionLevel.STRICT, cfg.auditRedaction)
    }

    @Test
    fun `RC8-normalize does not lower an already-STRICT redaction`() {
        val cfg = RuntimeConfig(
            enterpriseAllowlist = listOf("camera.*"),
            auditRedaction = RedactionLevel.STRICT,
        ).normalized()
        assertEquals(RedactionLevel.STRICT, cfg.auditRedaction)
    }

    @Test
    fun `RC9-an empty (non-null) allow-list is rejected`() {
        assertFailsWith<McosException> { RuntimeConfig(enterpriseAllowlist = emptyList()).validate() }
    }

    @Test
    fun `RC10-a zero rate limit is rejected`() {
        assertFailsWith<McosException> {
            RuntimeConfig(rateLimits = RateLimits(maxInvokesPerMinute = 0)).validate()
        }
    }

    @Test
    fun `RC11-a fully custom valid config round-trips through normalize`() {
        val cfg = RuntimeConfig(
            maxParallel = 8,
            defaultTimeoutMs = 30_000,
            strictSchemaOutput = true,
            auditRedaction = RedactionLevel.STRICT,
            eventTriggersEnabled = false,
            enterpriseAllowlist = listOf("files.*"),
            networkAllowList = listOf("*.example.com"),
            rateLimits = RateLimits(120, 10, 40),
            scheduler = SchedulerConfig(maxConcurrentInvokes = 8),
            userPolicy = UserPolicy(confirmEveryNetwork = true),
        )
        assertEquals(cfg, cfg.normalized())
    }
}
