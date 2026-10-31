package dev.ninecontroller.android.ui.controller

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.ninecontroller.android.NineApp
import kotlinx.coroutines.launch

/**
 * The app *is* an Xbox controller: opening the controller screen turns on Bluetooth controller mode
 * (the phone pairs with the computer as "Xbox Wireless Controller", no app on the computer). Returns a
 * function that does every step it needs — Bluetooth permissions, turning Bluetooth on, the "controller
 * is on" notification permission — then starts the mode and remembers it. Same steps as Home's "Turn on".
 */
@Composable
fun rememberXboxStart(app: NineApp): () -> Unit {
    val scope = rememberCoroutineScope()
    fun start() {
        if (app.hid.unavailableReason() != null) { app.hid.start(); return } // shows the reason, not remembered
        app.hid.start(); scope.launch { app.settings.update { it.copy(hidMode = true) } }
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) start()
    }
    lateinit var go: () -> Unit
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) go()
    }
    go = {
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        when {
            app.hid.active -> Unit
            Build.VERSION.SDK_INT >= 31 && app.hid.unavailableReason()?.contains("permission", ignoreCase = true) == true ->
                perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
            adapter != null && runCatching { !adapter.isEnabled }.getOrDefault(false) ->
                enableBt.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    perms.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) // the controller works without it
                start()
            }
        }
    }
    return go
}
