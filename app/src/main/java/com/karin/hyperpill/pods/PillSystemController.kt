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
    const val ACTION_PODS_CONFIG_CHANGED = "com.karin.hyperpill.action.pods_config_changed"
    const val ACTION_APPLY_CONFIG = "com.karin.hyperpill.action.apply_config"
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
 * Builds a pod reading from a raw protocol level byte.
 *
 * The headset reports a level byte of 0 while a pod is settling -- taking it out of the
 * case, re-establishing its link, or right after the RFCOMM stream is rebuilt -- and then
 * sends the real value one poll later. 0 is not a usable percentage for a pod that is
 * reporting at all (a genuinely flat pod still reports a small nonzero byte), so surfacing
 * it as "0%" told the user an earbud was empty when it was not. Returning null makes the
 * pod read as "no reading yet" and leaves null handling to each consumer.
 */
private fun podReading(level: Int?): PodParams? =
    level?.takeIf { it > 0 }?.let { PodParams(it, false, true) }

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

    /**
     * Packages that can host a live controller session.
     *
     * The activity runs in com.karin.hyperpill, but the UI process never owns a session:
     * PillViewModel.connect() opens a plain PillClient, and the only caller of
     * PillSystemController.connect() is HeadsetStateDispatcher, which HookEntry loads for
     * com.android.bluetooth only. Addressing outgoing control broadcasts to the module's own
     * package therefore reached a controller that had no socket and silently dropped every
     * write, which is why the device page stayed read-only while the system auto-connected
     * and only worked after a manual disconnect + tap-connect (that tap creates a local
     * client, so the client-first setters short-circuit before this path is used).
     */
    val CONTROL_TARGET_PACKAGES = listOf(
        "com.android.bluetooth",
        "com.xiaomi.bluetooth"
    )

    private val connected = AtomicBoolean(false)
    private var context: Context? = null
    private var device: BluetoothDevice? = null
    private var client: PillClient? = null
    private var pollThread: Thread? = null
    private var currentBattery: BatteryParams? = null

    /**
     * Last voice configuration read from the headset.
     *
     * The UI sends a partial update (it may change only the toggle or only the level), and
     * the GAIA setter needs all three fields at once, so the missing ones are filled from
     * the most recent read rather than being guessed as zero.
     */
    private var currentVoiceConf: VoiceConf? = null
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

    /**
     * The device this process currently holds a session with, or null.
     *
     * The UI cannot rely on its own paired-device list to name a live session: it is only
     * populated once the user taps refresh, and auto-connect happens entirely inside the
     * hooked bluetooth process. Exposing the controller's own device lets the screen
     * describe a session it never initiated.
     */
    fun connectedDevice(): BluetoothDevice? = if (connected.get()) device else null

    /** Address of the live session, or null. Lets a caller resolve the device itself. */
    fun connectedAddress(): String? = if (connected.get()) device?.address else null

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

                HyperPILLAction.ACTION_APPLY_CONFIG -> {
                    Log.diag("[Controller] apply config from UI")
                    applyConfig(intent)
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
                        Log.diag("[Controller] RFCOMM connected ${target.address}, querying battery + config")
                        queryBattery()
                        queryConfig()
                        // The first battery frame after RFCOMM comes up is routinely a
                        // transient: repeated captures show 01 64 02 64 (100/100) once the
                        // link has settled, but the frame issued immediately after connect
                        // is 01 5A 02 00 -> L=90, R=0 even when both pods are full. Without
                        // a retry that transient stayed on screen until the 30s poll, which
                        // read as "battery is wrong on first connect". Re-ask a few times
                        // over the next seconds so the real values land almost immediately.
                        val settleAddress = target.address
                        listOf(400L, 1_200L, 3_000L).forEach { delayMs ->
                            handler.postDelayed({
                                if (connected.get() && device?.address == settleAddress) {
                                    Log.d(TAG, "battery re-query after connect (+${delayMs}ms)")
                                    queryBattery()
                                }
                            }, delayMs)
                        }
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
            IntentFilter(HyperPILLAction.ACTION_REFRESH_STATUS).apply {
                addAction(HyperPILLAction.ACTION_APPLY_CONFIG)
            },
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

    /**
     * Asks the headset for its stored configuration and relays the answers to the UI.
     *
     * These queries used to be issued only by the app's own PillClient when the app owned
     * the socket. In the normal path the system side owns it, so nothing ever asked, the
     * UI's gain/EQ/voice/dual-connection fields stayed null, and every control on the device
     * page was disabled. Asking here and broadcasting the values lets the configuring screen
     * show real state and drive the live session.
     */
    private fun queryConfig() {
        val c = client ?: return
        if (!connected.get()) return
        runCatching {
            c.requestGain()
            c.requestEqState()
            c.requestAvailablePreSets()
            c.requestEqSet()
            c.requestOneBringTwoState()
            c.requestOneBringTwoTimeout()
            c.requestVoiceConf()
            Log.d(TAG, "config queries sent")
        }.onFailure { t ->
            if (!connected.get()) return@onFailure
            Log.w(TAG, "config query failed: ${t.message}")
        }
    }

    /**
     * Applies a settings change that the UI asked for.
     *
     * The device page used to write through its own PillClient, which never exists in the
     * normal path because the system side owns the socket -- so every control refused unless
     * the user disconnected and connected by hand. Forwarding the write here lets the screen
     * drive the session that is actually live. Every setter is followed by a fresh read so
     * the UI's optimistic value is corrected by the device.
     */
    private fun applyConfig(intent: Intent) {
        val c = client
        if (c == null || !connected.get()) {
            Log.diag("[Controller] apply config ignored: no live session")
            return
        }
        runCatching {
            when {
                intent.hasExtra("gain_index") ->
                    c.setGain(intent.getIntExtra("gain_index", 0))

                intent.hasExtra("eq_selected") ->
                    c.setEqSet(intent.getIntExtra("eq_selected", 0))

                intent.hasExtra("one_bring_two_enabled") ->
                    c.setOneBringTwoState(intent.getBooleanExtra("one_bring_two_enabled", false))

                intent.hasExtra("one_bring_two_timeout") ->
                    c.setOneBringTwoTimeout(intent.getIntExtra("one_bring_two_timeout", 0))

                intent.hasExtra("voice_enabled") || intent.hasExtra("voice_volume") -> {
                    val current = currentVoiceConf
                    val enabled = intent.getBooleanExtra("voice_enabled", current?.enabled ?: false)
                    val volume = intent.getIntExtra("voice_volume", current?.volume ?: 0)
                    val index = intent.getIntExtra("voice_index", current?.index ?: 0)
                    c.setVoiceConf(enabled, volume, index)
                }

                else -> {
                    Log.diag("[Controller] apply config: no recognised extra")
                    return@runCatching
                }
            }
            Log.diag("[Controller] apply config sent, re-reading")
            queryConfig()
        }.onFailure { t ->
            Log.diag("[Controller] apply config FAILED: ${t.message}", t)
        }
    }

    private fun handleFrame(pdu: ByteArray) {
        val command = GaiaProtocol.commandValueOf(pdu)
        val type = (command shr 7) and 0x03
        val feature = (command shr 9) and 0x7F
        val cmd = command and 0x7F
        if (type != GaiaProtocol.TYPE_RESPONSE) return

        if (feature == GaiaProtocol.FEATURE_BATTERY && cmd == GaiaProtocol.CMD_GET_BATTERY_LEVELS) {
            val levels = GaiaProtocol.parseBatteryLevels(GaiaProtocol.payloadOf(pdu))
            val battery = BatteryParams(
                left = podReading(levels[GaiaProtocol.BATTERY_LEFT]),
                right = podReading(levels[GaiaProtocol.BATTERY_RIGHT]),
                case = podReading(levels[GaiaProtocol.BATTERY_CHARGER_CASE]),
                single = podReading(levels[GaiaProtocol.BATTERY_SINGLE_DEVICE])
            )
            currentBattery = battery
            Log.diag("[Controller] battery L=${battery.left?.battery ?: "--"} R=${battery.right?.battery ?: "--"} C=${battery.case?.battery ?: "--"} single=${battery.single?.battery ?: "--"}")
            sendBatteryBroadcast(battery)
            return
        }

        handleConfigFrame(feature, cmd, GaiaProtocol.payloadOf(pdu))
    }

    /**
     * Relays a configuration answer to whoever is showing the device page.
     *
     * Only the values that have a parser are forwarded; anything else is ignored so an
     * unrecognised or newer command cannot corrupt the UI's cached state.
     */
    private fun handleConfigFrame(feature: Int, cmd: Int, payload: ByteArray) {
        val values = android.os.Bundle()
        var recognised = true
        when {
            feature == GaiaProtocol.FEATURE_DAC_GAIN && cmd == GaiaProtocol.CMD_GET_GAIN ->
                values.putInt("gain_index", GaiaProtocol.parseGainIndex(payload))

            feature == GaiaProtocol.FEATURE_MUSIC_PROCESSING && cmd == GaiaProtocol.CMD_GET_EQ_STATE ->
                values.putInt("eq_state", GaiaProtocol.parseEqState(payload))

            feature == GaiaProtocol.FEATURE_MUSIC_PROCESSING && cmd == GaiaProtocol.CMD_GET_AVAILABLE_EQ_PRE_SETS -> {
                val presets = GaiaProtocol.parseAvailablePreSets(payload)
                values.putIntArray("eq_presets", presets.toIntArray())
            }

            feature == GaiaProtocol.FEATURE_MUSIC_PROCESSING && cmd == GaiaProtocol.CMD_GET_EQ_SET ->
                values.putInt("eq_selected", GaiaProtocol.parseEqSet(payload))

            feature == GaiaProtocol.FEATURE_ONEBRINGTWO && cmd == GaiaProtocol.CMD_GET_ONEBRINGTWO_STATE ->
                values.putBoolean("one_bring_two_enabled", GaiaProtocol.parseOneBringTwoState(payload))

            feature == GaiaProtocol.FEATURE_ONEBRINGTWO && cmd == GaiaProtocol.CMD_GET_ONEBRINGTWO_TIMEOUT ->
                values.putInt("one_bring_two_timeout", GaiaProtocol.parseOneBringTwoTimeout(payload))

            feature == GaiaProtocol.FEATURE_VOICE && cmd == GaiaProtocol.CMD_GET_VOICE_CONF ->
                GaiaProtocol.parseVoiceConf(payload)?.let {
                    currentVoiceConf = it
                    values.putBoolean("voice_enabled", it.enabled)
                    values.putInt("voice_volume", it.volume)
                    values.putInt("voice_index", it.index)
                } ?: run { recognised = false }

            else -> recognised = false
        }
        if (!recognised) {
            Log.d(TAG, "unhandled config response feature=$feature cmd=$cmd")
            return
        }
        Log.diag("[Config] feature=$feature cmd=$cmd -> ${values.keySet().joinToString()}")
        sendConfigBroadcast(values)
    }

    private fun sendConfigBroadcast(values: android.os.Bundle) {
        val ctx = context ?: return
        val base = Intent(HyperPILLAction.ACTION_PODS_CONFIG_CHANGED).apply {
            putExtras(values)
            device?.address?.let { putExtra("address", it) }
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        ctx.sendBroadcast(Intent(base).setPackage(ctx.packageName))
        BROADCAST_TARGETS.forEach { pkg ->
            ctx.sendBroadcast(Intent(base).setPackage(pkg))
        }
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
