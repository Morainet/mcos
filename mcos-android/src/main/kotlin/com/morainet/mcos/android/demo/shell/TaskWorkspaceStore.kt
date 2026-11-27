package com.morainet.mcos.android.demo.shell

import com.morainet.mcos.sdk.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val FIRST_TASK_ID = "main"
internal const val TASK_TITLE_LIMIT = 100

internal data class SavedTask(
    val id: String,
    val title: String = "",
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val outcome: String? = null,
    val resumeAtMs: Long? = null,
    val unfinished: Boolean = false,
) {
    val visibleInHistory: Boolean
        get() = draft.isNotBlank() || messages.isNotEmpty() || resumeAtMs != null

    fun matches(query: String): Boolean = title.contains(query, ignoreCase = true) ||
        draft.contains(query, ignoreCase = true) ||
        outcome?.contains(query, ignoreCase = true) == true ||
        messages.any { it.text.contains(query, ignoreCase = true) }
}

internal data class TaskWorkspace(
    val activeId: String = FIRST_TASK_ID,
    val tasks: List<SavedTask> = listOf(SavedTask(FIRST_TASK_ID)),
) {
    fun withTask(task: SavedTask): TaskWorkspace = copy(tasks = listOf(task) + tasks.filterNot { it.id == task.id })
}

/** One private, encrypted snapshot for task order, selection, drafts and the transcript. */
internal class TaskWorkspaceStore(private val secureStore: SecureStore) {
    suspend fun load(): TaskWorkspace? {
        val bytes = secureStore.get(KEY) ?: return null
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(root["version"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported task history version" }
        val tasks = root.getValue("tasks").jsonArray.map { entry -> decodeTask(entry.jsonObject) }
        val activeId = root.getValue("activeId").jsonPrimitive.content
        require(tasks.map { it.id }.toSet().size == tasks.size && tasks.any { it.id == activeId }) {
            "Invalid task history"
        }
        return TaskWorkspace(activeId, tasks)
    }

    suspend fun save(workspace: TaskWorkspace) {
        val snapshot = buildJsonObject {
            put("version", 1)
            put("activeId", workspace.activeId)
            put("tasks", buildJsonArray { workspace.tasks.forEach { add(encodeTask(it)) } })
        }
        secureStore.put(KEY, snapshot.toString().encodeToByteArray())
    }

    private fun decodeTask(entry: JsonObject): SavedTask = SavedTask(
        id = entry.getValue("id").jsonPrimitive.content,
        title = entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        draft = entry["draft"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        messages = entry.getValue("messages").jsonArray.map { value ->
            val message = value.jsonObject
            ChatMessage(
                id = requireNotNull(message.getValue("id").jsonPrimitive.longOrNull),
                role = ChatRole.valueOf(message.getValue("role").jsonPrimitive.content),
                text = message.getValue("text").jsonPrimitive.content,
            )
        },
        outcome = entry["outcome"]?.jsonPrimitive?.contentOrNull,
        resumeAtMs = entry["resumeAtMs"]?.jsonPrimitive?.longOrNull,
        unfinished = entry["unfinished"]?.jsonPrimitive?.booleanOrNull == true,
    )

    private fun encodeTask(task: SavedTask): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("draft", task.draft)
        task.outcome?.let { put("outcome", it) }
        task.resumeAtMs?.let { put("resumeAtMs", it) }
        put("unfinished", task.unfinished)
        put("messages", buildJsonArray {
            task.messages.forEach { message ->
                add(buildJsonObject {
                    put("id", message.id)
                    put("role", message.role.name)
                    put("text", message.text)
                })
            }
        })
    }

    private companion object {
        const val KEY = "agent_task_workspace"
    }
}
