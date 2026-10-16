package com.karin.hyperpill.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import com.karin.hyperpill.hook.Log
import java.util.concurrent.atomic.AtomicBoolean

object HyperPILLAction {
    const val ACTION_PODS_CONNECTED = "com.karin.hyperpill.action.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "com.karin.hyperpill.action.pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = "com.karin.hyperpill.action.pods_battery_changed"
    const val ACTION_REFRESH_STATUS = "com.karin.hyperpill.action.refresh_status"
}

data class PodParams(
    val battery: Int,
    val isCharging: Boolean,
    val isConnected: Boolean,
    val unused: Int = 0
)

data class BatteryParams(
    val left: PodParams? = null,
    val right: PodParams? = null,
    val case: PodParams? = null,
    val single: PodParams? = null
)

/**
 * Runs inside the Android Bluetooth process.
 * Opens a GAIA RFCOMM connection to Pill/OBA and broadcasts battery state to
 * Xiaomi Bluetooth / MiLink / Settings so later steps can feed the system UI.
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object PillSystemController {

    private const val TAG = "HyperPILL-System"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    /**
     * Packages the pod-state broadcasts are addressed to.
     *
     * The module runs in several processes at once, and a broadcast sent with
     * setPackage(pkg) is only delivered to receivers inside pkg, so every consumer has to
     * be listed by name. com.karin.hyperpill is the module's own UI process: without it the
     * activity is never told that a session is already up and keeps showing a disconnected
     * state until the user taps connect by hand.
     */
    private val BROADCAST_TARGETS = listOf(
        "com.xiaomi.bluetooth",
        "com.milink.service",
        "com.android.settings",
        "com.karin.hyperpill"
    )

    private val connected = AtomicBoolean(false)
    private var context: Context? = null
    private var device: BluetoothDevice? = null
    private var client: PillClient? = null
    private var pollThread: Thread? = null
    private var currentBattery: BatteryParams? = null
    private var receiverRegistered = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * True when this process already holds a live RFCOMM session for [address].
     *
     * The module is injected into several processes at once (com.android.bluetooth,
     * com.xiaomi.bluetooth, com.karin.hyperpill, ...). Because this controller is an
     * in-process singleton rather than a cross-process one, each of them owns an
     * independent socket for the same SPP UUID; two live sockets evict each other.
     * Callers use this to detect an existing session and reuse it instead of racing it.
     */
    fun isConnectedTo(address: String?): Boolean =
        address != null && connected.get() && device?.address == address

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HyperPILLAction.ACTION_REFRESH_STATUS -> {
                    Log.d(TAG, "refresh status requested")
                    // Re-announce the session before querying. Receivers that hooked in
                    // after the original connected broadcast (process restart, APK update,
                    // module reload) would otherwise never learn the address and would drop
                    // every battery event that follows.
                    val d = device
                    if (connected.get() && d != null) {
                        Log.diag("[Controller] refresh -> re-announcing connected ${d.address}")
                        sendConnectedBroadcast(d)
                    }
                    queryBattery()
                }
            }
        }
    }

    @Synchronized
    fun connect(context: Context, device: BluetoothDevice) {
        Log.diag("[Controller] connect() dev=${device.address} alreadyConnected=${connected.get()} current=${this.device?.address}")
        if (connected.get() && this.device?.address == device.address) {
            Log.diag("[Controller]   -> already connected, no-op")
            return
        }
        Log.diag("[Controller]   -> tearing down previous session before reconnect")
        disconnectInternal()
        this.context = context.applicationContext ?: context
        this.device = device
        connected.set(true)
        registerReceiverIfNeeded()

        sendConnectedBroadcast(device)

        val ctx = this.context
        if (ctx == null) {
            Log.diag("[Controller]   -> FATAL context null after applicationContext fallback")
            return
        }
        val target = device
        Thread {
            try {
                val c = PillClient(target, object : PillClient.Listener {
                    override fun onConnected() {
                        Log.diag("[Controller] RFCOMM connected ${target.address}, querying battery")
                        queryBattery()
                    }

                    override fun onFrame(pdu: ByteArray) {
                        handleFrame(pdu)
                    }

                    override fun onError(throwable: Throwable) {
                        Log.diag("[Controller] RFCOMM error: ${throwable.message}", throwable)
                        // connected is set optimistically before the socket exists, so a
                        // failed attempt would otherwise leave it stuck true and make every
                        // later connect() a no-op. Clear it so the next trigger can retry.
                        disconnectInternal()
                    }

                    override fun onDisconnected() {
                        Log.diag("[Controller] RFCOMM disconnected ${target.address}")
                        disconnectInternal()
                    }
                })
                client = c
                c.connect()
            } catch (t: Throwable) {
                Log.diag("[Controller] RFCOMM connect FAILED: ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }.apply {
            name = "HyperPILL-System-RFCOMM"
            start()
        }

        startPolling()
    }

    @Synchronized
    fun disconnect(context: Context?, device: BluetoothDevice?) {
        // A dropped RFCOMM stream tears the session down on its own (onError ->
        // disconnectInternal), which clears this.device. The headset then reports its
        // A2DP disconnect a few seconds later, and a guard of the form
        // "this.device?.address != device.address" evaluates null != address -> true and
        // returns early, so the pods_disconnected broadcast is never sent and the
        // notification plus focus island stay on screen forever. Only skip when a
        // *different* device is still actively connected.
        val current = this.device
        if (device != null && current != null && current.address != device.address) {
            Log.diag("[Controller] disconnect() ignored: ${device.address} != active ${current.address}")
            return
        }
        // Nothing left to tear down, but the host may still be showing the last battery
        // state from a session that already ended. Emit the disconnect anyway so the
        // receiver withdraws it.
        if (!connected.get() && current == null) {
            Log.diag("[Controller] disconnect() after session already cleared, notifying host unconditionally")
            val ctx = context ?: this.context
            if (ctx != null && device != null) {
                sendDisconnectedBroadcast(ctx.applicationContext ?: ctx, device.address, device)
            }
            return
        }
        disconnectInternal(notifyDisconnect = true)
    }

    private fun disconnectInternal(notifyDisconnect: Boolean = false) {
        Log.diag("[Controller] disconnectInternal notify=$notifyDisconnect (client=${client != null} connected=${connected.get()})")
        val oldContext = context
        val oldDevice = device
        connected.set(false)
        pollThread?.interrupt()
        pollThread = null
        runCatching { client?.close() }
        client = null
        currentBattery = null
        if (receiverRegistered) {
            runCatching { oldContext?.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        context = null
        device = null
        if (notifyDisconnect && oldDevice != null && oldContext != null) {
            sendDisconnectedBroadcast(oldContext, oldDevice.address, oldDevice)
        }
    }

    private fun sendDisconnectedBroadcast(ctx: Context, address: String?, device: BluetoothDevice?) {
        val base = Intent(HyperPILLAction.ACTION_PODS_DISCONNECTED).apply {
            putExtra("address", address)
            device?.let { putExtra("device", it) }
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(Intent(base).setPackage(ctx.packageName))
        BROADCAST_TARGETS.forEach { pkg ->
            ctx.sendBroadcast(Intent(base).setPackage(pkg))
        }
    }

    private fun registerReceiverIfNeeded() {
        val ctx = context ?: return
        if (receiverRegistered) return
        ctx.registerReceiver(
            receiver,
            IntentFilter(HyperPILLAction.ACTION_REFRESH_STATUS),
            Context.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun startPolling() {
        pollThread?.interrupt()
        pollThread = Thread {
            while (connected.get()) {
                try {
                    Thread.sleep(BATTERY_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (connected.get()) queryBattery()
            }
        }.apply {
            name = "HyperPILL-System-BatteryPoll"
            start()
        }
    }

    private fun queryBattery() {
        val c = client ?: return
        if (!connected.get()) return
        runCatching {
            c.requestBatteryLevels()
            Log.d(TAG, "battery query sent")
        }.onFailure { t ->
            if (!connected.get()) return@onFailure
            Log.w(TAG, "battery query failed: ${t.message}")
            if (t is java.io.IOException) {
                // Socket is dead (e.g. Pill disconnected while polling). Stop cleanly.
                disconnectInternal()
            }
        }
    }

    private fun handleFrame(pdu: ByteArray) {
        val command = GaiaProtocol.commandValueOf(pdu)
        val type = (command shr 7) and 0x03
        val feature = (command shr 9) and 0x7F
        val cmd = command and 0x7F
        if (type != GaiaProtocol.TYPE_RESPONSE) return
        if (feature != GaiaProtocol.FEATURE_BATTERY || cmd != GaiaProtocol.CMD_GET_BATTERY_LEVELS) return

        val levels = GaiaProtocol.parseBatteryLevels(GaiaProtocol.payloadOf(pdu))
        val battery = BatteryParams(
            left = levels[GaiaProtocol.BATTERY_LEFT]?.let { PodParams(it, false, true) },
            right = levels[GaiaProtocol.BATTERY_RIGHT]?.let { PodParams(it, false, true) },
            case = levels[GaiaProtocol.BATTERY_CHARGER_CASE]?.let { PodParams(it, false, true) },
            single = levels[GaiaProtocol.BATTERY_SINGLE_DEVICE]?.let { PodParams(it, false, true) }
        )
        currentBattery = battery
        Log.i(TAG, "battery ${battery.single?.battery ?: "--"} single, L=${battery.left?.battery ?: "--"} R=${battery.right?.battery ?: "--"} C=${battery.case?.battery ?: "--"}")
        sendBatteryBroadcast(battery)
    }

    private fun sendConnectedBroadcast(device: BluetoothDevice) {
        val ctx = context ?: return
        val name = runCatching { device.alias ?: device.name }.getOrNull() ?: device.address
        val intent = Intent(HyperPILLAction.ACTION_PODS_CONNECTED).apply {
            putExtra("address", device.address)
            putExtra("device_name", name)
            putExtra("device", device)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(intent.setPackage(ctx.packageName))
        BROADCAST_TARGETS.forEach { pkg ->
            ctx.sendBroadcast(Intent(intent).setPackage(pkg))
        }
    }

    private fun sendBatteryBroadcast(battery: BatteryParams) {
        val ctx = context ?: return
        val base = Intent(HyperPILLAction.ACTION_PODS_BATTERY_CHANGED).apply {
            device?.address?.let { putExtra("address", it) }
            device?.let { d ->
                putExtra("device_name", runCatching { d.alias ?: d.name }.getOrNull() ?: d.address)
                putExtra("device", d)
            }
            putExtra("left_battery", battery.left?.battery ?: 0)
            putExtra("left_connected", battery.left?.isConnected == true)
            putExtra("right_battery", battery.right?.battery ?: 0)
            putExtra("right_connected", battery.right?.isConnected == true)
            putExtra("case_battery", battery.case?.battery ?: 0)
            putExtra("case_connected", battery.case?.isConnected == true)
            putExtra("single_battery", battery.single?.battery ?: 0)
            putExtra("single_connected", battery.single?.isConnected == true)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(Intent(base).setPackage(ctx.packageName))
        BROADCAST_TARGETS.forEach { pkg ->
            ctx.sendBroadcast(Intent(base).setPackage(pkg))
        }
    }
}
