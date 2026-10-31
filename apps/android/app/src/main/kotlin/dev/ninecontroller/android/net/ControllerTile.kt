package dev.ninecontroller.android.net

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ninecontroller.android.MainActivity
import dev.ninecontroller.android.NineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap in the notification shade turns the phone into the Bluetooth
 * controller (or off again) without opening the app. Without Bluetooth permission it opens the app,
 * which asks for it.
 */
class ControllerTile : TileService() {
    private val app get() = application as NineApp

    override fun onStartListening() = refresh()

    override fun onClick() {
        val hid = app.hid
        if (!hid.active && hid.unavailableReason() != null) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        val on = !hid.active
        if (on) hid.start() else hid.stop()
        CoroutineScope(Dispatchers.IO).launch { app.settings.update { it.copy(hidMode = on) } }
        refresh(on)
    }

    private fun refresh(on: Boolean = app.hid.active) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Controller"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Xbox · on" else "Off"
        t.updateTile()
    }
}
