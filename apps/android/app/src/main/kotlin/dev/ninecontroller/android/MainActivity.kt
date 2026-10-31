package dev.ninecontroller.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        if (BuildConfig.DEBUG && i?.getBooleanExtra("c9_remote", false) == true) HidSweep.remote(application as NineApp)
        if (BuildConfig.DEBUG) i?.getStringExtra("c9_bond")?.let { HidSweep.bond(application as NineApp, it) } // TV-emulator host test
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); debugHook(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NineApp
        debugHook(intent)
        setContent {
            NineTheme {
                // The game being played lights every screen (ui.theme.Accent).
                val accent = app.settings.flow.collectAsState(null).value?.accent
                androidx.compose.runtime.CompositionLocalProvider(dev.ninecontroller.android.ui.theme.LocalAccent provides dev.ninecontroller.android.ui.theme.Accent.of(accent)) {
                val nav = rememberNavController()
                // A launcher shortcut ("Play Asphalt Legends"): make that game current and open its controller.
                val shortcutGame = intent?.getStringExtra(dev.ninecontroller.android.data.GameShortcuts.EXTRA_GAME)
                var shortcutDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(shortcutGame) {
                    if (shortcutDone) return@LaunchedEffect // not again after the activity is restored
                    val g = shortcutGame?.let { app.games.get(it) } ?: return@LaunchedEffect
                    shortcutDone = true
                    app.play(g); nav.navigate("play")
                }
                val reduced = dev.ninecontroller.android.ui.theme.LocalReducedMotion.current
                NavHost(nav, startDestination = "home",
                    enterTransition = dev.ninecontroller.android.ui.theme.NineTransitions.enter(reduced), exitTransition = dev.ninecontroller.android.ui.theme.NineTransitions.exit(),
                    popEnterTransition = dev.ninecontroller.android.ui.theme.NineTransitions.enter(true), popExitTransition = dev.ninecontroller.android.ui.theme.NineTransitions.exit()) {
                    composable("home") { HomeScreen(app, nav) }
                    composable("add") { AddHostScreen(app, nav) }
                    composable("play") { ControllerScreen(app, nav) }
                    composable("profiles") { ProfilesScreen(app, nav) }
                    composable("edit/{id}") { LayoutEditorScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("motion/{id}") { MotionScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("diagnostics") { DiagnosticsScreen(app, nav) }
                    composable("doctor") { dev.ninecontroller.android.ui.diagnostics.DoctorScreen(app, nav) }
                    composable("connection") { ConnectionScreen(app, nav) }
                    composable("settings") { SettingsScreen(app, nav) }
                    composable("layouts") { dev.ninecontroller.android.ui.layouts.LayoutsScreen(app, nav) }
                    composable("layout/{id}") { dev.ninecontroller.android.ui.layouts.LayoutDetailScreen(app, nav, it.arguments?.getString("id") ?: "") }
                    composable("calibrate") { dev.ninecontroller.android.ui.calibration.CalibrationScreen(app, nav) }
                    composable("game/{id}") { dev.ninecontroller.android.ui.games.GameScreen(app, nav, it.arguments?.getString("id") ?: "new") }
                    composable("remote") { dev.ninecontroller.android.ui.remote.RemoteScreen(app, nav) }
                    composable("games/scan") { dev.ninecontroller.android.ui.games.ScanGameScreen(app, nav) }
                }
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
