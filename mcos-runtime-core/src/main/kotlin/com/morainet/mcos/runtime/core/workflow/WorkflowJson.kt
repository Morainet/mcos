package com.morainet.mcos.runtime.core.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON <-> [WorkflowStep] conversion.
 *
 * [WorkflowStep] is a plain sealed class (no kotlinx.serialization support),
 * so workflow definitions arriving as IR JSON are decoded by hand here.
 *
 * Matches [05-workflow.md] WorkflowSchema.
 */
object WorkflowJson {

    /**
     * Decode a workflow definition from [json].
     *
     * @return the decoded [WorkflowStep], or null when the JSON is not a
     *         recognized workflow node (unknown type, wrong shape, etc.).
     */
    fun fromJson(json: JsonElement): WorkflowStep? = parseStep(json)

    // ─── Step decoding ──────────────────────────────────────────────────

    private fun parseStep(json: JsonElement?): WorkflowStep? {
        val obj = json as? JsonObject ?: return null
        val type = asString(obj["type"]) ?: return null
        return when (type) {
            // IR envelope produced by DslParser: { "type": "workflow", "body": <step> }
            "workflow" -> parseStep(obj["body"])
            "command" -> parseCommand(obj)
            "sequential" -> parseSequential(obj)
            "parallel" -> parseParallel(obj)
            "if" -> parseIf(obj)
            "loop" -> parseLoop(obj)
            "retry" -> parseRetry(obj)
            "try" -> parseTry(obj)
            else -> null
        }
    }

    private fun parseCommand(obj: JsonObject): WorkflowStep? {
        val commandId = asString(obj["commandId"]) ?: return null
        return WorkflowStep.Command(
            commandId = commandId,
            args = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
        )
    }

    private fun parseSequential(obj: JsonObject): WorkflowStep? {
        val steps = parseSteps(obj["steps"])
        if (steps.isEmpty()) return null
        return WorkflowStep.Sequential(steps)
    }

    private fun parseParallel(obj: JsonObject): WorkflowStep? {
        val steps = parseSteps(obj["steps"])
        if (steps.isEmpty()) return null
        return WorkflowStep.Parallel(steps)
    }

    private fun parseIf(obj: JsonObject): WorkflowStep? {
        val condition = obj["condition"]?.let { parseCondition(it) } ?: return null
        val thenStep = parseStep(obj["then"]) ?: return null
        return WorkflowStep.If(
            condition = condition,
            thenStep = thenStep,
            elseStep = parseStep(obj["else"])
        )
    }

    private fun parseLoop(obj: JsonObject): WorkflowStep? {
        val body = parseStep(obj["body"]) ?: return null
        return WorkflowStep.Loop(
            body = body,
            condition = obj["condition"]?.let { parseCondition(it) },
            maxIterations = asInt(obj["maxIterations"]) ?: 100
        )
    }

    private fun parseRetry(obj: JsonObject): WorkflowStep? {
        val step = parseStep(obj["step"]) ?: return null
        return WorkflowStep.Retry(
            step = step,
            maxRetries = asInt(obj["maxRetries"]) ?: 3,
            backoffMs = asLong(obj["backoffMs"]) ?: 1000L,
            idempotent = asBool(obj["idempotent"]) ?: true,
            retryOnCodes = asStringSet(obj["retryOnCodes"])
        )
    }

    private fun parseTry(obj: JsonObject): WorkflowStep? {
        val step = parseStep(obj["step"]) ?: return null
        return WorkflowStep.Try(
            step = step,
            compensation = parseSteps(obj["compensation"])
        )
    }

    private fun parseSteps(json: JsonElement?): List<WorkflowStep> {
        val arr = json as? JsonArray ?: return emptyList()
        return arr.mapNotNull { parseStep(it) }
    }

    // ─── Condition decoding ─────────────────────────────────────────────

    private fun parseCondition(json: JsonElement): WorkflowCondition? {
        val obj = json as? JsonObject ?: return null
        return when (asString(obj["type"])) {
            "always" -> WorkflowCondition.Always(asBool(obj["value"]) ?: false)
            "based_on_previous" -> {
                val predicate = when (asString(obj["predicate"])) {
                    "LAST_STEP_SUCCEEDED" -> WorkflowPredicate.LAST_STEP_SUCCEEDED
                    "LAST_STEP_FAILED" -> WorkflowPredicate.LAST_STEP_FAILED
                    "HAS_ARTIFACTS" -> WorkflowPredicate.HAS_ARTIFACTS
                    else -> return null
                }
                WorkflowCondition.BasedOnPrevious(predicate)
            }
            else -> null
        }
    }

    // ─── Primitive readers ──────────────────────────────────────────────

    private fun asString(json: JsonElement?): String? =
        (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun asBool(json: JsonElement?): Boolean? =
        (json as? JsonPrimitive)?.booleanOrNull

    private fun asInt(json: JsonElement?): Int? =
        (json as? JsonPrimitive)?.intOrNull

    private fun asLong(json: JsonElement?): Long? =
        (json as? JsonPrimitive)?.longOrNull

    private fun asStringSet(json: JsonElement?): Set<String> {
        val arr = json as? JsonArray ?: return emptySet()
        return arr.mapNotNull { asString(it) }.toSet()
    }
}
