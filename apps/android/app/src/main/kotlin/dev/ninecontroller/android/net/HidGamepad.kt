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

    private lateinit var inputReport: BluetoothGattCharacteristic
    private lateinit var outputReport: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic
    /** Values of our characteristics and descriptors, keyed by object identity (two Reports share a UUID). */
    private val values = ConcurrentHashMap<Any, ByteArray>()

    val active get() = running

    fun unavailableReason(): String? = BlePeripheral(context).unavailableReason()

    @Synchronized fun start() {
        if (running) return
        unavailableReason()?.let { _state.value = State.Failed(it); return }
        val s = manager.openGattServer(context, callback) ?: run { _state.value = State.Failed("Bluetooth refused to open a GATT server"); return }
        server = s
        // Android adds one service at a time: wait for each onServiceAdded before the next.
        for (svc in listOf(deviceInfo(), battery(), hid())) {
            if (!s.addService(svc) || !serviceAdded.tryAcquire(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "service ${svc.uuid} was not added")
                stop(); _state.value = State.Failed("This phone refused the Bluetooth controller service"); return
            }
        }
        running = true
        Thread(::pumpLoop, "c9-hid-notify").apply { isDaemon = true; start() }
        advertise()
    }

    @Synchronized fun stop() {
        running = false
        wake.release()
        haptics.rumble(0, 0, 0)
        runCatching { manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { host?.let { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        server = null; host = null; subscribed = false
        _state.value = State.Off
    }

    /** Latest controller state; sent as soon as the previous notification has gone out. */
    fun submit(s: InputState) { if (s != latest) { latest = s; dirty = true; wake.release() } }

    private fun advertise() {
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID_SERVICE)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val adv = manager.adapter.bluetoothLeAdvertiser ?: run { _state.value = State.Failed("This phone can't advertise over Bluetooth LE"); return }
        adv.startAdvertising(settings, data, scan, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.i(TAG, "advertising as HID gamepad"); if (host == null) _state.value = State.Advertising }
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return // a reconnect raced an earlier start
            Log.w(TAG, "advertising failed: $errorCode"); _state.value = State.Failed("Bluetooth advertising failed (code $errorCode)")
        }
    }

    private fun pumpLoop() {
        while (running) {
            wake.tryAcquire(100, TimeUnit.MILLISECONDS)
            val d = host ?: continue
            if (!subscribed || !dirty) continue
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
            if (status == BluetoothGatt.GATT_SUCCESS) serviceAdded.release() else Log.w(TAG, "onServiceAdded ${service.uuid} status=$status")
        }

        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status bond=${d.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = d; publishConnected()
                server?.connect(d, false) // hold the link from our side too
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && d == host) {
                host = null; subscribed = false
                haptics.rumble(0, 0, 0)
                if (running) { _state.value = State.Advertising; advertise() }
            }
        }

        override fun onCharacteristicReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            if (ch === batteryLevel) values[batteryLevel] = byteArrayOf(batteryPercent().toByte())
            respond(d, requestId, offset, values[ch])
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
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
            if (desc.uuid != CCCD) return
            values[desc] = value
            if (desc.characteristic === inputReport) {
                subscribed = value.isNotEmpty() && value[0].toInt() and 1 != 0
                Log.i(TAG, "host ${if (subscribed) "subscribed to" else "unsubscribed from"} input reports")
                publishConnected()
                if (subscribed) { dirty = true; wake.release() }
            }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) { sent.release() }
        override fun onMtuChanged(d: BluetoothDevice, mtu: Int) { Log.i(TAG, "mtu $mtu") }
    }

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
            addDescriptor(desc(CCCD, dEnc, byteArrayOf(0, 0)))
            addDescriptor(desc(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED, byteArrayOf(XboxHidReport.INPUT_ID.toByte(), 0x01)))
        }
        addCharacteristic(inputReport)
        outputReport = char(REPORT, PROPERTY_READ or PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE, enc or PERMISSION_WRITE_ENCRYPTED, ByteArray(8)).apply {
            addDescriptor(desc(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED, byteArrayOf(XboxHidReport.OUTPUT_ID.toByte(), 0x02)))
        }
        addCharacteristic(outputReport)
    }

    companion object {
        private const val TAG = "C9Hid"
        private fun u16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
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
