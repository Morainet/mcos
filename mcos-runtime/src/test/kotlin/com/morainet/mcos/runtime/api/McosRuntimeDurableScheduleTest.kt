package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.workflow.ArmedScheduleStore
import com.morainet.mcos.runtime.core.workflow.PersistedSchedule
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.TriggerArmResult
import com.morainet.mcos.runtime.core.workflow.WorkflowSpec
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.runtime.core.workflow.WorkflowStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Durable schedule hosting through the facade (10 §6): arming persists the
 * schedule to an [ArmedScheduleStore], and a fresh runtime re-arms it via
 * [McosRuntime.rehydrateSchedules] once the workflow is re-registered.
 */
class McosRuntimeDurableScheduleTest {

    private class InMemoryArmedScheduleStore(
        var records: List<PersistedSchedule> = emptyList(),
    ) : ArmedScheduleStore {
        override fun load(): List<PersistedSchedule> = records
        override fun save(records: List<PersistedSchedule>) { this.records = records }
    }

    private fun scheduleSpec(cron: String = "0 23 * * *", tz: String = "Asia/Shanghai") =
        WorkflowSpec(
            trigger = Trigger.Schedule(cron = cron, tz = tz),
            step = WorkflowStep.Command("net.notify"),
        )

    private fun buildRuntime(store: ArmedScheduleStore, workflowStore: WorkflowStore) =
        McosRuntime.Builder()
            .withRegistry(CommandRegistry())
            .withEventBus(TypedEventBus())
            .withWorkflowStore(workflowStore)
            .withArmedScheduleStore(store)
            .build()

    @Test fun `DS1 arming a schedule persists it to the store`() = runBlocking {
        val store = InMemoryArmedScheduleStore()
        val rt = buildRuntime(store, WorkflowStore().apply { registerSpec("nightly", scheduleSpec()) })
        try {
            assertIs<TriggerArmResult.Armed>(rt.armTrigger("nightly", preAuthorized = true))
            assertEquals(listOf(PersistedSchedule("nightly", true)), store.load())
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `DS2 a fresh runtime re-arms the persisted schedule`() = runBlocking {
        val store = InMemoryArmedScheduleStore(listOf(PersistedSchedule("nightly", true)))
        // A fresh process: the workflow is re-registered (e.g. marketplace rehydration).
        val rt = buildRuntime(store, WorkflowStore().apply { registerSpec("nightly", scheduleSpec()) })
        try {
            assertEquals(1, rt.rehydrateSchedules())
            assertEquals(listOf("nightly"), rt.armedTriggers())
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `DS3 a record whose workflow is not re-registered is pruned`() = runBlocking {
        val store = InMemoryArmedScheduleStore(listOf(PersistedSchedule("ghost", false)))
        val rt = buildRuntime(store, WorkflowStore()) // "ghost" never registered
        try {
            assertEquals(0, rt.rehydrateSchedules())
            assertTrue(rt.armedTriggers().isEmpty())
            assertTrue(store.load().isEmpty(), "the unresolvable record must be pruned")
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `DS4 disarming removes the schedule from the store`() = runBlocking {
        val store = InMemoryArmedScheduleStore()
        val rt = buildRuntime(store, WorkflowStore().apply { registerSpec("nightly", scheduleSpec()) })
        try {
            rt.armTrigger("nightly")
            assertEquals(1, store.load().size)
            assertTrue(rt.disarmTrigger("nightly"))
            assertTrue(store.load().isEmpty())
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `DS5 the default store re-arms nothing (lifetime-only)`() = runBlocking {
        // No withArmedScheduleStore → NullArmedScheduleStore.
        val rt = McosRuntime.Builder()
            .withRegistry(CommandRegistry())
            .withEventBus(TypedEventBus())
            .withWorkflowStore(WorkflowStore().apply { registerSpec("nightly", scheduleSpec()) })
            .build()
        try {
            assertIs<TriggerArmResult.Armed>(rt.armTrigger("nightly"))
            assertEquals(0, rt.rehydrateSchedules(), "the null store persists nothing to re-arm")
        } finally {
            rt.shutdown()
        }
    }
}
