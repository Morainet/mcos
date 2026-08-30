package com.morainet.mcos.android.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.CompositionRoot

/**
 * Shell activity. Construction lives in [CompositionRoot] (owned now by
 * [McosApplication] for the process lifetime, so a schedule alarm can fire
 * headlessly — 10 §6); state and orchestration live in [McosViewModel]. This
 * class only binds the process-lifetime [AppDeps] into the Compose tree; the
 * Compose layer re-attaches the activity-result launcher on each create.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MCOSApp((application as McosApplication).deps)
        }
    }
}
