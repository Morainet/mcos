package com.morainet.mcos.sample.embedded

import android.app.Application
import com.morainet.mcos.android.CompositionRoot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

class SampleApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun cancelRun(runId: String) {
        scope.launch { host.await().runtime.cancel(runId) }
    }

    val host by lazy {
        scope.async {
            CompositionRoot.createEmbedded(
                this@SampleApplication,
                listOf(RecordExportPlugin(File(filesDir, "record-exports"))),
            )
        }
    }
}
