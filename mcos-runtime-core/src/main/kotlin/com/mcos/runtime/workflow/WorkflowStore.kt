package com.mcos.runtime.workflow

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of named workflow definitions, addressable by [com.mcos.runtime.api.Payload.WorkflowRef].
 *
 * Matches [05-workflow.md] workflow storage requirements.
 */
class WorkflowStore {

    private val workflows = ConcurrentHashMap<String, WorkflowStep>()

    /**
     * Register (or overwrite) a workflow under [id].
     *
     * @throws IllegalArgumentException if [id] is blank.
     */
    fun register(id: String, step: WorkflowStep) {
        require(id.isNotBlank()) { "workflow id must not be blank" }
        workflows[id] = step
    }

    /** Load a workflow by [id], or null if not registered. */
    fun get(id: String): WorkflowStep? = workflows[id]

    /** Remove a workflow by [id]; returns the removed step, or null. */
    fun remove(id: String): WorkflowStep? = workflows.remove(id)

    /** Sorted list of all registered workflow ids. */
    fun list(): List<String> = workflows.keys.sorted()

    /** Remove all registered workflows. */
    fun clear() = workflows.clear()
}
