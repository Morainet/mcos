package com.morainet.mcos.runtime.core.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /**
     * Decode a workflow definition **including its trigger** (05 §9) from [json].
     *
     * The trigger may sit at the IR-envelope level (`{"type":"workflow",
     * "trigger": {...}, "body": {...}}`) or inside the body (the spec's compile
     * pass reads `body["trigger"]`, 05 §11.1 step 8) — both shapes are accepted.
     * An absent or null trigger yields a manual-only spec.
     *
     * @return the decoded [WorkflowSpec], or null when the step tree does not
     *         decode (a malformed trigger also rejects the whole spec — an
     *         unparseable trigger must never silently degrade to manual-only).
     */
    fun specFromJson(json: JsonElement): WorkflowSpec? {
        val obj = json as? JsonObject ?: return null
        val body = obj["body"]
        val triggerJson = obj["trigger"] ?: (body as? JsonObject)?.get("trigger")
        val trigger = if (triggerJson == null || triggerJson is JsonNull) null else parseTrigger(triggerJson)
            ?: return null
        val step = parseStep(json) ?: return null
        return WorkflowSpec(trigger = trigger, step = step)
    }

    // ─── Trigger decoding (05 §9) ───────────────────────────────────────

    private fun parseTrigger(json: JsonElement): Trigger? {
        val obj = json as? JsonObject ?: return null
        return when (val type = asString(obj["type"])) {
            "manual" -> Trigger.Manual(
                source = asString(obj["source"]),
                inputs = asStringList(obj["inputs"])
            )
            "event" -> {
                val filter = obj["filter"] as? JsonObject ?: return null
                Trigger.Event(
                    filter = filter,
                    resolveMemory = when (val rm = asString(obj["resolveMemory"])) {
                        null, "arm" -> MemoryResolution.ARM
                        "fire" -> MemoryResolution.FIRE
                        else -> return null
                    }
                )
            }
            "schedule" -> {
                val cron = asString(obj["cron"]) ?: return null
                val tz = asString(obj["tz"]) ?: return null
                val misfirePolicy = asString(obj["misfirePolicy"]) ?: "skip"
                if (misfirePolicy !in setOf("skip", "fire-and-forget", "fire-and-forget-if-window")) {
                    return null
                }
                Trigger.Schedule(cron = cron, tz = tz, misfirePolicy = misfirePolicy)
            }
            else -> null
        }
    }

    private fun asStringList(json: JsonElement?): List<String> {
        val arr = json as? JsonArray ?: return emptyList()
        return arr.mapNotNull { asString(it) }
    }

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
            args = obj["args"] as? JsonObject ?: JsonObject(emptyMap()),
            requiresDevices = asStringList(obj["requiresDevices"])
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
