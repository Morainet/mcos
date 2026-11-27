package com.morainet.mcos.sample.embedded

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var output: TextView
    private lateinit var export: Button
    private lateinit var missing: Button
    private var runId: String? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        column.addView(Button(this).apply {
            text = "Cancel running task"
            setOnClickListener {
                lifecycleScope.launch {
                    val host = (application as SampleApplication).host.await()
                    runId?.let(host.runtime::cancel)
                    dialog?.dismiss()
                }
            }
        })
        output = TextView(this).apply { textSize = 16f }
        column.addView(output)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun execute(id: String) {
        export.isEnabled = false
        missing.isEnabled = false
        output.text = ""
        lifecycleScope.launch {
            var artifact: File? = null
            try {
                val host = (application as SampleApplication).host.await()
                val runtime = host.runtime
                val request = ExecuteRequest(Source.API, Payload.DslText("record.export(id=\"$id\")"))
                val preview = runtime.preview(request)
                output.append("Plan: ${preview.commands.joinToString { it.id + it.args }}\n")
                val handle = runtime.execute(request)
                runId = handle.runId
                output.append("Run: ${handle.runId}\n")
                runtime.observe(handle.runId).collect { event ->
                    when (event) {
                        is RuntimeEvent.ConfirmationNeeded -> {
                            dialog = AlertDialog.Builder(this@MainActivity)
                                .setTitle("Export this record?")
                                .setMessage("Creates a text file in this app's private storage.")
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
                            output.append("Export completed.\n")
                            artifact?.let { output.append("File: ${it.name}\n${it.readText()}\n") }
                        }
                        is RuntimeEvent.RunFailed -> output.append("Task failed: ${event.error}\n")
                        is RuntimeEvent.RunCancelled -> output.append("Task cancelled.\n")
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
                export.isEnabled = true
                missing.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        // A disappearing host UI cannot leave an approval/run orphaned.
        runId?.let { (application as SampleApplication).cancelRun(it) }
        super.onDestroy()
    }
}
