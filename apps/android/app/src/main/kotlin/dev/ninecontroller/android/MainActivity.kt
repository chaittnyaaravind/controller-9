package dev.ninecontroller.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ninecontroller.android.ui.controller.ControllerScreen
import dev.ninecontroller.android.ui.diagnostics.ConnectionScreen
import dev.ninecontroller.android.ui.diagnostics.DiagnosticsScreen
import dev.ninecontroller.android.ui.editor.LayoutEditorScreen
import dev.ninecontroller.android.ui.home.HomeScreen
import dev.ninecontroller.android.ui.motion.MotionScreen
import dev.ninecontroller.android.ui.pair.AddHostScreen
import dev.ninecontroller.android.ui.profiles.ProfilesScreen
import dev.ninecontroller.android.ui.settings.SettingsScreen
import dev.ninecontroller.android.ui.theme.NineTheme

class MainActivity : ComponentActivity() {
    /** Debug builds only: tools/direct-bt-check starts the Bluetooth controller mode (and its sweep). */
    private fun debugHook(i: android.content.Intent?) {
        if (BuildConfig.DEBUG && i?.getBooleanExtra("c9_hid", false) == true) HidSweep.start(application as NineApp, i.getBooleanExtra("c9_hid_sweep", false))
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); debugHook(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NineApp
        debugHook(intent)
        setContent {
            NineTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") { HomeScreen(app, nav) }
                    composable("add") { AddHostScreen(app, nav) }
                    composable("play") { ControllerScreen(app, nav) }
                    composable("profiles") { ProfilesScreen(app, nav) }
                    composable("edit/{id}") { LayoutEditorScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("motion/{id}") { MotionScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("diagnostics") { DiagnosticsScreen(app, nav) }
                    composable("connection") { ConnectionScreen(app, nav) }
                    composable("settings") { SettingsScreen(app, nav) }
                    composable("layouts") { dev.ninecontroller.android.ui.layouts.LayoutsScreen(app, nav) }
                    composable("layout/{id}") { dev.ninecontroller.android.ui.layouts.LayoutDetailScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("calibrate") { dev.ninecontroller.android.ui.calibration.CalibrationScreen(app, nav) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: release every control so nothing stays held in the game.
        (application as NineApp).connection.setPaused(true)
    }

    override fun onStart() {
        super.onStart()
        (application as NineApp).connection.setPaused(false)
    }
}
