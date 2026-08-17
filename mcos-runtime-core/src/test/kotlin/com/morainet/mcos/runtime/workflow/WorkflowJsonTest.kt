package com.morainet.mcos.runtime.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.*

/**
 * Tests for [WorkflowJson] — hand-written JSON decoding of [WorkflowStep].
 */
class WorkflowJsonTest {

    private fun parse(text: String): WorkflowStep? =
        WorkflowJson.fromJson(Json.parseToJsonElement(text))

    // ─── Command ─────────────────────────────────────────────────────────

    @Test
    fun `parse command step`() {
        val step = parse(
            """{"type":"command","commandId":"camera.capture","args":{"quality":"high"}}"""
        )

        val command = step as? WorkflowStep.Command
        assertNotNull(command)
        assertEquals("camera.capture", command.commandId)
        assertEquals("high", command.args["quality"]?.let { it.toString().trim('"') })
    }

    @Test
    fun `command without args defaults to empty`() {
        val step = parse("""{"type":"command","commandId":"sys.notify"}""")

        val command = step as? WorkflowStep.Command
        assertNotNull(command)
        assertTrue(command.args.isEmpty())
    }

    // ─── Composite steps ─────────────────────────────────────────────────

    @Test
    fun `parse sequential with nested steps`() {
        val step = parse(
            """
            {
              "type": "sequential",
              "steps": [
                {"type": "command", "commandId": "a"},
                {"type": "command", "commandId": "b"}
              ]
            }
            """.trimIndent()
        )

        val seq = step as? WorkflowStep.Sequential
        assertNotNull(seq)
        assertEquals(2, seq.steps.size)
        assertTrue(seq.steps[0] is WorkflowStep.Command)
    }

    @Test
    fun `parse parallel step`() {
        val step = parse(
            """
            {
              "type": "parallel",
              "steps": [
                {"type": "command", "commandId": "a"},
                {"type": "command", "commandId": "b"}
              ]
            }
            """.trimIndent()
        )

        val par = step as? WorkflowStep.Parallel
        assertNotNull(par)
        assertEquals(2, par.steps.size)
    }

    @Test
    fun `parse if step with else branch`() {
        val step = parse(
            """
            {
              "type": "if",
              "condition": {"type": "based_on_previous", "predicate": "LAST_STEP_SUCCEEDED"},
              "then": {"type": "command", "commandId": "a"},
              "else": {"type": "command", "commandId": "b"}
            }
            """.trimIndent()
        )

        val ifStep = step as? WorkflowStep.If
        assertNotNull(ifStep)
        assertTrue(ifStep.condition is WorkflowCondition.BasedOnPrevious)
        assertEquals(WorkflowPredicate.LAST_STEP_SUCCEEDED, ifStep.condition.predicate)
        assertNotNull(ifStep.elseStep)
    }

    @Test
    fun `parse loop step with max iterations`() {
        val step = parse(
            """
            {
              "type": "loop",
              "body": {"type": "command", "commandId": "poll"},
              "condition": {"type": "always", "value": true},
              "maxIterations": 5
            }
            """.trimIndent()
        )

        val loop = step as? WorkflowStep.Loop
        assertNotNull(loop)
        assertEquals(5, loop.maxIterations)
        assertEquals(true, (loop.condition as WorkflowCondition.Always).value)
    }

    @Test
    fun `parse retry step with safety flags`() {
        val step = parse(
            """
            {
              "type": "retry",
              "step": {"type": "command", "commandId": "net.fetch"},
              "maxRetries": 2,
              "backoffMs": 250,
              "idempotent": false,
              "retryOnCodes": ["NETWORK_ERROR"]
            }
            """.trimIndent()
        )

        val retry = step as? WorkflowStep.Retry
        assertNotNull(retry)
        assertEquals(2, retry.maxRetries)
        assertEquals(250L, retry.backoffMs)
        assertEquals(false, retry.idempotent)
        assertEquals(setOf("NETWORK_ERROR"), retry.retryOnCodes)
    }

    @Test
    fun `parse try step with compensation`() {
        val step = parse(
            """
            {
              "type": "try",
              "step": {"type": "command", "commandId": "files.move"},
              "compensation": [
                {"type": "command", "commandId": "files.delete"}
              ]
            }
            """.trimIndent()
        )

        val tryStep = step as? WorkflowStep.Try
        assertNotNull(tryStep)
        assertEquals(1, tryStep.compensation.size)
    }

    @Test
    fun `parse workflow envelope delegates to body`() {
        val step = parse(
            """
            {
              "type": "workflow",
              "body": {
                "type": "sequential",
                "steps": [
                  {"type": "command", "commandId": "a"},
                  {"type": "command", "commandId": "b"}
                ]
              }
            }
            """.trimIndent()
        )

        val seq = step as? WorkflowStep.Sequential
        assertNotNull(seq)
        assertEquals(2, seq.steps.size)
    }

    @Test
    fun `workflow envelope without body returns null`() {
        assertNull(parse("""{"type":"workflow"}"""))
    }

    // ─── Invalid input ───────────────────────────────────────────────────

    @Test
    fun `unknown type returns null`() {
        assertNull(parse("""{"type":"teleport","commandId":"x"}"""))
    }

    @Test
    fun `non-object json returns null`() {
        assertNull(parse("""["a","b"]"""))
    }

    @Test
    fun `command without commandId returns null`() {
        assertNull(parse("""{"type":"command"}"""))
    }

    @Test
    fun `empty sequential returns null`() {
        assertNull(parse("""{"type":"sequential","steps":[]}"""))
    }

    // ─── Builder-based nested workflow ───────────────────────────────────

    @Test
    fun `decode arbitrary nested workflow built with Json DSL`() {
        val json = buildJsonObject {
            put("type", "sequential")
            putJsonArray("steps") {
                add(
                    buildJsonObject {
                        put("type", "command")
                        put("commandId", "camera.capture")
                        putJsonObject("args") {
                            put("quality", "high")
                        }
                    }
                )
                add(
                    buildJsonObject {
                        put("type", "retry")
                        put("maxRetries", 2)
                        putJsonObject("step") {
                            put("type", "command")
                            put("commandId", "photo.compress")
                        }
                    }
                )
                add(
                    buildJsonObject {
                        put("type", "try")
                        putJsonObject("step") {
                            put("type", "command")
                            put("commandId", "sys.notify")
                        }
                        putJsonArray("compensation") {
                            add(
                                buildJsonObject {
                                    put("type", "command")
                                    put("commandId", "log.cleanup")
                                }
                            )
                        }
                    }
                )
            }
        }

        val step = WorkflowJson.fromJson(json) as? WorkflowStep.Sequential
        assertNotNull(step)
        assertEquals(3, step.steps.size)
        assertTrue(step.steps[1] is WorkflowStep.Retry)
        assertTrue(step.steps[2] is WorkflowStep.Try)
    }
}
