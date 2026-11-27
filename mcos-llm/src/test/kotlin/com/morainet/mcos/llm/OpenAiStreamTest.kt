package com.morainet.mcos.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiStreamTest {
    @Test
    fun `tool arguments are joined only after completion and validated`() {
        val stream = OpenAiStream()
        stream.receive(choice(
            """{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"photo.enhance","arguments":"{"}}]}}""",
        ))
        assertFailsWith<LlmTransportException> { stream.result() }
        stream.receive(choice(
            """{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"strength\":2}"}}]}}""",
        ))
        stream.receive(choice("""{"delta":{},"finish_reason":"tool_calls"}"""))
        stream.markDone()

        val call = stream.result().toolCalls.single()
        assertEquals("photo.enhance", call.command)
        assertEquals("""{"strength":2}""", call.args.toString())
    }

    @Test
    fun `a truncated tool response is not executable`() {
        val stream = OpenAiStream()
        stream.receive(choice(
            """{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"photo.enhance","arguments":"{}"}}]},"finish_reason":"length"}""",
        ))
        stream.markDone()
        assertFailsWith<LlmTransportException> { stream.result() }
    }

    private fun choice(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
