package dev.ninecontroller.android.ui.controller

import android.app.Activity
import android.app.ActivityManager

/**
 * Controller Lock: Android screen pinning. While pinned, the notification shade, home and recents
 * can't be opened by a stray swipe in the middle of a game. Android asks the player to confirm the
 * first time (an app can't pin itself silently); the player unlocks by holding the lock pill, or
 * with the system's own unpin gesture.
 */
object ControllerLock {
    fun isLocked(a: Activity): Boolean =
        a.getSystemService(ActivityManager::class.java)?.lockTaskModeState?.let { it != ActivityManager.LOCK_TASK_MODE_NONE } ?: false

    fun lock(a: Activity) { if (!isLocked(a)) runCatching { a.startLockTask() }.onFailure { android.util.Log.w(TAG, "lock: $it") } }

    fun unlock(a: Activity) {
        android.util.Log.i(TAG, "unlock requested, locked=${isLocked(a)}")
        if (isLocked(a)) runCatching { a.stopLockTask() }.onFailure { android.util.Log.w(TAG, "unlock: $it") }
    }

    private const val TAG = "C9Lock"
}
