package dev.ninecontroller.android

import android.util.Log
import dev.ninecontroller.android.net.HidGamepad
import dev.ninecontroller.protocol.AXIS_MAX
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.TRIGGER_MAX

/**
 * Debug-only test hook for tools/direct-bt-check (debug builds only, see MainActivity): starts the
 * Bluetooth controller mode and, with `sweep`, plays every button, stick direction and trigger in a
 * fixed order once a computer subscribes, so the Mac-side probe can verify each one without a person.
 */
object HidSweep {
    private const val TAG = "C9HidSweep"

    fun start(app: NineApp, sweep: Boolean) {
        app.connection.disconnect()
        app.hid.start()
        if (!sweep) return
        Thread({
            while (app.hid.state.value.let { it !is HidGamepad.State.Connected || !it.subscribed }) {
                if (app.hid.state.value is HidGamepad.State.Failed) { Log.w(TAG, "mode failed: ${app.hid.state.value}"); return@Thread }
                Thread.sleep(200)
            }
            Thread.sleep(3000) // let the computer finish setting up the gamepad
            fun hold(name: String, s: InputState) {
                Log.i(TAG, "step $name"); app.hid.submit(s); Thread.sleep(250)
                app.hid.submit(InputState.NEUTRAL); Thread.sleep(250)
            }
            for (round in 1..2) {
                for (b in Button.entries) hold(b.id, InputState(buttons = b.mask))
                hold("lx+", InputState(lx = AXIS_MAX)); hold("lx-", InputState(lx = -AXIS_MAX))
                hold("ly+", InputState(ly = AXIS_MAX)); hold("ly-", InputState(ly = -AXIS_MAX))
                hold("rx+", InputState(rx = AXIS_MAX)); hold("rx-", InputState(rx = -AXIS_MAX))
                hold("ry+", InputState(ry = AXIS_MAX)); hold("ry-", InputState(ry = -AXIS_MAX))
                hold("lt", InputState(lt = TRIGGER_MAX)); hold("rt", InputState(rt = TRIGGER_MAX))
            }
            Log.i(TAG, "sweep done, reports sent ${app.hid.reportsSent}")
        }, "c9-hid-sweep").apply { isDaemon = true }.start()
    }
}
