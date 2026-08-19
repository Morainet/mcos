package com.morainet.mcos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Shell activity. All construction lives in [CompositionRoot] and all state
 * and orchestration live in [McosViewModel]; this class only wires them
 * together (architecture review #8).
 *
 * The activity owns its [AppDeps] lifecycle: [onDestroy] stops the audit
 * writer. The JSONL trail itself is file-backed, so the next instance
 * (configuration change or relaunch) replays and continues the same log.
 */
class MainActivity : ComponentActivity() {

    private var deps: AppDeps? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val built = CompositionRoot.create(this)
        deps = built

        setContent {
            MCOSApp(built)
        }
    }

    override fun onDestroy() {
        deps?.auditLog?.stop()
        deps = null
        super.onDestroy()
    }
}
