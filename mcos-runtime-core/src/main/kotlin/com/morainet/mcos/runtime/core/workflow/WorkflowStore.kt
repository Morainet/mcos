package com.morainet.mcos.runtime.core.workflow

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of named workflow definitions, addressable by [com.morainet.mcos.runtime.core.api.Payload.WorkflowRef].
 *
 * Workflows are stored as [WorkflowSpec]s (step tree + optional trigger,
 * 05 §9). The classic [register]/[get] pair addresses the step tree and
 * behaves exactly as before; trigger-aware callers use [registerSpec]/[spec].
 *
 * Matches [05-workflow.md] workflow storage requirements.
 */
class WorkflowStore {

    private val specs = ConcurrentHashMap<String, WorkflowSpec>()

    /**
     * Register (or overwrite) a workflow under [id].
     *
     * @throws IllegalArgumentException if [id] is blank.
     */
    fun register(id: String, step: WorkflowStep) {
        registerSpec(id, WorkflowSpec(trigger = null, step = step))
    }

    /**
     * Register (or overwrite) a workflow **with its trigger** under [id].
     *
     * @throws IllegalArgumentException if [id] is blank.
     */
    fun registerSpec(id: String, spec: WorkflowSpec) {
        require(id.isNotBlank()) { "workflow id must not be blank" }
        specs[id] = spec
    }

    /** Load a workflow's step tree by [id], or null if not registered. */
    fun get(id: String): WorkflowStep? = specs[id]?.step

    /** Load a workflow's full spec (step tree + trigger) by [id], or null. */
    fun spec(id: String): WorkflowSpec? = specs[id]

    /** Remove a workflow by [id]; returns the removed step tree, or null. */
    fun remove(id: String): WorkflowStep? = specs.remove(id)?.step

    /** Sorted list of all registered workflow ids. */
    fun list(): List<String> = specs.keys.sorted()

    /** Remove all registered workflows. */
    fun clear() = specs.clear()
}
