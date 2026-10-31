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

    /** Remote keys check (tools/direct-bt-check/remote-check.sh): pointer 400 px right, then Volume Up. */
    fun remote(app: NineApp) = Thread({
        Log.i(TAG, "remote: ready=${app.hid.remoteReady}")
        repeat(10) { app.hid.sendMouse(0, 40, 0); Thread.sleep(30) }
        Thread.sleep(500)
        app.hid.tapConsumer(dev.ninecontroller.android.input.RemoteHidReport.VOLUME_UP)
        Log.i(TAG, "remote: sent pointer + volume up")
    }, "c9-remote-check").apply { isDaemon = true }.start()

    /**
     * Simulation test only (tools/tv-sim): run on an Android TV *emulator* acting as the host. Its accessory
     * screen can't see BLE devices in the emulator (the virtual controller rejects the scan filter it uses), so
     * this scans without filters for [name] and bonds; Android's own HID host then takes over, as on a real TV.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun bond(app: NineApp, name: String) {
        val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        val scanner = adapter.bluetoothLeScanner ?: run { Log.w(TAG, "bond: no LE scanner"); return }
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                if (r.device.name != name && r.scanRecord?.deviceName != name) return
                scanner.stopScan(this)
                Log.i(TAG, "bond: found $name, type=${r.device.type}, bonding")
                Log.i(TAG, "bond: createBond=${r.device.createBond()}")
            }
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "bond: scan failed $errorCode") }
        }
        Log.i(TAG, "bond: scanning for $name")
        scanner.startScan(null, android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    fun start(app: NineApp, sweep: Boolean) {
        Log.i(TAG, "hook: sweep=$sweep state=${app.hid.state.value}")
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
                // Not GUIDE: macOS takes the Xbox button for its game overlay and holds back controller
                // input from apps for a few seconds after it (measured 2026-10-25), hiding later steps.
                for (b in Button.entries) if (b != Button.GUIDE) hold(b.id, InputState(buttons = b.mask))
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
