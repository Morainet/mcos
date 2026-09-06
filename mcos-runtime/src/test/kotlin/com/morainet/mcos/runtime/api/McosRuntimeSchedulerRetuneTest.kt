package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.scheduler.SchedulerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Facade wiring of scheduler hot-retuning (03-runtime.md §19.1, item 55):
 * [McosRuntime.reconfigureScheduler] reaches the runtime's scheduler, returns
 * the previously-active config, refuses builder-time drift loudly, and refuses
 * a post-shutdown retune.
 */
class McosRuntimeSchedulerRetuneTest {

    @Test
    fun `reconfigureScheduler retunes live knobs and returns the previous config`() {
        val runtime = McosRuntime.Builder()
            .withRegistry(CommandRegistry())
            .build()

        val previous = runtime.reconfigureScheduler(
            SchedulerConfig().copy(maxConcurrentInvokes = 8)
        )
        assertEquals(4, previous.maxConcurrentInvokes)

        // Builder-time fields (lane channels / executor limiter) cannot drift.
        assertFailsWith<IllegalArgumentException> {
            runtime.reconfigureScheduler(SchedulerConfig().copy(laneCapacity = 4))
        }
        runtime.shutdown()
    }

    @Test
    fun `reconfigureScheduler refuses a post-shutdown retune`() {
        val runtime = McosRuntime.Builder()
            .withRegistry(CommandRegistry())
            .build()
        runtime.shutdown()
        assertFailsWith<IllegalArgumentException> {
            runtime.reconfigureScheduler(SchedulerConfig())
        }
    }
}
