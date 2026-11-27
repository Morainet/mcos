package com.morainet.mcos.android.demo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.CompositionRoot
import com.morainet.mcos.android.demo.shell.MCOSApp

/**
 * Shell activity. Construction lives in [CompositionRoot] (owned now by
 * [McosApplication] for the process lifetime, so a schedule alarm can fire
 * headlessly — 10 §6); state and orchestration live in [com.morainet.mcos.android.demo.shell.McosViewModel]. This
 * class only binds the process-lifetime [AppDeps] into the Compose tree; the
 * Compose layer re-attaches the activity-result launcher on each create.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // The splash starts light. The app applies the saved appearance as its content loads.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val preferences = getPreferences(MODE_PRIVATE)
        var darkTheme by mutableStateOf(preferences.getBoolean(DARK_THEME_KEY, false))
        if (darkTheme) window.setBackgroundDrawableResource(R.color.mcos_window_bg_dark)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val updateSystemBars = {
            bars.isAppearanceLightStatusBars = !darkTheme
            bars.isAppearanceLightNavigationBars = !darkTheme
        }
        // SplashScreen restores the window theme's system-bar appearance when it removes its view.
        splashScreen.setOnExitAnimationListener { splash -> splash.animateToWorkspace(updateSystemBars) }
        setContent {
            SideEffect(updateSystemBars)
            MCOSApp((application as McosApplication).deps, darkTheme = darkTheme) { dark ->
                preferences.edit().putBoolean(DARK_THEME_KEY, dark).apply()
                window.setBackgroundDrawableResource(
                    if (dark) R.color.mcos_window_bg_dark else R.color.mcos_window_bg
                )
                darkTheme = dark
            }
        }
    }

    private companion object {
        const val DARK_THEME_KEY = "dark_theme"
    }
}
