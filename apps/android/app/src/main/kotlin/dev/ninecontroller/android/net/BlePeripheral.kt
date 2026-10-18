package dev.ninecontroller.android.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.ninecontroller.protocol.ControlBridge
import dev.ninecontroller.protocol.DatagramLink
import dev.ninecontroller.protocol.LinkConst
import dev.ninecontroller.protocol.PacketType
import dev.ninecontroller.protocol.Reassembler
import dev.ninecontroller.protocol.Segmenter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * BLE GATT binding, phone side (SPEC §8.3): this phone is a GATT server; the desktop host is the
 * central. Requires BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT on Android 12+.
 * STATUS: compiles; not yet run on a device against a host (UNVERIFIED).
 */
@SuppressLint("MissingPermission") // callers check [permissionsGranted] first
class BlePeripheral(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    /** Why BLE is unusable right now, or null when it can be offered. */
    fun unavailableReason(): String? = when {
        adapter == null -> "This phone has no Bluetooth"
        !permissionsGranted() -> "Bluetooth permission not granted"
        adapter?.isEnabled != true -> "Bluetooth is off"
        adapter?.bluetoothLeAdvertiser == null || adapter?.isMultipleAdvertisementSupported != true -> "This phone can't act as a Bluetooth LE peripheral"
        else -> null
    }

    fun permissionsGranted(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        listOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    } else true

    /**
     * Advertise with [hint] and wait up to [timeoutMs] for a host to connect and subscribe.
     * Returns the link, or null on timeout. The caller closes it.
     */
    fun awaitHost(hint: ByteArray, timeoutMs: Long): BleLink? {
        unavailableReason()?.let { throw IllegalStateException(it) }
        val link = BleLink(context, manager!!, hint)
        return if (link.start(timeoutMs)) link else { link.close(); null }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString(LinkConst.BLE_SERVICE)
        val CONTROL_UP: UUID = UUID.fromString(LinkConst.BLE_CONTROL_UP)
        val CONTROL_DOWN: UUID = UUID.fromString(LinkConst.BLE_CONTROL_DOWN)
        val INPUT_UP: UUID = UUID.fromString(LinkConst.BLE_INPUT_UP)
        val INPUT_DOWN: UUID = UUID.fromString(LinkConst.BLE_INPUT_DOWN)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

@SuppressLint("MissingPermission")
class BleLink internal constructor(private val context: Context, private val manager: BluetoothManager, private val hint: ByteArray) : DatagramLink {
    private var server: BluetoothGattServer? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile var mtu = 23; private set
    private val subscribed = HashSet<UUID>()
    private val ready = Semaphore(0)
    private val sent = Semaphore(1)
    private val datagrams = LinkedBlockingQueue<ByteArray>()
    private val controlIn = Reassembler(65535)
    private val inputIn = Reassembler(256)
    private val controlSeg = Segmenter()
    private val inputSeg = Segmenter()
    private val controlQueue = LinkedBlockingQueue<ByteArray>()
    /** Ordered input_up messages. A new INPUT datagram removes older queued INPUT datagrams (latest
     *  state); dropping never reorders, so counters stay increasing. Pings/byes keep their place. */
    private val inputQueue = ArrayDeque<ByteArray>()
    private val qlock = Any()
    private val signal = Semaphore(0)
    @Volatile private var closed = false
    @Volatile var disconnected = false; private set
    val control = ControlBridge { frame -> synchronized(controlSeg) { controlSeg.segment(frame, mtu).forEach(controlQueue::put) }; signal.release() }

    private lateinit var controlUp: BluetoothGattCharacteristic
    private lateinit var inputUp: BluetoothGattCharacteristic

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && device == null) device = d
            if (newState == BluetoothProfile.STATE_DISCONNECTED && d == device) { disconnected = true; control.close(); signal.release() }
        }
        override fun onMtuChanged(d: BluetoothDevice, m: Int) { if (d == device) mtu = m }
        override fun onDescriptorWriteRequest(d: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
            if (needResponse) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (d != device || desc.uuid != BlePeripheral.CCCD) return
            val on = value.isNotEmpty() && value[0].toInt() != 0
            synchronized(subscribed) {
                if (on) subscribed += desc.characteristic.uuid else subscribed -= desc.characteristic.uuid
                if (BlePeripheral.CONTROL_UP in subscribed && BlePeripheral.INPUT_UP in subscribed) ready.release()
            }
        }
        override fun onCharacteristicWriteRequest(d: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic, prepared: Boolean, needResponse: Boolean, offset: Int, value: ByteArray) {
            if (needResponse) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (d != device) return
            when (ch.uuid) {
                BlePeripheral.CONTROL_DOWN -> synchronized(controlIn) { controlIn.push(value) }?.let { if (it.isNotEmpty()) control.deliver(it) }
                BlePeripheral.INPUT_DOWN -> synchronized(inputIn) { inputIn.push(value) }?.let { datagrams.offer(it) }
            }
        }
        override fun onNotificationSent(d: BluetoothDevice, status: Int) { sent.release() }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    internal fun start(timeoutMs: Long): Boolean {
        val s = manager.openGattServer(context, callback) ?: return false
        server = s
        val svc = BluetoothGattService(BlePeripheral.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fun notifyChar(u: UUID) = BluetoothGattCharacteristic(u, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ).apply {
            addDescriptor(BluetoothGattDescriptor(BlePeripheral.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        controlUp = notifyChar(BlePeripheral.CONTROL_UP)
        inputUp = notifyChar(BlePeripheral.INPUT_UP)
        svc.addCharacteristic(controlUp)
        svc.addCharacteristic(BluetoothGattCharacteristic(BlePeripheral.CONTROL_DOWN, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        svc.addCharacteristic(inputUp)
        svc.addCharacteristic(BluetoothGattCharacteristic(BlePeripheral.INPUT_DOWN, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        s.addService(svc)
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BlePeripheral.SERVICE)).setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().addServiceData(ParcelUuid(BlePeripheral.SERVICE), hint.copyOf(4)).build()
        manager.adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback) ?: return false
        val ok = ready.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        if (ok) Thread(::pump, "c9-ble-notify").apply { isDaemon = true; start() }
        return ok
    }

    /** Notification pump: control segments first; input = only the newest pending datagram. */
    private fun pump() {
        while (!closed && !disconnected) {
            val ctl = controlQueue.poll()
            if (ctl != null) { notify(controlUp, ctl); continue }
            val inp = synchronized(qlock) { inputQueue.removeFirstOrNull() }
            if (inp != null) { inputSeg.segment(inp, mtu).forEach { notify(inputUp, it) }; continue }
            signal.tryAcquire(50, TimeUnit.MILLISECONDS)
        }
    }

    private fun notify(ch: BluetoothGattCharacteristic, v: ByteArray) {
        val d = device ?: return
        if (!sent.tryAcquire(200, TimeUnit.MILLISECONDS)) { /* stack lost a callback; continue rather than stall */ }
        if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(d, ch, false, v)
        else @Suppress("DEPRECATION") { ch.value = v; server?.notifyCharacteristicChanged(d, ch, false) }
    }

    override fun send(d: ByteArray) {
        val isInput = d.size > 3 && d[3].toInt() == PacketType.INPUT.code
        synchronized(qlock) {
            if (isInput) inputQueue.removeAll { it.size > 3 && it[3].toInt() == PacketType.INPUT.code }
            inputQueue.addLast(d)
        }
        signal.release()
    }

    override fun receive(timeoutMs: Int): ByteArray? = datagrams.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
        control.close()
        runCatching { device?.let { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        signal.release()
    }
}
