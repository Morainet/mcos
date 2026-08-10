package com.mcos.runtime.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * IR node types for the execution engine.
 * Matches [02-command-protocol.md §7], [03-runtime.md §5.1].
 */

/** A single command invocation */
@Serializable
data class IrInvoke(
    val dslVersion: String = "0.1",
    val type: String = "invoke",
    val id: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val meta: JsonObject? = null
)

/** Sequential list of invocations */
@Serializable
data class IrSequence(
    val dslVersion: String = "0.1",
    val type: String = "sequence",
    val steps: List<IrInvoke> = emptyList()
)

/** Sealed class representing any valid execution IR */
sealed class ExecutionIr {
    data class Invoke(val invoke: IrInvoke) : ExecutionIr()
    data class Sequence(val sequence: IrSequence) : ExecutionIr()
    data class Workflow(val body: JsonElement) : ExecutionIr()
}

/** Parsed result: either a valid ExecutionIr or a parse error */
sealed class ParseResult {
    data class Ok(val ir: ExecutionIr) : ParseResult()
    data class Err(
        val code: String,
        val message: String,
        val line: Int = 1,
        val column: Int = 1,
        val reason: String? = null,
        val token: String? = null,
        val expected: List<String>? = null
    ) : ParseResult()
}
