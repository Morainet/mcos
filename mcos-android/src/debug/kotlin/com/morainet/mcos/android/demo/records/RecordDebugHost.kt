package com.morainet.mcos.android.demo.records

import android.content.Context
import com.morainet.mcos.android.CompositionRoot
import com.morainet.mcos.android.EmbeddedRuntime
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

internal object RecordDebugHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: Deferred<EmbeddedRuntime>? = null

    fun cancelRun(context: Context, runId: String) {
        scope.launch { get(context).await().runtime.cancel(runId) }
    }

    @Synchronized
    fun get(context: Context): Deferred<EmbeddedRuntime> {
        val appContext = context.applicationContext
        return host ?: scope.async {
            CompositionRoot.createEmbedded(
                appContext,
                listOf(RecordExportPlugin(File(appContext.filesDir, "record-exports"))),
            )
        }.also { host = it }
    }
}
