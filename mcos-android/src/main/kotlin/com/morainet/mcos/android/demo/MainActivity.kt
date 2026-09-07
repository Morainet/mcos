package com.morainet.mcos.android.demo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // Draw behind the status/navigation bars (full-screen immersive). The
        // shell is a fixed light theme, so both bars use dark icons over a
        // transparent scrim; the Compose layer insets its content via the
        // Scaffold's safeDrawing window insets (see MCOSApp).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MCOSApp((application as McosApplication).deps)
        }
    }
}
