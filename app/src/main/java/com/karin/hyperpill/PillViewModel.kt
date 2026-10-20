package com.karin.hyperpill

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karin.hyperpill.pods.GaiaFeature
import com.karin.hyperpill.pods.GaiaProtocol
import com.karin.hyperpill.pods.PillClient
import com.karin.hyperpill.pods.VoiceConf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PillUiState(
    val devices: List<BluetoothDevice> = emptyList(),
    val selectedDevice: BluetoothDevice? = null,
    /**
     * Live session facts learned from the controller's broadcasts.
     *
     * The controller runs inside the hooked bluetooth host, so this process's own
     * PillSystemController instance never has a session of its own and cannot answer
     * "which device is connected". The broadcasts carry the address and name; keeping
     * them here lets the UI show a real device on a cold start instead of "--".
     */
    val connectedAddress: String? = null,
    val connectedDeviceName: String? = null,
    val connected: Boolean = false,
    val batteryLevels: Map<Int, Int> = emptyMap(),
    val supportedBatteries: List<Int> = emptyList(),
    val features: List<GaiaFeature> = emptyList(),
    val gainIndex: Int? = null,
    val eqState: Int? = null,
    val eqPresets: List<Int> = emptyList(),
    val eqSelected: Int? = null,
    val oneBringTwoEnabled: Boolean? = null,
    val oneBringTwoTimeout: Int? = null,
    val voiceConf: VoiceConf? = null,
    val spoofedDeviceName: String? = null,
    val message: String = "",
    val busy: Boolean = false,
    val refreshingDevices: Boolean = false
)

class PillViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PillUiState())
    val uiState: StateFlow<PillUiState> = _uiState.asStateFlow()

    private var client: PillClient? = null
    private var pendingFeatures: List<GaiaFeature> = emptyList()

    /**
     * While the system-side controller owns the RFCOMM session, this screen has no socket
     * of its own and therefore no frames to show. Listening to the broadcast mirror keeps
     * the battery readout and the connected/disconnected state live without competing for
     * the SPP channel.
     */
    private var mirrorReceiver: android.content.BroadcastReceiver? = null

    fun startMirroringSystemSession() {
        if (mirrorReceiver != null) return
        val ctx = getApplication<Application>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_BATTERY_CHANGED -> {
                        // Fill connectedAddress/name from the broadcast FIRST, so that
                        // adoptSystemSessionIfAny can resolve the device even on a cold start
                        // where the paired-device list is empty. Previously the adopt ran
                        // before the address was recorded, so a live session showed as
                        // "not connected" until the user tapped connect by hand.
                        applyBroadcastBattery(intent)
                        adoptSystemSessionIfAny()
                    }
                    com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_CONFIG_CHANGED -> {
                        applyBroadcastConfig(intent)
                    }
                    com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_DISCONNECTED -> {
                        com.karin.hyperpill.hook.Log.diag("[ViewModel] mirror saw disconnect, clearing UI")
                        client = null
                        _uiState.update {
                            it.copy(
                                connected = false,
                                selectedDevice = null,
                                connectedAddress = null,
                                connectedDeviceName = null,
                                batteryLevels = emptyMap(),
                                message = "已断开"
                            )
                        }
                    }
                    com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_CONNECTED -> {
                        com.karin.hyperpill.hook.Log.diag("[ViewModel] mirror saw connect for ${intent.getStringExtra("address")}")
                        _uiState.update {
                            it.copy(
                                connected = true,
                                connectedAddress = intent.getStringExtra("address") ?: it.connectedAddress,
                                connectedDeviceName = intent.getStringExtra("device_name") ?: it.connectedDeviceName,
                                selectedDevice = it.selectedDevice ?: resolveConnectedDevice()
                            )
                        }
                        adoptSystemSessionIfAny()
                    }
                }
            }
        }
        ctx.registerReceiver(
            receiver,
            android.content.IntentFilter().apply {
                addAction(com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_BATTERY_CHANGED)
                addAction(com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_CONFIG_CHANGED)
                addAction(com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_CONNECTED)
                addAction(com.karin.hyperpill.pods.HyperPILLAction.ACTION_PODS_DISCONNECTED)
            },
            // Required from Android 14 (API 34) on: a runtime-registered receiver for a
            // non-system action must declare its export behaviour or registerReceiver
            // throws SecurityException. This runs from MainActivity.onCreate, so the
            // omission did not merely lose battery updates -- it killed the process on
            // every launch and left the UI unable to show that a session was already up.
            // Exported: these broadcasts are sent by the hooked bluetooth host, which runs
            // under a different uid, so a not-exported receiver would never see them.
            android.content.Context.RECEIVER_EXPORTED
        )
        mirrorReceiver = receiver
        adoptSystemSessionIfAny()
        // Ask the controller to re-announce its session right now instead of waiting up to
        // BATTERY_POLL_INTERVAL_MS (30s) for the next poll. On a cold launcher the UI would
        // otherwise sit on "not connected" for up to 30s even though the pods are already up,
        // which read as a manual-tap-required bug. The controller re-emits connected +
        // battery + config on this signal.
        requestRefresh()
    }

    /** Tells the system-side controller to re-announce the current session immediately. */
    private fun requestRefresh() {
        val ctx = getApplication<Application>().applicationContext
        runCatching {
            com.karin.hyperpill.pods.PillSystemController.CONTROL_TARGET_PACKAGES.forEach { pkg ->
                ctx.sendBroadcast(
                    android.content.Intent(
                        com.karin.hyperpill.pods.HyperPILLAction.ACTION_REFRESH_STATUS
                    ).setPackage(pkg)
                )
            }
            com.karin.hyperpill.hook.Log.diag("[ViewModel] requested immediate session refresh")
        }
    }

    fun stopMirroringSystemSession() {
        mirrorReceiver?.let { runCatching { getApplication<Application>().unregisterReceiver(it) } }
        mirrorReceiver = null
    }

    /**
     * Maps the broadcast's per-pod percentages onto the same keys [PillClient] frames use,
     * so the UI renders system-sourced battery identically to locally-read battery.
     * The extras are emitted by PillSystemController.sendBatteryBroadcast as
     * <pod>_battery / <pod>_connected, and absent pods are sent as 0/false.
     */
    private fun applyBroadcastBattery(intent: android.content.Intent) {
        val levels = linkedMapOf<Int, Int>()
        fun record(extraBattery: String, extraConnected: String, key: Int) {
            if (!intent.getBooleanExtra(extraConnected, false)) return
            levels[key] = intent.getIntExtra(extraBattery, 0)
        }
        record("left_battery", "left_connected", GaiaProtocol.BATTERY_LEFT)
        record("right_battery", "right_connected", GaiaProtocol.BATTERY_RIGHT)
        // Single-pod devices surface one aggregate level instead of left/right; without
        // this the UI showed a connected device with no battery reading at all.
        if (levels.isEmpty()) {
            record("single_battery", "single_connected", GaiaProtocol.BATTERY_SINGLE_DEVICE)
        }
        if (levels.isEmpty()) return
        _uiState.update {
            it.copy(
                batteryLevels = levels,
                connected = true,
                busy = false,
                connectedAddress = intent.getStringExtra("address") ?: it.connectedAddress,
                connectedDeviceName = intent.getStringExtra("device_name") ?: it.connectedDeviceName
            )
        }
    }

    /**
     * Folds a configuration broadcast from the system-side controller into UI state.
     *
     * The device page's controls read these fields, and they used to be filled only by this
     * process's own PillClient -- which never connects when the system side owns the socket.
     * Every control therefore showed "--" and refused to act. The controller now asks the
     * headset directly and forwards the answers here.
     */
    private fun applyBroadcastConfig(intent: android.content.Intent) {
        val voice = if (intent.hasExtra("voice_enabled")) {
            VoiceConf(
                enabled = intent.getBooleanExtra("voice_enabled", false),
                volume = intent.getIntExtra("voice_volume", 0),
                index = intent.getIntExtra("voice_index", 0)
            )
        } else {
            null
        }
        _uiState.update { state ->
            state.copy(
                gainIndex = if (intent.hasExtra("gain_index")) {
                    intent.getIntExtra("gain_index", 0)
                } else {
                    state.gainIndex
                },
                eqState = if (intent.hasExtra("eq_state")) {
                    intent.getIntExtra("eq_state", 0)
                } else {
                    state.eqState
                },
                eqPresets = intent.getIntArrayExtra("eq_presets")?.toList() ?: state.eqPresets,
                eqSelected = if (intent.hasExtra("eq_selected")) {
                    intent.getIntExtra("eq_selected", 0)
                } else {
                    state.eqSelected
                },
                oneBringTwoEnabled = if (intent.hasExtra("one_bring_two_enabled")) {
                    intent.getBooleanExtra("one_bring_two_enabled", false)
                } else {
                    state.oneBringTwoEnabled
                },
                oneBringTwoTimeout = if (intent.hasExtra("one_bring_two_timeout")) {
                    intent.getIntExtra("one_bring_two_timeout", 0)
                } else {
                    state.oneBringTwoTimeout
                },
                voiceConf = voice ?: state.voiceConf
            )
        }
    }

    override fun onCleared() {
        stopMirroringSystemSession()
        super.onCleared()
    }

    fun refreshDevices() {
        _uiState.update { it.copy(refreshingDevices = true) }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            _uiState.update { it.copy(message = "设备不支持蓝牙", refreshingDevices = false) }
            return
        }
        val pills = adapter.bondedDevices
            ?.filter { isPillName(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        _uiState.update {
            it.copy(
                devices = pills,
                message = if (pills.isEmpty()) "未找到已配对的 Pill 设备" else "找到 ${pills.size} 个 Pill 设备",
                refreshingDevices = false
            )
        }
        adoptSystemSessionIfAny(pills)
    }

    /**
     * Mirrors an already-live system-side session into the UI.
     *
     * The module connects on its own as soon as the headset comes up, so opening the app
     * during a normal connection used to show "not connected" and require a manual tap
     * purely because the UI never looked at the session that already exists. Adopting it
     * here keeps the screen honest and removes the pointless tap.
     */
    private fun adoptSystemSessionIfAny(pills: List<BluetoothDevice> = _uiState.value.devices) {
        // Prefer a device from the caller's list, but fall back to whatever the controller
        // has announced over its broadcasts. On a cold launcher the list is empty until the
        // user taps refresh, so keying only off it meant a live session showed as
        // "not connected" and required a manual tap even though the focus island had fired.
        val live = pills.firstOrNull {
            com.karin.hyperpill.pods.PillSystemController.isConnectedTo(it.address)
        } ?: resolveConnectedDevice()
        ?: return
        val name = live.name ?: _uiState.value.connectedDeviceName ?: live.address
        com.karin.hyperpill.hook.Log.diag("[ViewModel] adopting live system session for ${live.address} name=${live.name}")
        _uiState.update {
            it.copy(
                selectedDevice = live,
                connected = true,
                busy = false,
                connectedAddress = live.address,
                connectedDeviceName = _uiState.value.connectedDeviceName ?: live.name,
                message = "已连接 ${name}"
            )
        }
    }

    /**
     * Resolves the connected BluetoothDevice without relying on the paired-device list.
     *
     * The controller is an in-process singleton, so in this process it is always empty --
     * the real session lives in the hooked bluetooth host. The address learned from that
     * host's broadcasts is the only authoritative source here; resolve it via the adapter so
     * BluetoothDevice.name still works.
     */
    @Suppress("MissingPermission")
    private fun resolveConnectedDevice(): BluetoothDevice? {
        com.karin.hyperpill.pods.PillSystemController.connectedDevice()?.let { return it }
        val address = com.karin.hyperpill.pods.PillSystemController.connectedAddress()
            ?: _uiState.value.connectedAddress
            ?: return null
        return runCatching {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { it.address == address }
        }.getOrNull()
    }

    /** Re-syncs UI state with the system controller whenever the screen is shown. */
    fun syncWithSystemSession() {
        adoptSystemSessionIfAny()
    }

    fun spoofDevice(name: String?) {
        _uiState.update { it.copy(spoofedDeviceName = name, message = if (name != null) "已伪装连接：$name" else "已取消伪装") }
    }

    fun connect(device: BluetoothDevice) {
        // PillSystemController is an in-process singleton, so the hooked bluetooth host
        // and this UI process each own an independent instance and an independent RFCOMM
        // socket for the same SPP UUID. Opening a second one tears down whichever session
        // got there first ("read failed, socket might closed"), which is the long-standing
        // "first connect fails, tap again to succeed" symptom. When the system side already
        // holds the session, reuse it and drive commands through it instead of racing it.
        if (com.karin.hyperpill.pods.PillSystemController.isConnectedTo(device.address)) {
            com.karin.hyperpill.hook.Log.diag("[ViewModel] system controller already holds ${device.address}, reusing it")
            _uiState.update {
                it.copy(
                    selectedDevice = device,
                    connected = true,
                    busy = false,
                    message = "已连接 ${device.name}"
                )
            }
            return
        }
        if (_uiState.value.connected) return
        _uiState.update { it.copy(selectedDevice = device, busy = true, message = "正在连接 ${device.name}...") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val c = PillClient(device, object : PillClient.Listener {
                    override fun onConnected() {
                        _uiState.update {
                            it.copy(
                                connected = true,
                                busy = false,
                                message = "已连接 ${device.name}"
                            )
                        }
                        runCatching { client?.requestSupportedFeatures() }
                        runCatching { client?.requestSupportedBatteries() }
                        runCatching { client?.requestBatteryLevels() }
                        runCatching { client?.requestGain() }
                        runCatching { client?.requestEqState() }
                        runCatching { client?.requestAvailablePreSets() }
                        runCatching { client?.requestEqSet() }
                        runCatching { client?.requestOneBringTwoState() }
                        runCatching { client?.requestOneBringTwoTimeout() }
                        runCatching { client?.requestVoiceConf() }
                    }

                    override fun onFrame(pdu: ByteArray) {
                        handleFrame(pdu)
                    }

                    override fun onError(throwable: Throwable) {
                        _uiState.update { it.copy(connected = false, busy = false, message = "错误: ${throwable.message}") }
                    }

                    override fun onDisconnected() {
                        _uiState.update { it.copy(connected = false, busy = false, message = "连接已断开") }
                    }
                })
                client = c
                c.connect()
            }.onFailure { t ->
                _uiState.update { it.copy(connected = false, busy = false, message = "连接失败: ${t.message}") }
            }
        }
    }

    fun disconnect() {
        // Releasing a session that belongs to the system-side controller would tear down
        // the very socket feeding the notification centre and the focus island; only the
        // UI-owned client is ours to close. The system session ends on its own when the
        // headset actually disconnects.
        val ownsSocket = client != null
        if (!ownsSocket) {
            com.karin.hyperpill.hook.Log.diag("[ViewModel] disconnect() without an owned socket, leaving system session alone")
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (ownsSocket) {
                client?.close()
            }
            client = null
            pendingFeatures = emptyList()
            _uiState.update {
                it.copy(
                    connected = false,
                    selectedDevice = null,
                    batteryLevels = emptyMap(),
                    supportedBatteries = emptyList(),
                    features = emptyList(),
                    gainIndex = null,
                    eqState = null,
                    eqPresets = emptyList(),
                    eqSelected = null,
                    oneBringTwoEnabled = null,
                    oneBringTwoTimeout = null,
                    voiceConf = null,
                    message = "已断开"
                )
            }
        }
    }

    /**
     * Controls below drive the GAIA channel through this screen's own client. When the
     * system controller owns the RFCOMM socket there is no local client, and silently
     * returning would make every switch look broken, so surface the reason instead.
     */
    private fun requireLocalClient(): PillClient? {
        val c = client
        if (c == null) {
            com.karin.hyperpill.hook.Log.diag("[ViewModel] no local client; system controller owns the session")
            _uiState.update {
                it.copy(message = "该设置需要 App 直连，请先断开系统连接后在 App 内连接")
            }
        }
        return c
    }

    /**
     * Sends a configuration write over whichever channel currently owns the headset.
     *
     * In the normal path the system-side controller holds the RFCOMM socket and this process
     * has no client at all, so routing every write through [requireLocalClient] made the whole
     * device page read-only even while the pods were connected. The write is forwarded to the
     * controller over a broadcast instead of opening a second socket, which cannot coexist
     * with the live one.
     */
    private fun writeConfig(action: String, vararg extras: Pair<String, Any?>) {
        client?.let { return }
        val ctx = getApplication<Application>().applicationContext
        val base = android.content.Intent(com.karin.hyperpill.pods.HyperPILLAction.ACTION_APPLY_CONFIG)
        extras.forEach { (key, value) ->
            when (value) {
                is Boolean -> base.putExtra(key, value)
                is Int -> base.putExtra(key, value)
                is String -> base.putExtra(key, value)
            }
        }
        com.karin.hyperpill.pods.PillSystemController.CONTROL_TARGET_PACKAGES.forEach { pkg ->
            ctx.sendBroadcast(android.content.Intent(base).setPackage(pkg))
        }
        com.karin.hyperpill.hook.Log.diag("[ViewModel] forwarded config write action=$action via controller")
    }

    fun refreshBattery() {
        val c = requireLocalClient() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { c.requestBatteryLevels() }
                .onFailure { _uiState.update { it.copy(message = "刷新电量失败: ${it.message}") } }
        }
    }

    fun setGain(index: Int) {
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setGain(index) }
                    .onSuccess { _uiState.update { it.copy(gainIndex = index, message = "增益已设为 $index") } }
                    .onFailure { _uiState.update { it.copy(message = "设置增益失败: ${it.message}") } }
            }
            return
        }
        writeConfig("set_gain", "gain_index" to index)
        _uiState.update { it.copy(gainIndex = index) }
    }

    fun selectEqSet(presetId: Int) {
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setEqSet(presetId) }
                    .onSuccess { _uiState.update { it.copy(eqSelected = presetId, message = "EQ 已切换为 $presetId") } }
                    .onFailure { _uiState.update { it.copy(message = "切换 EQ 失败: ${it.message}") } }
            }
            return
        }
        writeConfig("set_eq", "eq_selected" to presetId)
        _uiState.update { it.copy(eqSelected = presetId) }
    }

    fun setOneBringTwoState(enabled: Boolean) {
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setOneBringTwoState(enabled) }
                    .onSuccess { _uiState.update { it.copy(oneBringTwoEnabled = enabled, message = if (enabled) "双设备连接已开启" else "双设备连接已关闭") } }
                    .onFailure { _uiState.update { it.copy(message = "设置双设备连接失败: ${it.message}") } }
            }
            return
        }
        writeConfig("one_bring_two_state", "one_bring_two_enabled" to enabled)
        _uiState.update { it.copy(oneBringTwoEnabled = enabled) }
    }

    fun setOneBringTwoTimeout(timeout: Int) {
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setOneBringTwoTimeout(timeout) }
                    .onSuccess { _uiState.update { it.copy(oneBringTwoTimeout = timeout, message = "双设备超时已设为 $timeout") } }
                    .onFailure { _uiState.update { it.copy(message = "设置双设备超时失败: ${it.message}") } }
            }
            return
        }
        writeConfig("one_bring_two_timeout", "one_bring_two_timeout" to timeout)
        _uiState.update { it.copy(oneBringTwoTimeout = timeout) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        val current = _uiState.value.voiceConf
        val volume = current?.volume ?: 100
        val index = current?.index ?: 0
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setVoiceConf(enabled, volume, index) }
                    .onSuccess {
                        _uiState.update {
                            it.copy(
                                voiceConf = VoiceConf(enabled = enabled, volume = volume, index = index),
                                message = if (enabled) "提示音已开启" else "提示音已关闭"
                            )
                        }
                    }
                    .onFailure { _uiState.update { it.copy(message = "设置提示音开关失败: ${it.message}") } }
            }
            return
        }
        writeConfig("set_voice_conf", "voice_enabled" to enabled, "voice_volume" to volume, "voice_index" to index)
        _uiState.update {
            it.copy(
                voiceConf = VoiceConf(enabled = enabled, volume = volume, index = index),
                message = if (enabled) "提示音已开启" else "提示音已关闭"
            )
        }
    }

    fun setVoiceVolume(volume: Int) {
        val current = _uiState.value.voiceConf
        val enabled = current?.enabled ?: true
        val index = current?.index ?: 0
        client?.let { c ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { c.setVoiceConf(enabled, volume, index) }
                    .onSuccess {
                        _uiState.update {
                            it.copy(
                                voiceConf = VoiceConf(enabled = enabled, volume = volume, index = index),
                                message = "提示音音量已设为 $volume%"
                            )
                        }
                    }
                    .onFailure { _uiState.update { it.copy(message = "设置提示音音量失败: ${it.message}") } }
            }
            return
        }
        writeConfig("set_voice_conf", "voice_enabled" to enabled, "voice_volume" to volume, "voice_index" to index)
        _uiState.update {
            it.copy(
                voiceConf = VoiceConf(enabled = enabled, volume = volume, index = index),
                message = "提示音音量已设为 $volume%"
            )
        }
    }

    private fun handleFrame(pdu: ByteArray) {
        val command = GaiaProtocol.commandValueOf(pdu)
        val type = (command shr 7) and 0x03
        val feature = (command shr 9) and 0x7F
        val cmd = command and 0x7F
        if (type != GaiaProtocol.TYPE_RESPONSE) return

        val payload = GaiaProtocol.payloadOf(pdu)

        if (feature == GaiaProtocol.FEATURE_BASIC) {
            when (cmd) {
                GaiaProtocol.CMD_GET_SUPPORTED_FEATURES,
                GaiaProtocol.CMD_GET_SUPPORTED_FEATURES_NEXT -> handleFeatures(payload)
            }
            return
        }

        when (feature) {
            GaiaProtocol.FEATURE_BATTERY -> when (cmd) {
                GaiaProtocol.CMD_GET_SUPPORTED_BATTERIES -> {
                    val supported = GaiaProtocol.parseSupportedBatteries(payload)
                    _uiState.update { it.copy(supportedBatteries = supported) }
                }
                GaiaProtocol.CMD_GET_BATTERY_LEVELS -> {
                    val levels = GaiaProtocol.parseBatteryLevels(payload)
                    _uiState.update { it.copy(batteryLevels = levels, message = "电量已更新") }
                }
            }
            GaiaProtocol.FEATURE_DAC_GAIN -> if (cmd == GaiaProtocol.CMD_GET_GAIN) {
                _uiState.update { it.copy(gainIndex = GaiaProtocol.parseGainIndex(payload)) }
            }
            GaiaProtocol.FEATURE_MUSIC_PROCESSING -> when (cmd) {
                GaiaProtocol.CMD_GET_EQ_STATE -> {
                    _uiState.update { it.copy(eqState = GaiaProtocol.parseEqState(payload)) }
                }
                GaiaProtocol.CMD_GET_AVAILABLE_EQ_PRE_SETS -> {
                    _uiState.update { it.copy(eqPresets = GaiaProtocol.parseAvailablePreSets(payload)) }
                }
                GaiaProtocol.CMD_GET_EQ_SET -> {
                    _uiState.update { it.copy(eqSelected = GaiaProtocol.parseEqSet(payload)) }
                }
            }
            GaiaProtocol.FEATURE_ONEBRINGTWO -> when (cmd) {
                GaiaProtocol.CMD_GET_ONEBRINGTWO_STATE -> {
                    _uiState.update { it.copy(oneBringTwoEnabled = GaiaProtocol.parseOneBringTwoState(payload)) }
                }
                GaiaProtocol.CMD_GET_ONEBRINGTWO_TIMEOUT -> {
                    _uiState.update { it.copy(oneBringTwoTimeout = GaiaProtocol.parseOneBringTwoTimeout(payload)) }
                }
            }
            GaiaProtocol.FEATURE_VOICE -> if (cmd == GaiaProtocol.CMD_GET_VOICE_CONF) {
                GaiaProtocol.parseVoiceConf(payload)?.let { conf ->
                    _uiState.update { it.copy(voiceConf = conf, message = "提示音状态已读取") }
                }
            }
        }
    }

    private fun handleFeatures(payload: ByteArray) {
        val page = GaiaProtocol.parseSupportedFeatures(payload)
        val merged = (pendingFeatures + page.features).distinctBy { it.id }
        if (page.hasMoreData) {
            pendingFeatures = merged
            runCatching { client?.requestSupportedFeaturesNext() }
        } else {
            pendingFeatures = emptyList()
            _uiState.update {
                it.copy(
                    features = merged,
                    message = "已发现 ${merged.size} 个功能: ${merged.joinToString { GaiaProtocol.featureName(it.id) }}"
                )
            }
        }
    }

    private fun isPillName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("pill") || n.contains("moondrop") || n.contains("oba") || n.contains("laplace")
    }
}
