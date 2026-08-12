package com.mcos.runtime.workflow

import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/**
 * Tests for [WorkflowStore] — named workflow registry.
 */
class WorkflowStoreTest {

    private fun simpleStep(id: String = "test.cmd"): WorkflowStep.Command =
        WorkflowStep.Command(commandId = id, args = JsonObject(emptyMap()))

    @Test
    fun `register and get returns the same step`() {
        val store = WorkflowStore()
        val step = simpleStep()

        store.register("wf-1", step)

        assertSame(step, store.get("wf-1"))
    }

    @Test
    fun `get missing id returns null`() {
        val store = WorkflowStore()

        assertNull(store.get("missing"))
    }

    @Test
    fun `register overwrites existing id`() {
        val store = WorkflowStore()
        val first = simpleStep("test.a")
        val second = simpleStep("test.b")

        store.register("wf-1", first)
        store.register("wf-1", second)

        val loaded = store.get("wf-1") as? WorkflowStep.Command
        assertEquals("test.b", loaded?.commandId)
    }

    @Test
    fun `blank id is rejected`() {
        val store = WorkflowStore()
        val step = simpleStep()

        assertFailsWith<IllegalArgumentException> {
            store.register("  ", step)
        }
    }

    @Test
    fun `remove returns the step and clears entry`() {
        val store = WorkflowStore()
        val step = simpleStep()
        store.register("wf-1", step)

        assertSame(step, store.remove("wf-1"))
        assertNull(store.get("wf-1"))
    }

    @Test
    fun `list returns sorted ids`() {
        val store = WorkflowStore()
        store.register("zebra", simpleStep())
        store.register("alpha", simpleStep())
        store.register("mid", simpleStep())

        assertEquals(listOf("alpha", "mid", "zebra"), store.list())
    }

    @Test
    fun `clear removes all workflows`() {
        val store = WorkflowStore()
        store.register("a", simpleStep())
        store.register("b", simpleStep())

        store.clear()

        assertTrue(store.list().isEmpty())
    }
}
