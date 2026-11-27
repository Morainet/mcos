package com.morainet.mcos.android.demo.records

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.util.AtomicFile
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.morainet.mcos.android.demo.R
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.sdk.CommandResult
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RecordExportActivity : ComponentActivity() {
    private lateinit var output: TextView
    private lateinit var export: Button
    private lateinit var missing: Button
    private lateinit var exportEntered: Button
    private lateinit var findLatest: Button
    private lateinit var exportLocated: Button
    private lateinit var lookupOutput: TextView
    private var locatedId: String? = null
    private var runId: String? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(32, 48, 32, 32)
        }
        column.addView(TextView(this).apply {
            text = "Export an app record\n\nRecord demo-001: Site inspection, completed.\n" +
                "Preview → confirmation → private file → result and audit."
            textSize = 20f
        })
        export = Button(this).apply { text = "Preview and export record"; setOnClickListener { execute("demo-001") } }
        missing = Button(this).apply { text = "Try a missing record"; setOnClickListener { execute("missing") } }
        column.addView(export)
        column.addView(missing)
        findLatest = Button(this).apply {
            setText(R.string.find_latest_record)
            setOnClickListener { locateLatest() }
        }
        lookupOutput = TextView(this)
        exportLocated = Button(this).apply {
            setText(R.string.export_located_record)
            isEnabled = false
            setOnClickListener { locatedId?.let(::execute) }
        }
        column.addView(findLatest)
        column.addView(lookupOutput)
        column.addView(exportLocated)
        val recordId = EditText(this).apply {
            id = R.id.record_id
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        column.addView(TextView(this).apply {
            setText(R.string.record_id_label)
            labelFor = recordId.id
        })
        column.addView(recordId)
        exportEntered = Button(this).apply {
            setText(R.string.export_entered_record)
            setOnClickListener {
                val enteredId = recordId.text.toString()
                if (enteredId.isBlank()) {
                    recordId.error = getString(R.string.record_id_required)
                } else {
                    execute(enteredId)
                }
            }
        }
        column.addView(exportEntered)
        column.addView(Button(this).apply {
            text = "Cancel running task"
            setOnClickListener {
                lifecycleScope.launch {
                    val host = RecordDebugHost.get(application).await()
                    runId?.let(host.runtime::cancel)
                    dialog?.dismiss()
                }
            }
        })
        output = TextView(this).apply { textSize = 16f }
        column.addView(output)
        setContentView(ScrollView(this).apply { addView(column) })
        if (savedInstanceState == null) column.requestFocus()
    }

    private fun setBusy(busy: Boolean) {
        export.isEnabled = !busy
        missing.isEnabled = !busy
        exportEntered.isEnabled = !busy
        findLatest.isEnabled = !busy
        exportLocated.isEnabled = !busy && locatedId != null
    }

    private fun locateLatest() {
        locatedId = null
        lookupOutput.text = ""
        setBusy(true)
        lifecycleScope.launch {
            try {
                val runtime = RecordDebugHost.get(application).await().runtime
                val result = runtime.executeProbe(listOf(Command("record.latest_completed", JsonObject(emptyMap()))))
                    .single()
                lookupOutput.text = when (result) {
                    is CommandResult.Ok -> {
                        val id = (result.value as JsonPrimitive).content
                        locatedId = id
                        getString(R.string.latest_record_result, id, UUID.randomUUID().toString())
                    }
                    is CommandResult.Err -> getString(R.string.latest_record_failure, result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lookupOutput.text = getString(R.string.latest_record_failure, failure.message.orEmpty())
            } finally {
                setBusy(false)
            }
        }
    }

    private fun execute(id: String) {
        setBusy(true)
        output.text = ""
        lifecycleScope.launch {
            var artifact: File? = null
            try {
                val host = RecordDebugHost.get(application).await()
                val runtime = host.runtime
                val request = ExecuteRequest(Source.API, Payload.DslText("record.export(id=${JsonPrimitive(id)})"))
                val preview = runtime.preview(request)
                output.append("Plan: ${preview.commands.joinToString { it.id + it.args }}\n")
                val handle = runtime.execute(request)
                runId = handle.runId
                output.append("Run: ${handle.runId}\n")
                runtime.observe(handle.runId).collect { event ->
                    when (event) {
                        is RuntimeEvent.ConfirmationNeeded -> {
                            dialog = AlertDialog.Builder(this@RecordExportActivity)
                                .setTitle("Export this record?")
                                .setMessage(getString(R.string.export_confirmation_message, id, event.runId))
                                .setPositiveButton("Export") { _, _ ->
                                    lifecycleScope.launch {
                                        runtime.respondConfirmation(event.runId, event.commandId, ConfirmationDecision.Approve())
                                    }
                                }
                                .setNegativeButton("Reject") { _, _ ->
                                    lifecycleScope.launch {
                                        runtime.respondConfirmation(event.runId, event.commandId, ConfirmationDecision.Reject)
                                    }
                                }
                                .setNeutralButton("Cancel task") { _, _ -> runtime.cancel(event.runId) }
                                .setCancelable(false).show()
                        }
                        is RuntimeEvent.ArtifactEmitted -> artifact = File(URI(event.uri))
                        is RuntimeEvent.RunSucceeded -> {
                            val file = checkNotNull(artifact) { "Export completed without a file" }
                            val content = withContext(Dispatchers.IO) { file.readText() }
                            saveExportResult(event.runId, "succeeded", file = file.name, recordId = id)
                            output.append("Export completed.\n")
                            output.append("File: ${file.name}\n$content\n")
                        }
                        is RuntimeEvent.RunFailed -> {
                            saveExportResult(event.runId, "failed", error = event.error)
                            output.append("Task failed: ${event.error}\n")
                        }
                        is RuntimeEvent.RunCancelled -> {
                            saveExportResult(event.runId, "cancelled")
                            output.append("Task cancelled.\n")
                        }
                        else -> Unit
                    }
                }
                host.auditLog.flush()
                output.append("Audit records: ${host.auditLog.count()}\n")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                output.append("Unable to complete task: ${failure.message}\n")
            } finally {
                dialog?.dismiss()
                runId = null
                setBusy(false)
            }
        }
    }

    private suspend fun saveExportResult(
        runId: String,
        status: String,
        file: String? = null,
        error: String? = null,
        recordId: String? = null,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val receipt = buildJsonObject {
            put("version", 1)
            put("runId", runId)
            put("status", status)
            file?.let { put("file", it) }
            error?.let { put("error", it.take(512)) }
            recordId?.let { put("recordId", it) }
        }
        val directory = File(filesDir, "export-results")
        val target = AtomicFile(File(directory, "$runId.json"))
        val output = target.startWrite()
        try {
            output.write(receipt.toString().encodeToByteArray())
            target.finishWrite(output)
        } catch (failure: Exception) {
            target.failWrite(output)
            throw failure
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        // A disappearing host UI cannot leave an approval/run orphaned.
        runId?.let { RecordDebugHost.cancelRun(application, it) }
        super.onDestroy()
    }
}
