package com.morainet.mcos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Shell activity. All construction lives in [CompositionRoot] and all state
 * and orchestration live in [McosViewModel]; this class only wires them
 * together (architecture review #8).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deps = CompositionRoot.create(this)

        setContent {
            MCOSApp(deps)
        }
    }
}
