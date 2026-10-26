package dev.ninecontroller.android.ui.controller

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Do Not Disturb while playing: on the controller screen only alarms may interrupt, so a message
 * banner can't cover the pad. The player grants "Do Not Disturb access" once in Android settings.
 *
 * The filter that was active before is saved first and put back when the controller closes; if the
 * app is killed mid-game, [restoreIfLeftOn] puts it back at the next launch, so the phone is never
 * left silenced by us.
 */
object ControllerQuiet {
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun prefs(c: Context) = c.getSharedPreferences("quiet", Context.MODE_PRIVATE)

    fun allowed(c: Context) = nm(c)?.isNotificationPolicyAccessGranted == true

    /** Opens the system page where the player grants Do Not Disturb access. */
    fun requestAccess(c: Context) = c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun quiet(c: Context) {
        val n = nm(c) ?: return
        if (!allowed(c) || prefs(c).contains("previous")) return
        prefs(c).edit().putInt("previous", n.currentInterruptionFilter).commit()
        runCatching { n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
    }

    fun restore(c: Context) {
        val p = prefs(c)
        if (!p.contains("previous")) return
        val previous = p.getInt("previous", NotificationManager.INTERRUPTION_FILTER_ALL)
        if (allowed(c)) runCatching { nm(c)?.setInterruptionFilter(previous) }
        p.edit().remove("previous").apply()
    }

    fun restoreIfLeftOn(c: Context) = restore(c)
}
