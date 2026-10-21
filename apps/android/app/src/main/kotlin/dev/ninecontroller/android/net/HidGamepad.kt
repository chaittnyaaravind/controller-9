package dev.ninecontroller.android.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.android.input.XboxHidReport
import dev.ninecontroller.protocol.InputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The phone as a Bluetooth controller with no app on the computer (docs/DIRECT_BLUETOOTH.md):
 * a HID-over-GATT peripheral that presents itself as an Xbox Wireless Controller, so the computer's
 * own Bluetooth HID stack creates the gamepad. The computer pairs from its Bluetooth settings.
 * Requires BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT (checked by [BlePeripheral.permissionsGranted]).
 */
@SuppressLint("MissingPermission")
class HidGamepad(private val context: Context, private val haptics: Haptics) {
    sealed interface State {
        data object Off : State
        data object Advertising : State
        data class Connected(val host: String, val subscribed: Boolean) : State
        data class Failed(val reason: String) : State
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    /** Reports sent since start, for diagnostics. */
    @Volatile var reportsSent = 0L; private set

    private var server: BluetoothGattServer? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var subscribed = false
    private val serviceAdded = Semaphore(0)
    private val sent = Semaphore(1)
    private val wake = Semaphore(0)
    @Volatile private var latest = InputState.NEUTRAL
    @Volatile private var dirty = false
    @Volatile private var running = false
    @Volatile private var quietUntilNs = 0L

    private lateinit var inputReport: BluetoothGattCharacteristic
    private lateinit var outputReport: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic
    private lateinit var inputCccd: BluetoothGattDescriptor
    /** Input-notification state per bonded computer. The Bluetooth spec keeps a bonded client's
     *  subscription across connections and computers rely on it (macOS does not subscribe again on
     *  reconnect). A bonded host of this HID service counts as subscribed unless it unsubscribed. */
    private val subscribers = context.getSharedPreferences("hid_subscribers", Context.MODE_PRIVATE)
    /** Values of our characteristics and descriptors, keyed by object identity (two Reports share a UUID). */
    private val values = ConcurrentHashMap<Any, ByteArray>()

    val active get() = running || wanted
    /** The user turned the mode on; it comes back by itself after Bluetooth is switched off and on. */
    @Volatile private var wanted = false

    init {
        androidx.core.content.ContextCompat.registerReceiver(context, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                    android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> if (running) { Log.i(TAG, "Bluetooth turning off"); teardown(); _state.value = State.Failed("Bluetooth is off") }
                    android.bluetooth.BluetoothAdapter.STATE_ON -> if (wanted && !running) { Log.i(TAG, "Bluetooth back on: restarting controller"); start() }
                }
            }
        }, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unavailableReason(): String? = BlePeripheral(context).unavailableReason()

    /** Start (or keep) the controller. Setup runs off the caller's thread: it may wait and retry. */
    fun start() {
        if (!wanted) HidService.start(context) // keeps the controller up while the app is closed
        wanted = true
        if (running || starting) return
        starting = true
        Thread({ try { for (attempt in 1..6) { if (!wanted || setUp()) break; Thread.sleep(1500) } } finally { starting = false } }, "c9-hid-start").apply { isDaemon = true; start() }
    }
    @Volatile private var starting = false

    /** One setup attempt; false = landed off the pinned handles (another service registered mid-way), retry. */
    @Synchronized private fun setUp(): Boolean {
        if (running) return true
        unavailableReason()?.let { _state.value = State.Failed(it); return true }
        val s = manager.openGattServer(context, callback) ?: run { _state.value = State.Failed("Bluetooth refused to open a GATT server"); return true }
        server = s
        // Android adds one service at a time: wait for each onServiceAdded before the next.
        fun add(svc: BluetoothGattService): Boolean {
            val accepted = s.addService(svc)
            if (accepted && serviceAdded.tryAcquire(3, TimeUnit.SECONDS)) return true
            Log.w(TAG, "service ${svc.uuid} (${svc.characteristics.size} characteristics) was not added: ${if (accepted) "no callback" else "refused"}")
            teardown(); _state.value = State.Failed("This phone refused the Bluetooth controller service"); return false
        }
        // Pin the layout: a bonded computer caches attribute handles and macOS ignores Service Changed,
        // but Android hands out the next free handle, which moves with whatever registered first. An
        // empty marker service reveals that handle; filler then makes the controller start at HANDLE_BASE.
        val marker = BluetoothGattService(PAD_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        if (!add(marker)) return true
        val gap = HANDLE_BASE - (marker.instanceId + 1)
        if (gap > 0) for (f in filler(gap)) if (!add(f)) return true
        if (gap < 0) Log.w(TAG, "handle ${marker.instanceId + 1} is past the pinned base $HANDLE_BASE: computers paired earlier must pair again")
        val info = deviceInfo()
        for (svc in listOf(info, battery(), hid())) if (!add(svc)) return true
        if (gap >= 0 && info.instanceId != HANDLE_BASE) {
            Log.w(TAG, "controller landed at ${info.instanceId}, not $HANDLE_BASE: something registered mid-setup, retrying")
            teardown(); return false
        }
        running = true
        Thread(::pumpLoop, "c9-hid-notify").apply { isDaemon = true; start() }
        // A bonded computer reconnects within ~0.5 s of the phone's Bluetooth coming on, before these
        // services exist. macOS then caches a phone without a controller and, because the half-built
        // database also fails its Service Changed subscription, never refreshes (measured, 2026-10-20).
        // Drop every link that predates the controller; the computer reconnects to the complete one.
        val early = manager.getConnectedDevices(BluetoothProfile.GATT).filter { it.bondState == BluetoothDevice.BOND_BONDED }
        if (early.isNotEmpty()) Thread({
            for (d in early) { Log.i(TAG, "dropping a link made before the controller was ready"); s.connect(d, false); Thread.sleep(150); s.cancelConnection(d) }
        }, "c9-hid-drop-early").apply { isDaemon = true; start() }
        advertise()
        return true
    }

    @Synchronized fun stop() {
        if (wanted) HidService.stop(context)
        wanted = false
        teardown()
        _state.value = State.Off
    }

    @Synchronized private fun teardown() {
        running = false
        wake.release()
        haptics.rumble(0, 0, 0)
        runCatching { manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { host?.let { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        server = null; host = null; subscribed = false
    }

    /** Latest controller state; sent as soon as the previous notification has gone out. */
    fun submit(s: InputState) { if (s != latest) { latest = s; dirty = true; wake.release() } }

    private fun advertise() {
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID_SERVICE)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        // Right after Bluetooth turns on the LE advertiser is not ready yet: retry for a few seconds,
        // off the caller's thread (start() can run on the main thread).
        Thread({ advertiseWithRetry(settings, data, scan) }, "c9-hid-advertise").apply { isDaemon = true; start() }
    }

    private fun advertiseWithRetry(settings: AdvertiseSettings, data: AdvertiseData, scan: AdvertiseData) {
        advertiseCallback = newAdvertiseCallback()
        for (attempt in 1..20) {
            if (!running) return
            val adv = manager.adapter.bluetoothLeAdvertiser
            if (adv != null && runCatching { adv.startAdvertising(settings, data, scan, advertiseCallback) }.onFailure { Log.w(TAG, "startAdvertising: $it") }.isSuccess) return
            Thread.sleep(500)
        }
        Log.w(TAG, "advertiser never became available"); _state.value = State.Failed("This phone can't advertise over Bluetooth LE")
    }

    /** A new callback per advertising session: after Bluetooth restarts, the advertiser may still hold
     *  the previous callback, and reusing it fails with "already started" while nothing advertises. */
    @Volatile private var advertiseCallback = newAdvertiseCallback()

    private fun newAdvertiseCallback() = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.i(TAG, "advertising as HID gamepad"); if (host == null) _state.value = State.Advertising }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising start failed: $errorCode")
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return // this session's advertisement is already running
            Log.w(TAG, "advertising failed: $errorCode"); _state.value = State.Failed("Bluetooth advertising failed (code $errorCode)")
        }
    }

    private fun pumpLoop() {
        while (running) {
            wake.tryAcquire(100, TimeUnit.MILLISECONDS)
            val d = host ?: continue
            if (!subscribed || !dirty || System.nanoTime() < quietUntilNs) continue
            dirty = false
            val report = XboxHidReport.input(latest)
            values[inputReport] = report
            if (!sent.tryAcquire(100, TimeUnit.MILLISECONDS)) { /* lost callback: keep going rather than stall */ }
            val ok = if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(d, inputReport, false, report) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") { inputReport.value = report; server?.notifyCharacteristicChanged(d, inputReport, false) == true }
            if (ok) reportsSent++ else { sent.release(); dirty = true }
        }
    }

    private fun publishConnected() { host?.let { d -> _state.value = State.Connected(runCatching { d.name }.getOrNull() ?: "computer", subscribed) } }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) { Log.i(TAG, "service ${name(service.uuid)} handle ${service.instanceId}"); serviceAdded.release() } else Log.w(TAG, "onServiceAdded ${service.uuid} status=$status")
        }

        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status bond=${d.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = d
                if (d.bondState == BluetoothDevice.BOND_BONDED && subscribers.getBoolean(d.address, true)) {
                    // Let the computer encrypt the link first: a report sent earlier collides with its
                    // encryption start and macOS then never attaches its controller driver.
                    quietUntilNs = System.nanoTime() + RECONNECT_QUIET_MS * 1_000_000L
                    subscribed = true; values[inputCccd] = byteArrayOf(1, 0); dirty = true; wake.release()
                    Log.i(TAG, "bonded computer reconnected: input reports resume")
                }
                publishConnected()
                server?.connect(d, false) // hold the link from our side too
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && d == host) {
                host = null; subscribed = false
                haptics.rumble(0, 0, 0)
                if (running) { _state.value = State.Advertising; advertise() }
            }
        }

        override fun onCharacteristicReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            adopt(d)
            if (ch === batteryLevel) values[batteryLevel] = byteArrayOf(batteryPercent().toByte())
            Log.d(TAG, "read ${name(ch.uuid)} offset=$offset")
            respond(d, requestId, offset, values[ch])
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
            Log.d(TAG, "read ${name(desc.uuid)} of ${name(desc.characteristic.uuid)}")
            respond(d, requestId, offset, values[desc] ?: byteArrayOf(0, 0))
        }

        override fun onCharacteristicWriteRequest(d: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
            if (needResponse) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (ch === outputReport) {
                values[outputReport] = value
                Log.i(TAG, "output report ${value.size} bytes")
                XboxHidReport.rumble(value)?.let { haptics.rumble(it.low, it.high, it.ms) }
            } else if (ch.uuid == PROTOCOL_MODE) values[ch] = value
        }

        override fun onDescriptorWriteRequest(d: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
            if (needResponse) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            Log.d(TAG, "write ${name(desc.uuid)} of ${name(desc.characteristic.uuid)} = ${value.joinToString(" ") { "%02x".format(it) }}")
            adopt(d)
            if (desc.uuid != CCCD) return
            values[desc] = value
            if (desc.characteristic === inputReport) {
                subscribed = value.isNotEmpty() && value[0].toInt() and 1 != 0
                subscribers.edit().putBoolean(d.address, subscribed).apply()
                Log.i(TAG, "host ${if (subscribed) "subscribed to" else "unsubscribed from"} input reports")
                publishConnected()
                if (subscribed) { dirty = true; wake.release() }
            }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) { sent.release() }
        override fun onMtuChanged(d: BluetoothDevice, mtu: Int) { Log.i(TAG, "mtu $mtu"); adopt(d) }
    }

    /**
     * Claim a computer's link as ours. After Bluetooth restarts, this phone's stack (realme, Android 16)
     * reports new links only through MTU/read/write callbacks, never onConnectionStateChange; a link no
     * app holds is dropped by Android within seconds, so the computer saw the controller flap.
     */
    private fun adopt(d: BluetoothDevice) {
        val now = System.nanoTime()
        if (host?.address == d.address && now - adoptedAtNs < 2_000_000_000L) return
        adoptedAtNs = now
        if (host?.address != d.address) Log.i(TAG, "link adopted without a connection callback")
        host = d
        server?.connect(d, false)
        publishConnected()
    }
    @Volatile private var adoptedAtNs = 0L

    private fun respond(d: BluetoothDevice, requestId: Int, offset: Int, v: ByteArray?) {
        val s = server ?: return
        if (v == null || offset > v.size) { s.sendResponse(d, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null); return }
        s.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(offset, v.size))
    }

    private fun batteryPercent(): Int = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100

    private fun char(uuid: UUID, props: Int, perms: Int, value: ByteArray?) =
        BluetoothGattCharacteristic(uuid, props, perms).also { if (value != null) values[it] = value }

    private fun desc(uuid: UUID, perms: Int, value: ByteArray) = BluetoothGattDescriptor(uuid, perms).also { values[it] = value }

    /** Services occupying exactly [handles] attribute handles, in blocks of [CHUNK]. A service is 1 (declaration) + 2 per characteristic + 1 per
     *  descriptor, so any size except 2 fits in one service; sizes are chosen to never leave 2. */
    private fun filler(handles: Int): List<BluetoothGattService> {
        var k = 0
        // Each service needs its own UUID: this phone's stack never answers for a duplicate.
        fun one(n: Int) = BluetoothGattService(fillerUuid(k++), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            repeat((n - 1) / 2) { i -> addCharacteristic(BluetoothGattCharacteristic(padUuid(i), PROPERTY_READ, PERMISSION_READ)) }
            if ((n - 1) % 2 == 1) characteristics.last().addDescriptor(BluetoothGattDescriptor(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ))
        }
        val out = ArrayList<BluetoothGattService>()
        var left = handles
        while (left > 0) {
            val n = when { left == 2 -> 1; left <= CHUNK -> left; left - CHUNK == 2 -> CHUNK - 1; else -> CHUNK }
            out += one(n); left -= n
        }
        return out
    }

    private fun deviceInfo() = BluetoothGattService(DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val x = XboxHidReport
        // PnP ID: vendor ID source 2 (USB-IF), VID, PID, version, all little-endian.
        val pnp = byteArrayOf(0x02, x.VENDOR_ID.toByte(), (x.VENDOR_ID shr 8).toByte(), x.PRODUCT_ID.toByte(),
            (x.PRODUCT_ID shr 8).toByte(), x.VERSION.toByte(), (x.VERSION shr 8).toByte())
        addCharacteristic(char(PNP_ID, PROPERTY_READ, PERMISSION_READ, pnp))
        addCharacteristic(char(MANUFACTURER, PROPERTY_READ, PERMISSION_READ, "Controller-9".toByteArray()))
    }

    private fun battery() = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        batteryLevel = char(BATTERY_LEVEL, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ, byteArrayOf(100))
        batteryLevel.addDescriptor(desc(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE, byteArrayOf(0, 0)))
        addCharacteristic(batteryLevel)
    }

    /** Encrypted HID characteristics make the computer bond before it reads the report map. */
    private fun hid() = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val enc = PERMISSION_READ_ENCRYPTED
        val dEnc = BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
        addCharacteristic(char(HID_INFO, PROPERTY_READ, enc, byteArrayOf(0x11, 0x01, 0x00, 0x02))) // HID 1.11, normally connectable
        addCharacteristic(char(REPORT_MAP, PROPERTY_READ, enc, XboxHidReport.REPORT_MAP))
        addCharacteristic(char(PROTOCOL_MODE, PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE, enc or PERMISSION_WRITE_ENCRYPTED, byteArrayOf(0x01)))
        addCharacteristic(char(CONTROL_POINT, PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED, null))
        inputReport = char(REPORT, PROPERTY_READ or PROPERTY_NOTIFY, enc, XboxHidReport.input(InputState.NEUTRAL)).apply {
            inputCccd = desc(CCCD, dEnc, byteArrayOf(0, 0))
            addDescriptor(inputCccd)
            addDescriptor(desc(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED, byteArrayOf(XboxHidReport.INPUT_ID.toByte(), 0x01)))
        }
        addCharacteristic(inputReport)
        outputReport = char(REPORT, PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE, enc or PERMISSION_WRITE_ENCRYPTED, ByteArray(8)).apply {
            addDescriptor(desc(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED, byteArrayOf(XboxHidReport.OUTPUT_ID.toByte(), 0x02)))
        }
        addCharacteristic(outputReport)
    }

    private fun name(u: UUID) = "%04X".format((u.mostSignificantBits ushr 32).toInt() and 0xFFFF)

    companion object {
        private const val TAG = "C9Hid"
        private fun u16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
        /** First handle of the controller's services (see start()). Far above Android's next free handle,
         *  measured at 234–351 on a realme 12 5G depending on what the system registered first. */
        const val HANDLE_BASE = 640
        /** After a bonded computer reconnects, send nothing for this long (link encryption first). */
        const val RECONNECT_QUIET_MS = 1500L
        /** Private UUID of the layout marker and filler services. */
        val PAD_SERVICE: UUID = UUID.fromString("c9c90100-1c3a-4e9d-8f2b-6c7a0b5d4e10")
        /** Handles per filler service (1 + 2 × 30 characteristics). */
        private const val CHUNK = 61
        private fun fillerUuid(i: Int): UUID = UUID.fromString("c9c90300-1c3a-4e9d-8f2b-6c7a0b5d%04x".format(i))
        private fun padUuid(i: Int): UUID = UUID.fromString("c9c90200-1c3a-4e9d-8f2b-6c7a0b5d%04x".format(i))
        val HID_SERVICE = u16(0x1812)
        val DEVICE_INFO = u16(0x180A)
        val BATTERY_SERVICE = u16(0x180F)
        val HID_INFO = u16(0x2A4A)
        val REPORT_MAP = u16(0x2A4B)
        val CONTROL_POINT = u16(0x2A4C)
        val REPORT = u16(0x2A4D)
        val PROTOCOL_MODE = u16(0x2A4E)
        val PNP_ID = u16(0x2A50)
        val MANUFACTURER = u16(0x2A29)
        val BATTERY_LEVEL = u16(0x2A19)
        val CCCD = u16(0x2902)
        val REPORT_REFERENCE = u16(0x2908)
    }
}
