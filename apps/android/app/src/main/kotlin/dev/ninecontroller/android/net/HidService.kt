package dev.ninecontroller.android.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.ninecontroller.android.MainActivity
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.R

/**
 * Keeps the Bluetooth controller registered while the app's screen is closed (docs/DIRECT_BLUETOOTH.md).
 * macOS caches the phone's GATT layout and cannot follow a change (Android's Service Changed has no
 * client configuration descriptor), so the controller must not disappear just because the UI did.
 * Holds no logic of its own: [HidGamepad] does the work; this only keeps the process in the foreground.
 */
class HidService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { (application as NineApp).hid.stop(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bluetooth controller", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HidService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.c9_mark_small)
            .setContentTitle("Bluetooth controller is on")
            .setContentText("Your computer sees this phone as a game controller.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        }.onFailure { Log.w("C9Hid", "foreground service refused: $it") }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "hid"
        private const val ID = 9
        private const val ACTION_STOP = "dev.ninecontroller.android.HID_STOP"

        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, HidService::class.java)) }
            .onFailure { Log.w("C9Hid", "could not start the foreground service: $it") }

        fun stop(c: Context) { c.stopService(Intent(c, HidService::class.java)) }
    }
}
