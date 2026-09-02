package com.morainet.mcos.runtime.core.scheduler

/**
 * The four scheduler lanes — 03-runtime.md §8.1.
 *
 * | Lane | Use |
 * |------|-----|
 * | [INTERACTIVE] | User-facing CLI/chat (low latency) |
 * | [WORKFLOW] | Multi-step jobs |
 * | [BACKGROUND] | Event-triggered / deferred |
 * | [EXPEDITED] | User-confirmed safety-critical cancellations only |
 */
enum class SchedulerLane { INTERACTIVE, WORKFLOW, BACKGROUND, EXPEDITED }

/**
 * Kind of work submitted to a lane. Only [CANCELLATION] may be enqueued on
 * [SchedulerLane.EXPEDITED] — any other kind there is a configuration bug and is
 * rejected with `INTERNAL` at admission (03-runtime.md §8.4).
 */
enum class WorkKind { RUN, CANCELLATION }

/**
 * Admission outcome of [RunScheduler.enqueue].
 *
 * `RATE_LIMITED` rejections carry the §8.4 exponential `retryAfterMs`; `INTERNAL`
 * (expedited-lane guard violation) and `UNAVAILABLE` (post-shutdown) rejections
 * carry no backoff.
 */
sealed class SubmitResult {
    /** The item was accepted onto the lane; its body will run when a worker + semaphore permit free up. */
    data class Admitted(val runId: String) : SubmitResult()

    /**
     * The item was refused before queueing — the lane never saw the body.
     *
     * @param code One of `RATE_LIMITED`, `INTERNAL`, `UNAVAILABLE` (McosErrorCode names).
     * @param retryAfterMs Present for `RATE_LIMITED` only.
     */
    data class Rejected(val code: String, val retryAfterMs: Long?, val message: String) : SubmitResult()
}

/**
 * Point-in-time scheduler observability snapshot (03-runtime.md §8.4 "Observability":
 * queue depth and semaphore wait-time are exposed as Runtime metrics).
 *
 * @param laneDepth Items currently waiting on each lane (admitted, not yet picked up).
 * @param laneInFlight Items currently executing a body on each lane (past the semaphore).
 * @param semaphoreAcquisitions Total global-semaphore acquisitions since [RunScheduler.start].
 * @param semaphoreMaxWaitMs Longest observed acquire wait, in ms.
 * @param semaphoreLastWaitMs Most recent acquire wait, in ms.
 */
data class SchedulerMetrics(
    val laneDepth: Map<SchedulerLane, Int>,
    val laneInFlight: Map<SchedulerLane, Int>,
    val semaphoreAcquisitions: Long,
    val semaphoreMaxWaitMs: Long,
    val semaphoreLastWaitMs: Long,
)
